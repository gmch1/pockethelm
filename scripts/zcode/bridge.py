#!/usr/bin/env python3
"""Restricted PocketHelm JSONL adapter for ZCode Server 3.16.x.

No generic RPC forwarding, account mutations, token export or shell endpoint.
The user's Full access tasks still execute with the development account's rights.
"""
import base64
import hashlib
import json
import math
from pathlib import Path
import re
import signal
import sys
import time
import uuid
import zlib
from wire import Client, WireError, MAX_FRAME
from session_index import SessionIndexes

MAX_LINE = 256 * 1024

# Interaction projection limits. Identity fields (interaction/option IDs, option values,
# question texts that key content.answers) are validated against these bounds and never
# truncated — a violation downgrades the whole item to supported=false instead of trimming
# it and letting a deformed answer through. Display-only strings are clipped separately.
INTERACTION_ID_MAX = 1024
INTERACTION_QUESTIONS_MAX = 16
INTERACTION_OPTIONS_MAX = 32
INTERACTION_QUESTION_CHARS = 8192
INTERACTION_OPTION_VALUE_CHARS = 4096
INTERACTION_ANSWER_TEXT_CHARS = 16384
INTERACTION_JOINED_CHARS = 65536
INTERACTION_ANSWER_BYTES = 64 * 1024
INTERACTION_DETAIL_BYTES = 32 * 1024
INTERACTION_DETAILS_MAX = 4
INTERACTION_CHECK_IDS_MAX = 16

# Image attachments (task 2026-11-03-image-attachments). Two-stage chunking: the phone
# sends 96KiB raw chunks that safely fit the 256KiB JSONL line cap after base64; the
# bridge re-aggregates them on raw-byte boundaries into the official 384KiB chunks
# (96*4=384, so phone blocks never straddle an upstream boundary). Phone chunk counts
# and progress are never forwarded upstream — official begin sees upstream counts only.
ATTACHMENT_MIME_ALLOWLIST = ('image/jpeg',)
ATTACHMENT_MAX_BYTES = 8 * 1024 * 1024
ATTACHMENT_MESSAGE_MAX = 4
ATTACHMENT_ROW_MAX = 8   # official per-message cap; rows beyond the bridge cap get a marker
ATTACHMENT_PHONE_CHUNK = 96 * 1024
ATTACHMENT_UPSTREAM_CHUNK = 384 * 1024
ATTACHMENT_TRANSACTIONS_MAX = 4
ATTACHMENT_COMMITTED_MAX = 32
ATTACHMENT_TTL_SECONDS = 600


class RequestError(Exception):
    pass


def require(condition, message):
    if not condition:
        raise RequestError(message)


def string(value, maximum=1024):
    require(isinstance(value, str) and 0 < len(value) <= maximum and '\x00' not in value, '参数无效')
    return value


def pick(source, keys):
    return {key: source[key] for key in keys if key in source}


def project_quota(data, kind):
    # Export display fields only; subscription, account and bucket identities stay on host.
    quota = data.get('quota')
    result = {'planKind': kind, 'limits': []}
    reason = data.get('unavailableReason')
    if reason in ('not_authenticated', 'not_configured', 'no_plan', 'unavailable'):
        result['unavailableReason'] = reason
    if data.get('startPlanExpired') is True:
        result['startPlanExpired'] = True
    if not isinstance(quota, dict):
        return result
    limits = quota.get('limits')
    for limit in limits if isinstance(limits, list) else []:
        if not isinstance(limit, dict):
            continue
        item = {k: v for k, v in pick(limit, ['unit', 'number', 'usage', 'currentValue',
            'remaining', 'percentage', 'nextResetTime']).items()
            if type(v) in (int, float) and math.isfinite(v) and v >= 0}
        item.update({k: v[:160] for k, v in pick(limit, ['type', 'period', 'unitType']).items()
            if isinstance(v, str)})
        details = limit.get('usageDetails')
        item['usageDetails'] = [{k: v[:160] for k, v in pick(detail, ['modelCode', 'displayName']).items()
            if isinstance(v, str)} for detail in (details if isinstance(details, list) else [])
            if isinstance(detail, dict)]
        result['limits'].append(item)
    return result


def selection(source):
    require(isinstance(source, dict) and set(source) <= {'providerId', 'modelId', 'options'}, '模型配置无效')
    result = {k: string(source.get(k)) for k in ('providerId', 'modelId')}
    if 'options' in source:
        options = source['options']
        require(isinstance(options, dict) and set(options) == {'reasoningLevel'}, '推理配置无效')
        result['options'] = {'reasoningLevel': string(options['reasoningLevel'], 100)}
    return result


def guard_for(provider, settings):
    # Includes account identity and exact team/project selection, not just provider ID.
    # No account identifier or credential is returned to the phone.
    data = [provider['providerId'], provider.get('accountState'), provider.get('effectiveConfig'),
        pick(settings, ['providerFamilyDomain', 'providerFamilyConnectionSelections', 'providerFamilyDomainUpdatedAt'])]
    return hashlib.sha256(json.dumps(data, sort_keys=True, separators=(',', ':')).encode()).hexdigest()


def bounded_int(value, maximum=2**53):
    # Authoritative counters (diff stats, durations, revisions): pass through finite
    # non-negative numbers only; anything else is dropped rather than guessed.
    if type(value) is int and 0 <= value <= maximum:
        return value
    return None


def project_rows(rows):
    result = []
    for row in rows:
        kind = row.get('kind')
        if kind == 'turnHeader':
            continue
        item = {'id': str(row['rowId']), 'kind': kind, 'status': row.get('state', row.get('status', ''))}
        # Row identity for detail queries and (P2) row-targeted commands; display rows
        # never re-key on it, and an absent or malformed value simply omits the field.
        entity = row.get('entityId')
        if isinstance(entity, str) and 0 < len(entity) <= 1024 and '\x00' not in entity:
            item['entityId'] = entity
        if kind in ('userInput', 'assistantText', 'reasoning'):
            item['text'] = row.get('text', '')[:120000]
            item['commandId'] = row.get('sourceCommandId', '')
            attachments = project_row_attachments(row.get('attachments'))
            if attachments:
                item['attachments'] = attachments
        elif kind == 'toolCall':
            # Omit arbitrary metadata and binary attachments. Detail remains opt-in in UI.
            output = row.get('output', {})
            item['text'] = row.get('toolName', '工具') + '\n' + row.get('inputText', '')[:8000]
            if isinstance(output, dict) and isinstance(output.get('text'), str):
                item['text'] += '\n' + output['text'][-16000:]
            if row.get('error'):
                item['text'] += '\n' + str(row['error'].get('message', '执行失败'))[:2000]
        else:
            item['text'] = str(row.get('displayName') or row.get('description') or row.get('text') or kind)[:2000]
        result.append(item)
    return result


def project_row_attachments(source):
    """Bounded attachment metadata for userInput rows: index, display name, mime, size.
    Malformed entries are skipped; valid entries beyond the cap set a truncation marker —
    dropped attachments are real data (desktop clients may send up to 8), never 'invalid'.
    ref/preview fields never leave the host."""
    if not isinstance(source, list) or not source:
        return []
    result = []
    truncated = False
    for index, entry in enumerate(source):
        if not isinstance(entry, dict):
            truncated = True
            continue
        name = entry.get('fileName')
        mime = entry.get('mime')
        size = entry.get('bytes')
        if not isinstance(name, str) or not 0 < len(name) <= 255 or not isinstance(mime, str) \
                or not 0 < len(mime) <= 100 or type(size) is not int or size < 0:
            truncated = True
            continue
        clean = re.sub(r'[\x00-\x1f\x7f]', '', name)
        if not clean:
            truncated = True
            continue
        if len(result) >= ATTACHMENT_ROW_MAX:
            truncated = True
            break
        result.append({'index': index, 'fileName': clean, 'mime': mime, 'bytes': size})
    if truncated:
        result.append({'index': -1, 'fileName': '…', 'mime': '', 'bytes': 0, 'truncated': True})
    return result


def project_turn(rows):
    """Project the latest turnHeader of the window: the authoritative per-turn work time
    and CLI-aggregated file change totals (repeat writes and reverts are already netted
    out upstream — clients must not sum tool diffs themselves). The rowId/entityId pair
    is the target for the conversationFileChanges detail query."""
    header = None
    for row in rows:
        if isinstance(row, dict) and row.get('kind') == 'turnHeader':
            header = row
    if not header or type(header.get('rowId')) is not int or header['rowId'] < 0:
        return None
    entity = header.get('entityId')
    result = {'rowId': header['rowId'],
        'entityId': entity if isinstance(entity, str) and 0 < len(entity) <= 1024 and '\x00' not in entity else '',
        'state': header.get('state') if header.get('state') in
            ('running', 'completedSuccess', 'completedInterrupted', 'failed') else ''}
    active = bounded_int(header.get('activeMs'))
    if active is not None:
        result['activeMs'] = active
    changes = header.get('fileChanges')
    if isinstance(changes, dict):
        summary = {}
        for key in ('additions', 'deletions', 'files'):
            value = bounded_int(changes.get(key))
            if value is not None:
                summary[key] = value
        if changes.get('state') in ('active', 'reverted'):
            summary['state'] = changes['state']
        if summary:
            result['fileChanges'] = summary
    return result


FILE_CHANGES_FILES_MAX = 200
FILE_CHANGES_HUNKS_MAX = 64
FILE_CHANGES_HUNK_LINES_MAX = 400
FILE_CHANGES_LINE_CHARS = 1000


def project_file_changes(data):
    """Bounded projection of conversationFileChangesV4: per-file identity, CLI counters,
    tool attribution and patch hunks. Malformed entries are skipped, never fatal."""
    require(isinstance(data, dict), '更改明细无效')
    result = {}
    for key in ('files', 'additions', 'deletions'):
        value = bounded_int(data.get(key))
        if value is not None:
            result[key] = value
    if data.get('state') in ('active', 'reverted'):
        result['state'] = data['state']
    items = []
    for entry in (data.get('items') or [])[:FILE_CHANGES_FILES_MAX]:
        if not isinstance(entry, dict):
            continue
        path = entry.get('path')
        if not isinstance(path, str) or not 0 < len(path) <= 4096 or '\x00' in path:
            continue
        item = {'path': path}
        for key in ('additions', 'deletions', 'writeCount'):
            value = bounded_int(entry.get(key))
            if value is not None:
                item[key] = value
        names = entry.get('toolNames')
        if isinstance(names, list):
            mapped = [name for name in names[:8] if isinstance(name, str) and name][:8]
            if mapped:
                item['toolNames'] = [name[:200] for name in mapped]
        hunks = []
        total_lines = 0
        for hunk in (entry.get('patches') or [])[:FILE_CHANGES_HUNKS_MAX]:
            if not isinstance(hunk, dict) or not isinstance(hunk.get('lines'), list):
                continue
            lines = []
            for line in hunk['lines']:
                if not isinstance(line, str):
                    continue
                if total_lines >= FILE_CHANGES_HUNK_LINES_MAX:
                    break
                lines.append(line[:FILE_CHANGES_LINE_CHARS])
                total_lines += 1
            if not lines:
                continue
            mapped = {'lines': lines}
            for key in ('oldStart', 'oldLines', 'newStart', 'newLines'):
                value = hunk.get(key)
                if type(value) is int:
                    mapped[key] = value
            hunks.append(mapped)
        if hunks:
            item['patches'] = hunks
        items.append(item)
    result['items'] = items
    return result


def display_text(value, maximum):
    # Display-only text: clipped, never used to rebuild protocol identity.
    return value[:maximum] if isinstance(value, str) else ''


def project_auto_resolution(source):
    if not isinstance(source, dict) or source.get('state') not in ('hiddenGrace', 'visibleCountdown', 'snoozed'):
        return None
    result = {'state': source['state']}
    for key in ('startedAt', 'visibleAt', 'deadlineAt', 'snoozedAt'):
        value = source.get(key)
        if type(value) in (int, float) and math.isfinite(value):
            result[key] = value
    return result


def project_questions(questions):
    require(isinstance(questions, list) and 0 < len(questions) <= INTERACTION_QUESTIONS_MAX, '交互问题超出限制')
    result = []
    for question in questions:
        require(isinstance(question, dict), '交互问题格式无效')
        options = question.get('options')
        require(isinstance(options, list) and 0 < len(options) <= INTERACTION_OPTIONS_MAX, '交互选项超出限制')
        mapped = []
        values = set()
        for option in options:
            require(isinstance(option, dict), '交互选项格式无效')
            value = string(option.get('value'), INTERACTION_OPTION_VALUE_CHARS)
            require(value not in values, '交互选项重复')
            values.add(value)
            item = {'value': value, 'label': display_text(option.get('label'), 512)}
            if isinstance(option.get('description'), str):
                item['description'] = display_text(option['description'], 1024)
            mapped.append(item)
        entry = {'question': string(question.get('question'), INTERACTION_QUESTION_CHARS),
            'header': display_text(question.get('header'), 512), 'options': mapped}
        if question.get('multiSelect') is True:
            entry['multiSelect'] = True
        result.append(entry)
    return result


def project_answer_drafts(drafts):
    require(isinstance(drafts, dict), '交互草稿无效')
    result = {}
    for question, answers in drafts.items():
        require(isinstance(question, str) and 0 < len(question) <= INTERACTION_QUESTION_CHARS
            and '\x00' not in question, '交互草稿无效')
        require(isinstance(answers, list) and len(answers) <= INTERACTION_OPTIONS_MAX, '交互草稿无效')
        result[question] = [string(answer, INTERACTION_ANSWER_TEXT_CHARS) for answer in answers]
    require(len(result) <= INTERACTION_QUESTIONS_MAX, '交互草稿无效')
    return result


def project_interaction(interaction):
    """Project one pendingInteraction into a bounded answerable detail.

    Returns (detail, id_preserved). Identity violations downgrade the whole item to a
    fixed unsupported placeholder rather than trimming questions/options and continuing.
    """
    require(isinstance(interaction, dict), '交互格式无效')
    kind = interaction.get('kind')
    require(kind in ('permission', 'userInput', 'workspaceHookReview'), '未知交互类型')
    raw_id = interaction.get('interactionId')
    id_preserved = isinstance(raw_id, str) and 0 < len(raw_id) <= INTERACTION_ID_MAX and '\x00' not in raw_id
    resolution = project_auto_resolution(interaction.get('autoResolution'))
    payload = interaction.get('payload')

    def placeholder(reason):
        detail = {'kind': kind, 'supported': False, 'unsupportedReason': reason}
        if id_preserved:
            detail['interactionId'] = raw_id
        if resolution:
            detail['autoResolution'] = resolution
        return detail

    if kind == 'workspaceHookReview':
        return placeholder('unsupported_kind'), id_preserved
    if not isinstance(payload, dict) or payload.get('kind') != kind:
        return placeholder('invalid_payload'), id_preserved
    if not id_preserved:
        return placeholder('invalid_id'), id_preserved
    try:
        detail = {'kind': kind, 'interactionId': raw_id}
        if resolution:
            detail['autoResolution'] = resolution
        if kind == 'permission':
            detail['toolName'] = display_text(payload.get('toolName'), 200)
            detail['summary'] = display_text(payload.get('summary'), 4000)
            detail['freeText'] = payload.get('freeText') is True
            options = payload.get('options')
            require(isinstance(options, list) and 0 < len(options) <= INTERACTION_OPTIONS_MAX, '权限选项无效')
            mapped = []
            for option in options:
                require(isinstance(option, dict) and option.get('kind') in
                    ('allowOnce', 'allowAlways', 'deny', 'custom'), '权限选项无效')
                mapped.append({'optionId': string(option.get('optionId'), INTERACTION_ID_MAX),
                    'label': display_text(option.get('label'), 512), 'kind': option['kind']})
            detail['options'] = mapped
        else:
            detail['prompt'] = display_text(payload.get('prompt'), 4000)
            detail['freeText'] = payload.get('freeText') is True
            detail['sensitive'] = payload.get('sensitive') is True
            detail['toolName'] = display_text(payload.get('toolName'), 200)
            questions = payload.get('questions')
            # Empty optional arrays are a valid free-text shape, not malformed structured
            # questions. Nonempty/invalid structures still go through strict validation.
            if questions is not None and not (questions == [] and detail['freeText']):
                detail['questions'] = project_questions(questions)
                index = payload.get('currentQuestionIndex')
                if type(index) is int and 0 <= index < len(detail['questions']):
                    detail['currentQuestionIndex'] = index
            elif payload.get('options') is not None:
                options = payload['options']
                require(isinstance(options, list) and len(options) <= INTERACTION_OPTIONS_MAX
                    and (len(options) > 0 or detail['freeText']), '交互选项无效')
                detail['options'] = [{'optionId': string(option.get('optionId'), INTERACTION_ID_MAX),
                    'label': display_text(option.get('label'), 512)}
                    for option in options if isinstance(option, dict)]
                require(len(detail['options']) == len(options), '交互选项无效')
            if payload.get('answerDrafts') is not None:
                detail['answerDrafts'] = project_answer_drafts(payload['answerDrafts'])
    except RequestError:
        return placeholder('oversized'), id_preserved
    if len(json.dumps(detail, ensure_ascii=False).encode()) > INTERACTION_DETAIL_BYTES:
        return placeholder('oversized'), id_preserved
    detail['supported'] = True
    return detail, id_preserved


def project_interactions(pending):
    """Return (details, complete): the first INTERACTION_DETAILS_MAX items plus the
    completeness flag. complete is true only when every pending interaction is
    represented with its original ID — a truncated window or an unpreservable ID
    keeps it false so absence in the details list never proves "not pending"."""
    require(isinstance(pending, list), '交互列表无效')
    details = []
    complete = len(pending) <= INTERACTION_DETAILS_MAX
    for interaction in pending[:INTERACTION_DETAILS_MAX]:
        try:
            detail, id_preserved = project_interaction(interaction)
        except RequestError:
            # Unrecognizable item: still occupies a slot as a placeholder without ID.
            detail = {'kind': 'unknown', 'supported': False, 'unsupportedReason': 'invalid_payload'}
            id_preserved = False
        complete = complete and id_preserved
        details.append(detail)
    return details, complete


def pending_interaction_ids(pending):
    # Exact original IDs, no length cap on the lookup side: presence comparison must
    # never miss an interaction whose ID is too long to be projected.
    return {item['interactionId'] for item in pending
        if isinstance(item, dict) and isinstance(item.get('interactionId'), str)}


def answer_value(value):
    if isinstance(value, str):
        return string(value, INTERACTION_ANSWER_TEXT_CHARS)
    require(isinstance(value, list) and 0 < len(value) <= INTERACTION_OPTIONS_MAX, '答案选项无效')
    return [string(item, INTERACTION_ANSWER_TEXT_CHARS) for item in value]


def interaction_content(content):
    require(isinstance(content, dict), '交互答案无效')
    result = {}
    for key, value in content.items():
        if key == 'answers':
            require(isinstance(value, dict), '答案表无效')
            mapped = {}
            for question, joined in value.items():
                require(isinstance(question, str) and 0 < len(question) <= INTERACTION_QUESTION_CHARS
                    and '\x00' not in question, '答案键无效')
                mapped[question] = string(joined, INTERACTION_JOINED_CHARS)
            require(mapped, '答案表不能为空')
            result['answers'] = mapped
        elif key == 'answer':
            result['answer'] = answer_value(value)
        elif re.fullmatch(r'answer_[0-9]{1,2}', key):
            require(int(key[7:]) < INTERACTION_QUESTIONS_MAX, '答案题号无效')
            result[key] = answer_value(value)
        else:
            raise RequestError('答案字段不受支持')
    require(result, '答案不能为空')
    return result


def interaction_answer(answer):
    require(isinstance(answer, dict), '交互应答无效')
    keys = set(answer)
    if keys == {'optionId'}:
        return {'optionId': string(answer['optionId'], INTERACTION_ID_MAX)}
    if keys == {'freeText'}:
        return {'freeText': string(answer['freeText'], INTERACTION_ANSWER_TEXT_CHARS)}
    if keys == {'action'}:
        require(answer['action'] in ('decline', 'cancel'), '该应答类型必须携带内容')
        return {'action': answer['action']}
    if keys == {'action', 'content'}:
        require(answer['action'] == 'accept', '该应答类型不应携带内容')
        validated = {'action': 'accept', 'content': interaction_content(answer['content'])}
        require(len(json.dumps(validated, ensure_ascii=False).encode()) <= INTERACTION_ANSWER_BYTES, '答案超出大小限制')
        return validated
    raise RequestError('交互应答无效')


class Assembler:
    def __init__(self):
        self.key = None
        self.fragments = {}
        self.total = 0

    def accept(self, wire):
        require(wire.get('wireVersion') == 3, 'ZCode 帧协议不兼容')
        if wire.get('kind') == 'complete':
            return wire['frame']
        require(wire.get('kind') == 'fragment', 'ZCode 帧格式不支持')
        count, index, size = wire.get('fragmentCount'), wire.get('fragmentIndex'), wire.get('logicalBytes')
        require(type(count) is int and 0 < count <= 1024 and type(index) is int and 0 <= index < count
                and type(size) is int and 0 < size <= MAX_FRAME, 'ZCode 分片超出限制')
        key = (wire['subscriptionId'], wire['logicalFrameId'], count, size, json.dumps(wire['checksum'], sort_keys=True))
        if key != self.key:
            require(not self.fragments, 'ZCode 分片不连续')
            self.key, self.total = key, 0
        data = base64.b64decode(wire['dataBase64'], validate=True)
        require(index not in self.fragments, 'ZCode 分片重复')
        self.fragments[index] = data
        self.total += len(data)
        require(self.total <= size, 'ZCode 分片超出长度')
        if len(self.fragments) != count:
            return None
        raw = b''.join(self.fragments[i] for i in range(count))
        require(len(raw) == size and wire['checksum'] == {'algorithm': 'crc32', 'value': f'{zlib.crc32(raw):08x}'}, 'ZCode 分片校验失败')
        self.fragments.clear()
        return json.loads(raw)


class AttachmentTransaction:
    """One in-flight phone→bridge→upstream upload. Phone chunks (96KiB) are accepted
    strictly in order and re-aggregated into official 384KiB chunks; a flush only counts
    after the upstream ack, so a failed upstream call leaves the buffer retryable. The
    whole-file digest is computed once per unique chunk and compared at commit."""

    def __init__(self, target, upload_id, file_name, mime, total_bytes, phone_chunks, checksum, now):
        self.target = target                    # trusted scope from begin, never re-supplied
        self.upload_id = upload_id
        self.file_name = file_name
        self.mime = mime
        self.total_bytes = total_bytes
        self.phone_chunks = phone_chunks
        self.checksum = checksum
        self.created_at = now
        self.last_used = now
        self.begun = False                      # official begin acked
        self.digests = {}                       # phone index -> sha256 hex
        self.next_index = 0                     # next phone chunk expected
        self.received = 0                       # unique accepted bytes
        self.buffer = bytearray()               # not-yet-flushed raw bytes (≤384KiB + tail)
        self.upstream_flushed = 0               # upstream-acked byte count (chunk-aligned)
        self.pending_upstream = False           # a push failed; buffer holds unconfirmed data
        self.hasher = hashlib.sha256()
        self.committing = False                 # upstream commit sent, outcome unknown

    def same_metadata(self, target, file_name, mime, total_bytes, phone_chunks, checksum):
        return (self.target == target and self.file_name == file_name and self.mime == mime
            and self.total_bytes == total_bytes and self.phone_chunks == phone_chunks
            and self.checksum == checksum)


class Bridge:
    def __init__(self, client):
        self.client = client
        self.client_id = None
        self.target = None
        self.subscription = None
        self.listener = None
        self.indexes = SessionIndexes(self, Assembler)
        self.client.event_handler = self.indexes.event
        self.attachments = {}                   # uploadId -> AttachmentTransaction (open)
        self.committed = {}                     # ref -> metadata of committed uploads

    def call(self, channel, method, *args):
        return self.client.call(channel, method, list(args))

    def agent(self, method, params):
        return self.call('zcode-agent', method, params)

    def catalog(self):
        view = self.call('model-selection', 'getView')
        settings_view = self.call('provider-settings', 'getView')
        settings = self.call('setting', 'get')
        records = {p['providerId']: p for p in settings_view['providers']}
        providers = []
        for provider in view['providers']:
            record = records.get(provider['providerId'], {})
            if record.get('executable') is not True:
                continue
            models = []
            for model in provider['models']:
                config = model.get('config', {})
                levels = config.get('optionSpecs', {}).get('reasoningLevel', {}).get('values', [])
                models.append({'id': model['modelId'], 'levels': [x for x in levels if isinstance(x, str)]})
            access = provider.get('config', {}).get('access', {})
            name = provider['providerName']
            if access.get('mode') == 'team-coding-plan':
                team = settings.get('providerFamilyConnectionSelections', {}).get(access.get('accountType'), {})
                name += ' · 项目 ' + str(team.get('projectId', '未选择'))[-12:]
            providers.append({'id': provider['providerId'], 'name': name,
                'planKind': access.get('mode', 'api'), 'models': models,
                'guard': guard_for(record, settings)})
        return {'providers': providers, 'preferred': view.get('preferredSelection'),
            'planScope': '开发机已连接的计划；团队/账号切换暂需在 ZCode 完成，刷新后生效。'}

    def check_selection(self, params):
        chosen = selection(params.get('selection'))
        catalog = self.catalog()
        provider = next((p for p in catalog['providers'] if p['id'] == chosen['providerId']), None)
        require(provider and params.get('guard') == provider['guard'], '计划或账号配置已变化，请刷新并重新选择后发送')
        model = next((m for m in provider['models'] if m['id'] == chosen['modelId']), None)
        require(model is not None, '模型不再可用，请刷新')
        level = chosen.get('options', {}).get('reasoningLevel')
        require((level in model['levels']) if model['levels'] else level is None, '请选择该模型支持的推理强度')
        resolved = self.call('model-selection', 'getView', {'selection': chosen})
        require(not resolved.get('selectionIssue') and resolved.get('effectiveSelection') == chosen,
            'ZCode 实际选择与指定计划/模型不一致，任务未发送')
        return chosen

    def workspace(self, params):
        path = string(params.get('workspacePath'), 4096)
        require(Path(path).is_absolute() and Path(path).is_dir(), '项目目录不存在')
        result = {'workspacePath': path}
        if params.get('workspaceIdentity'):
            result['workspaceIdentity'] = string(params['workspaceIdentity'])
        return result

    def session_target(self, params):
        return dict(self.workspace(params), sessionId=string(params.get('sessionId')))

    def read_directory(self, params):
        # Whitelisted read-only listing for the phone's project picker. The server
        # (project.readDirectory) only lexically normalizes the path — it does not
        # resolve symlinks — so the client still checks that returned paths are absolute.
        include_hidden = params.get('includeHidden')
        require(include_hidden is None or isinstance(include_hidden, bool), '参数无效')
        request = {}
        if params.get('path') is not None:
            path = string(params['path'], 4096)
            require(Path(path).is_absolute(), '项目路径需要使用绝对路径')
            request['path'] = path
        if include_hidden is not None:
            request['includeHidden'] = include_hidden
        try:
            data = self.call('project', 'readDirectory', request)
        except WireError:
            # Server rejections carry no usable detail; a bad path is the realistic cause.
            raise RequestError('无法读取该目录，可能不存在、没有权限或服务暂不可用')
        current = string(data.get('path'), 4096)
        require(Path(current).is_absolute(), '服务返回了非绝对路径')
        directories = []
        for entry in data.get('entries') or []:
            if not isinstance(entry, dict) or entry.get('type') != 'directory':
                continue
            try:
                name = string(entry.get('name'), 1024)
                path = string(entry.get('path'), 4096)
                require(Path(path).is_absolute(), '服务返回了非绝对路径')
            except RequestError:
                continue
            directories.append({'name': name, 'path': path})
        parent = str(Path(current).parent)
        return {'path': current, 'parent': parent, 'hasParent': parent != current, 'directories': directories}

    def projects(self):
        tree = self.call('project', 'readTree')
        result = {'projects': [{'id': p['id'], 'name': p['name'], 'roots': [r['rootPath'] for r in p['roots']]}
            for p in tree['projects'] if not p.get('deleted')],
            'sessions': [pick(t, ['taskId', 'title', 'workspacePath', 'workspaceIdentity', 'projectId', 'updatedAt', 'status'])
            for t in tree['tasks'] if not t.get('archived')]}
        self.indexes.set_sessions(result['sessions'])
        return result

    def snapshot(self, params):
        target = self.session_target(params)
        # Subscribe briefly for an authoritative snapshot, then detach. The server
        # owns execution. Polling while foreground avoids implementing a partial delta reducer.
        listener = self.client.listen('zcode-agent', 'onDynamicConversationFrame', target)
        subscription = None
        try:
            ack = self.agent('subscribeConversationV4', dict(target, visibility='foreground'))['ack']
            subscription = ack['subscriptionId']
            assembler = Assembler()
            self.client.deadline = time.monotonic() + 30
            while True:
                if not self.client.events:
                    h, body = self.client.receive()
                    if h[0] == 204:
                        self.client.event(h, body)
                    continue
                identity, wire = self.client.events.pop(0)
                if identity != listener or wire.get('subscriptionId') != subscription:
                    continue
                frame = assembler.accept(wire)
                if frame and frame['payload']['kind'] == 'snapshot':
                    snap = frame['payload']['snapshot']
                    require(snap['sessionId'] == target['sessionId'], '会话快照不匹配')
                    return snap
        finally:
            if subscription:
                self.agent('unsubscribeConversationV4', dict(target, subscriptionId=subscription))
            self.client.unlisten(listener)

    def command(self, kind, params):
        command_id = string(params.get('commandId'), 100)
        require(re.fullmatch(r'[a-zA-Z0-9_-]{16,100}', command_id), '命令 ID 无效')
        target = self.workspace(params) if kind == 'createSession' else self.session_target(params)
        session_id = target.pop('sessionId', None)
        payload = {}
        base_revision = None
        if kind in ('createSession', 'sendText'):
            chosen = self.check_selection(params)
            if kind == 'createSession':
                project_id = string(params.get('projectId'))
                project = next((p for p in self.projects()['projects'] if p['id'] == project_id), None)
                require(project and project['roots'] == [target['workspacePath']], '请选择该开发机上的单目录项目')
                target['projectId'] = project_id
                payload = {'workspaceId': target['workspacePath'], 'config': {'modelSelection': chosen,
                    'mode': 'yolo', 'planEnabled': False, 'followupMode': 'queue'}}
            else:
                require(params.get('delivery') in ('queue', 'guide'), '仅支持排队或插入')
                attachments = self.attachment_payload(params)
                text = params.get('text', '')
                require(isinstance(text, str) and len(text) <= 64000 and (text.strip() or attachments),
                    '请输入文字或添加附件')
                payload = {'text': text, 'modelSelection': chosen,
                    'mode': 'yolo', 'planEnabled': False, 'requestedDelivery': params['delivery'], 'inputOrigin': 'mobile'}
                if attachments:
                    payload['attachments'] = attachments
        elif kind == 'stop':
            payload = {'expectedForegroundExecutionId': string(params.get('executionId'))}
        elif kind == 'resolveInteraction':
            payload = {'interactionId': string(params.get('interactionId'), INTERACTION_ID_MAX),
                'answer': interaction_answer(params.get('answer'))}
        elif kind == 'snoozeInteractionAutoResolution':
            payload = {'interactionId': string(params.get('interactionId'), INTERACTION_ID_MAX)}
        elif kind in ('deleteQueueItem', 'sendQueuedNow'):
            payload = {'queueItemId': string(params.get('queueItemId'), 256)}
        elif kind == 'editQueueItem':
            payload = {'queueItemId': string(params.get('queueItemId'), 256),
                'newText': string(params.get('text'), 64000)}
        # CAS commands carry the revision the user acted on. The bridge never refreshes
        # it on its own: a server-side stale must surface instead of being bypassed.
        if kind in ('deleteQueueItem', 'editQueueItem', 'sendQueuedNow'):
            revision = params.get('baseRevision')
            require(type(revision) is int and revision >= 0, '缺少会话版本，请刷新后重试')
            base_revision = revision
        envelope = {'commandId': command_id, 'clientId': self.client_id, 'sessionId': session_id,
            'type': kind, 'payload': payload, 'issuedAt': int(time.time() * 1000)}
        if base_revision is not None:
            envelope['baseRevision'] = base_revision
        ack = self.agent('sendConversationCommandV4', dict(target, envelope=envelope))
        # Project only stable status/code, not raw upstream diagnostic messages.
        return pick(ack, ['commandId', 'status', 'reasonCode', 'revisionAtDecision', 'result'])

    # ---- Image attachments (task 2026-11-03-image-attachments) ----

    def attachment_expire(self):
        now = time.time()
        for txn in [t for t in self.attachments.values() if now - t.last_used > ATTACHMENT_TTL_SECONDS]:
            self.attachment_upstream_abort(txn)
            self.attachments.pop(txn.upload_id, None)
        for ref, meta in list(self.committed.items()):
            if now - meta['lastUsed'] > ATTACHMENT_TTL_SECONDS:
                self.committed.pop(ref, None)

    def attachment_upstream_abort(self, txn):
        # Best effort; a dead transaction is discarded locally regardless.
        try:
            self.agent('attachmentAbortV4', dict(self.attachment_upstream_target(txn), uploadId=txn.upload_id))
        except Exception:
            pass

    def attachment_upstream_target(self, txn):
        # Official attachment RPCs take the plain workspace/session pair; the workspace
        # identity stays in the trusted local scope only.
        return {'workspacePath': txn.target['workspacePath'], 'sessionId': txn.target['sessionId']}

    def attachment_upstream_begin(self, txn):
        result = self.agent('attachmentBeginV4', dict(self.attachment_upstream_target(txn),
            uploadId=txn.upload_id, fileName=txn.file_name, mime=txn.mime, totalBytes=txn.total_bytes,
            totalChunks=-(-txn.total_bytes // ATTACHMENT_UPSTREAM_CHUNK), checksum=txn.checksum))
        txn.begun = True
        return result

    def attachment_push_upstream(self, txn, size):
        index = txn.upstream_flushed // ATTACHMENT_UPSTREAM_CHUNK
        self.agent('attachmentChunkV4', dict(self.attachment_upstream_target(txn), uploadId=txn.upload_id,
            chunkIndex=index, dataBase64=base64.b64encode(bytes(txn.buffer[:size])).decode('ascii')))
        del txn.buffer[:size]
        txn.upstream_flushed += size

    def attachment_resync(self, txn):
        """After any lost/failed upstream exchange, re-run the idempotent official begin
        and adopt its progress: bytes the server already holds are dropped from the local
        buffer; anything beyond is re-flushed. Never guesses."""
        result = self.attachment_upstream_begin(txn)
        if result.get('state') == 'committed':
            return result
        official_next = result.get('nextChunkIndex')
        if type(official_next) is not int or official_next < 0:
            raise RequestError('无法核对上传进度，请重试')
        expected = min(official_next * ATTACHMENT_UPSTREAM_CHUNK, txn.total_bytes)
        require(expected <= txn.received, '上游进度与已传数据不符，请重新上传')
        if expected > txn.upstream_flushed:
            del txn.buffer[:expected - txn.upstream_flushed]
            txn.upstream_flushed = expected
        elif expected < txn.upstream_flushed:
            # The server acked less than we thought: rebuild from its truth.
            raise RequestError('上游进度落后于本地记录，请重新上传')
        return None

    def attachment_flush(self, txn, final=False):
        """Push complete 384KiB blocks (plus the tail when final). Progress is only
        advanced after the official ack; a failed push marks the tail unconfirmed and
        leaves it retryable."""
        try:
            while len(txn.buffer) >= ATTACHMENT_UPSTREAM_CHUNK:
                self.attachment_push_upstream(txn, ATTACHMENT_UPSTREAM_CHUNK)
            if final and txn.buffer:
                self.attachment_push_upstream(txn, len(txn.buffer))
        except WireError:
            txn.pending_upstream = True
            raise RequestError('上游暂不可用，请重传上一块')
        txn.pending_upstream = False

    def attachment_trim_committed(self):
        # Bounded ref map; server-side artifacts outlive local entries by design.
        while len(self.committed) > ATTACHMENT_COMMITTED_MAX:
            self.committed.pop(min(self.committed.items(), key=lambda kv: kv[1]['lastUsed'])[0])

    def attachment_adopt_committed(self, txn, ref):
        self.attachments.pop(txn.upload_id, None)
        self.committed[ref] = {'target': txn.target, 'uploadId': txn.upload_id, 'fileName': txn.file_name,
            'mime': txn.mime, 'bytes': txn.total_bytes, 'phoneChunks': txn.phone_chunks,
            'checksum': txn.checksum, 'lastUsed': time.time()}
        self.attachment_trim_committed()
        return {'uploadId': txn.upload_id, 'state': 'committed', 'ref': ref,
            'fileName': txn.file_name, 'mime': txn.mime, 'bytes': txn.total_bytes,
            'nextChunkIndex': txn.phone_chunks}

    def attachment_resolve_committing(self, txn):
        """A commit whose response was lost: re-run the idempotent official begin to learn
        whether the upload already committed — never guess."""
        txn.last_used = time.time()
        result = self.attachment_upstream_begin(txn)
        if result.get('state') == 'committed' and isinstance(result.get('ref'), str) and result['ref']:
            return self.attachment_adopt_committed(txn, result['ref'])
        txn.committing = False
        return {'uploadId': txn.upload_id, 'state': 'staging', 'nextChunkIndex': txn.next_index}

    def attachment_begin(self, params):
        self.attachment_expire()
        upload_id = string(params.get('uploadId'), 128)
        require(re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._:-]*', upload_id), '上传 ID 无效')
        target = self.session_target(params)
        file_name = string(params.get('fileName'), 255)
        require('\r' not in file_name and '\n' not in file_name, '文件名无效')
        mime = string(params.get('mime'), 100)
        require(mime in ATTACHMENT_MIME_ALLOWLIST, '仅支持图片附件')
        total_bytes = params.get('totalBytes')
        require(type(total_bytes) is int and 0 < total_bytes <= ATTACHMENT_MAX_BYTES, '图片超出大小限制')
        phone_chunks = params.get('totalChunks')
        require(type(phone_chunks) is int and phone_chunks == -(-total_bytes // ATTACHMENT_PHONE_CHUNK),
            '分块数量与大小不符')
        checksum = string(params.get('checksum'), 128)
        require(re.fullmatch(r'sha256:[0-9a-f]{64}', checksum), '校验值无效')
        for ref, meta in self.committed.items():
            if meta['uploadId'] != upload_id or meta['target'] != target:
                continue
            # A committed retry must match EVERY begin field: a same-id upload with changed
            # metadata must be refused, never silently answered with the old artifact.
            require(meta['fileName'] == file_name and meta['mime'] == mime
                and meta['bytes'] == total_bytes and meta['phoneChunks'] == phone_chunks
                and meta['checksum'] == checksum,
                '同名上传的参数与之前不一致，请重新开始上传')
            meta['lastUsed'] = time.time()
            return {'uploadId': upload_id, 'state': 'committed', 'ref': ref,
                'fileName': meta['fileName'], 'mime': meta['mime'], 'bytes': meta['bytes'],
                'nextChunkIndex': meta['phoneChunks']}
        txn = self.attachments.get(upload_id)
        if txn is not None:
            require(txn.same_metadata(target, file_name, mime, total_bytes, phone_chunks, checksum),
                '同名上传的参数与之前不一致，请重新开始上传')
            txn.last_used = time.time()
            if txn.committing:
                return self.attachment_resolve_committing(txn)
            if not txn.begun:
                result = self.attachment_upstream_begin(txn)
                if result.get('state') == 'committed':
                    return self.attachment_adopt_committed(txn, result['ref'])
            return {'uploadId': upload_id, 'state': 'staging', 'nextChunkIndex': txn.next_index}
        require(len(self.attachments) < ATTACHMENT_TRANSACTIONS_MAX, '并发上传过多，请稍后重试')
        txn = AttachmentTransaction(target, upload_id, file_name, mime, total_bytes, phone_chunks, checksum, time.time())
        result = self.attachment_upstream_begin(txn)
        self.attachments[upload_id] = txn
        if result.get('state') == 'committed':
            return self.attachment_adopt_committed(txn, result['ref'])
        return {'uploadId': upload_id, 'state': 'staging', 'nextChunkIndex': 0}

    def attachment_chunk(self, params):
        self.attachment_expire()
        txn = self.attachments.get(string(params.get('uploadId'), 128))
        require(txn is not None, '上传事务不存在或已过期，请重新上传')
        txn.last_used = time.time()
        if txn.committing:
            raise RequestError('上传正在确认，请稍后')
        index = params.get('chunkIndex')
        require(type(index) is int and 0 <= index < txn.phone_chunks, '块序号无效')
        try:
            raw = base64.b64decode(string(params.get('dataBase64'), 180 * 1024), validate=True)
        except Exception:
            raise RequestError('块数据无效')
        expected_size = ATTACHMENT_PHONE_CHUNK if index < txn.phone_chunks - 1 \
            else txn.total_bytes - (txn.phone_chunks - 1) * ATTACHMENT_PHONE_CHUNK
        require(len(raw) == expected_size, '块大小不符')
        digest = hashlib.sha256(raw).hexdigest()
        if index < txn.next_index:
            # Duplicate of an accepted chunk: must match exactly. With unconfirmed data
            # pending, first re-align with the server's own progress, then retry flushes.
            require(txn.digests.get(index) == digest, '重复块内容与已接受的不一致')
            if txn.pending_upstream:
                committed = self.attachment_resync(txn)
                if committed:
                    return committed
            self.attachment_flush(txn)
            return {'uploadId': txn.upload_id, 'nextChunkIndex': txn.next_index}
        require(index == txn.next_index, '块序号越序，请按顺序重传')
        # Backpressure: never stack a new phone chunk behind unconfirmed upstream data —
        # the client retransmits the previous chunk (duplicate path) to drive the retry.
        require(not txn.pending_upstream, '上一块尚未确认，请重传上一块')
        txn.digests[index] = digest
        txn.next_index = index + 1
        txn.received += len(raw)
        txn.hasher.update(raw)
        txn.buffer.extend(raw)
        self.attachment_flush(txn)
        return {'uploadId': txn.upload_id, 'nextChunkIndex': txn.next_index}

    def attachment_commit(self, params):
        self.attachment_expire()
        upload_id = string(params.get('uploadId'), 128)
        for ref, meta in self.committed.items():
            if meta['uploadId'] == upload_id:
                meta['lastUsed'] = time.time()
                return {'uploadId': upload_id, 'state': 'committed', 'ref': ref,
                    'fileName': meta['fileName'], 'mime': meta['mime'], 'bytes': meta['bytes']}
        txn = self.attachments.get(upload_id)
        require(txn is not None, '上传事务不存在或已过期，请重新上传')
        txn.last_used = time.time()
        if txn.committing:
            return self.attachment_resolve_committing(txn)
        require(txn.next_index == txn.phone_chunks and txn.received == txn.total_bytes, '数据尚未传完，不能提交')
        if txn.pending_upstream:
            # A failed push at commit time: adopt the server's own progress (it may
            # already hold everything, or even have committed) before flushing the rest.
            committed = self.attachment_resync(txn)
            if committed:
                return committed
        if 'sha256:' + txn.hasher.hexdigest() != txn.checksum:
            # Checksum mismatch: the upload is cancelled outright, never committed.
            self.attachments.pop(upload_id, None)
            self.attachment_upstream_abort(txn)
            raise RequestError('图片校验不符，已取消上传')
        self.attachment_flush(txn, final=True)
        require(txn.upstream_flushed == txn.total_bytes, '上游数据不完整，请重试提交')
        txn.committing = True
        try:
            result = self.agent('attachmentCommitV4', dict(self.attachment_upstream_target(txn), uploadId=upload_id))
        except WireError:
            raise RequestError('提交结果未知，请重试提交以确认')
        txn.committing = False
        ref = result.get('ref')
        require(isinstance(ref, str) and 0 < len(ref) <= 1024, '上游未返回有效附件引用')
        return self.attachment_adopt_committed(txn, ref)

    def attachment_abort(self, params):
        self.attachment_expire()
        upload_id = string(params.get('uploadId'), 128)
        txn = self.attachments.pop(upload_id, None)
        if txn is not None:
            self.attachment_upstream_abort(txn)
            return {'uploadId': upload_id, 'state': 'aborted'}
        if any(meta['uploadId'] == upload_id for meta in self.committed.values()):
            # Already committed upstream and possibly referenced by the session.
            return {'uploadId': upload_id, 'state': 'committed'}
        return {'uploadId': upload_id, 'state': 'aborted'}

    def attachment_payload(self, params):
        """Validate sendText attachments against committed uploads of the SAME target.
        Forged refs, cross-session refs, or metadata mismatches are rejected — a ref is
        only an opaque token for something this connection actually uploaded."""
        source = params.get('attachments')
        if source is None:
            return None
        require(isinstance(source, list) and 0 < len(source) <= ATTACHMENT_MESSAGE_MAX, '附件数量无效')
        target = self.session_target(params)
        result = []
        for entry in source:
            require(isinstance(entry, dict) and set(entry) == {'ref', 'fileName', 'mime', 'bytes'}, '附件信息无效')
            meta = self.committed.get(entry.get('ref'))
            require(meta is not None, '附件尚未上传完成，请先上传')
            require(meta['target'] == target and meta['fileName'] == entry['fileName']
                and meta['mime'] == entry['mime'] and meta['bytes'] == entry['bytes'],
                '附件信息与上传时不一致，请重新上传')
            meta['lastUsed'] = time.time()
            result.append(pick(entry, ['ref', 'fileName', 'mime', 'bytes']))
        return result

    def dispatch(self, method, params):
        require(isinstance(params, dict), '请求参数无效')
        if method == 'initialize':
            require(self.client_id is None, '连接已经初始化')
            client_id = string(params.get('clientId'), 100)
            require(re.fullmatch(r'pockethelm-[a-zA-Z0-9-]{16,80}', client_id), '客户端 ID 无效')
            hello = self.call('zcode-agent', 'helloConversationV4')
            require(hello['protocolVersion'] == 3 and hello['clientMode'] == 'web-remote-replayable', 'ZCode 协议版本不支持，请更新桥接器')
            self.call('zcode-agent', 'initializeConversationV4', {'kind': 'clientHello', 'protocolVersion': 3,
                'clientId': client_id, 'clientKind': 'mobileApp', 'appVersion': 'pockethelm-zcode-experimental'})
            self.client_id = client_id
            # bridgeVersion stays 1: released 0.4.12 clients require exactly this value.
            # New capabilities are negotiated additively; old clients ignore them.
            return {'engine': 'zcode', 'bridgeVersion': 1, 'permission': 'Full access', 'protocolVersion': 3,
                'sessionsIndex': True, 'capabilities': {'interactions': True, 'queueOps': True,
                    'turnSummary': True, 'fileChanges': True, 'indexMeta': True, 'attachments': True}}
        require(self.client_id is not None, '请先初始化连接')
        if method == 'sessions/status':
            return self.indexes.read()
        if method == 'sessions/pause':
            return self.indexes.pause()
        if method == 'catalog':
            return self.catalog()
        if method == 'projects':
            return self.projects()
        if method == 'project/add':
            target = self.workspace(params)
            existing = next((p for p in self.projects()['projects'] if p['roots'] == [target['workspacePath']]), None)
            if existing:
                return existing
            p = self.call('project', 'saveProject', {'roots': [target['workspacePath']]})
            return pick(p, ['id', 'name'])
        if method == 'directory/read':
            return self.read_directory(params)
        if method == 'session/read':
            check = params.get('checkInteractionIds')
            if check is not None:
                # Validate before subscribing: malformed checks must not touch upstream.
                require(isinstance(check, list) and len(check) <= INTERACTION_CHECK_IDS_MAX
                    and all(isinstance(item, str) and 0 < len(item) <= INTERACTION_ID_MAX for item in check)
                    and len(set(check)) == len(check),
                    '交互核对参数无效')
            snap = self.snapshot(params)
            config = snap['config']
            control = snap['control']
            interactions, complete = project_interactions(snap['pendingInteractions'])
            result = {'id': snap['sessionId'], 'title': snap['meta']['title'], 'revision': snap['revision'], 'logEpoch': snap['logEpoch'],
                'selection': config.get('modelSelection'), 'mode': config['mode'], 'phase': control['phase'],
                'canStop': control['canStop'], 'executionId': next((w['foregroundExecutionId'] for w in control['activeWorks'] if w.get('foregroundExecutionId')), ''),
                'items': project_rows(snap['rows']['window']), 'totalRows': snap['rows']['totalCount'],
                'firstRowId': snap['rows'].get('firstRowId'), 'hasMore': snap['rows']['totalCount'] > len(snap['rows']['window']),
                'queue': [pick(q, ['queueItemId', 'text', 'sourceCommandId']) for q in snap['queue']['items']],
                'interactionCount': len(snap['pendingInteractions']),
                'interactions': interactions, 'interactionsComplete': complete,
                'lastError': str((control.get('lastError') or {}).get('message', ''))[:2000]}
            turn = project_turn(snap['rows']['window'])
            if turn:
                result['turn'] = turn
            # Presence is computed from this same raw snapshot, before the detail window
            # is applied: an ID outside the first four details still reports pending=true.
            if check is not None:
                pending_ids = pending_interaction_ids(snap['pendingInteractions'])
                result['interactionPresence'] = [{'interactionId': item, 'pending': item in pending_ids}
                    for item in check]
            return result
        if method == 'session/history':
            target = self.session_target(params)
            before = params.get('beforeRowId')
            require(type(before) in (int, float) and math.isfinite(before), '历史游标无效')
            data = self.agent('conversationRowsRangeV4', dict(target, beforeRowId=before, limit=100))
            return {'items': project_rows(data['rows']), 'hasMore': data['hasMore'],
                'beforeRowId': data['rows'][0]['rowId'] if data['rows'] else None,
                'revision': data['atRevision'], 'logEpoch': data['atLogEpoch']}
        if method in ('session/create', 'session/send', 'session/stop'):
            return self.command({'session/create': 'createSession', 'session/send': 'sendText', 'session/stop': 'stop'}[method], params)
        if method in ('session/queue/remove', 'session/queue/edit', 'session/queue/promote'):
            return self.command({'session/queue/remove': 'deleteQueueItem', 'session/queue/edit': 'editQueueItem',
                'session/queue/promote': 'sendQueuedNow'}[method], params)
        if method == 'session/diff':
            target = self.session_target(params)
            row_id = params.get('rowId')
            require(type(row_id) is int and row_id >= 0, '轮次行号无效')
            revision = params.get('baseRevision')
            require(type(revision) is int and revision >= 0, '缺少会话版本，请刷新后重试')
            entity = params.get('entityId')
            require(isinstance(entity, str) and 0 < len(entity) <= 1024 and '\x00' not in entity,
                '该轮次缺少行标识，无法读取更改明细，请刷新会话')
            request = dict(target, sessionId=params['sessionId'], target={'rowId': row_id,
                'entityId': entity}, baseRevision=revision,
                baseLogEpoch=string(params.get('baseLogEpoch'), 256))
            try:
                data = self.agent('conversationFileChangesV4', request)
            except WireError:
                raise RequestError('更改明细读取失败，可能因会话已更新，请刷新后重试')
            return project_file_changes(data)
        if method == 'session/interact':
            return self.command('resolveInteraction', params)
        if method == 'session/interact/snooze':
            return self.command('snoozeInteractionAutoResolution', params)
        if method == 'session/attachment/begin':
            return self.attachment_begin(params)
        if method == 'session/attachment/chunk':
            return self.attachment_chunk(params)
        if method == 'session/attachment/commit':
            return self.attachment_commit(params)
        if method == 'session/attachment/abort':
            return self.attachment_abort(params)
        if method == 'command/query':
            target = self.workspace(params)
            data = self.agent('queryConversationCommandsV4', dict(target, commands=[{
                'sessionId': params.get('sessionId'), 'commandId': string(params.get('commandId'), 100)}]))
            result = data['results'][0]['result']
            return {'ack': pick(result, ['commandId', 'status', 'reasonCode', 'result']) if isinstance(result, dict) else result}
        if method == 'quota':
            chosen = self.check_selection(params)
            view = self.call('model-selection', 'getView')
            provider = next(p for p in view['providers'] if p['providerId'] == chosen['providerId'])
            access = provider.get('config', {}).get('access', {})
            kind = access.get('mode', '')
            require(kind == 'start-plan' or 'coding-plan' in chosen['providerId'], '该来源暂未提供可读取的额度')
            request = {'preferredProviderId': chosen['providerId'], 'range': 'today', 'timeZone': 'Asia/Shanghai'}
            if access.get('type') == 'zhipu-account':
                account = {'type': 'zhipu-account', 'family': access['accountType'], 'planKind': access['mode']}
                if access['mode'] == 'team-coding-plan':
                    settings = self.call('setting', 'get')
                    team = settings.get('providerFamilyConnectionSelections', {}).get(access['accountType'], {})
                    require(team.get('kind') == 'team-coding-plan', '团队计划已变化，请刷新')
                    account.update({k: string(team.get(k)) for k in ('productId', 'organizationId', 'projectId')})
                request['accountAccess'] = account
            if kind == 'start-plan':
                require(access.get('type') == 'zhipu-account', '该来源暂未提供可读取的额度')
                request.pop('range')
                request.pop('timeZone')
                request.update(includeSubscription=True, requirePreferredProvider=True,
                    allowDisabledPreferredProvider=True, allowEnvApiKey=False)
                data = self.call('usage-stats', 'getEntitlementSnapshot', request)
            else:
                data = self.call('usage-stats', 'getCodingPlanUsageSnapshot', request)
            self.check_selection(params)  # Do not label another account's quota with an old selection.
            return project_quota(data, kind)
        raise RequestError('不支持的操作')


def main():
    client = None
    bridge = None
    try:
        client = Client()
        bridge = Bridge(client)
        while True:
            line = sys.stdin.buffer.readline(MAX_LINE + 1)
            if not line:
                return
            if len(line) > MAX_LINE:
                return
            request_id = None
            try:
                request = json.loads(line)
                require(isinstance(request, dict), '请求格式无效')
                request_id = request.get('id')
                require(isinstance(request_id, (str, int)) and len(str(request_id)) < 100, '请求 ID 无效')
                result = bridge.dispatch(request.get('method'), request.get('params', {}))
                response = {'id': request_id, 'result': result}
            except RequestError as error:
                response = {'id': request_id, 'error': {'code': -32602, 'message': str(error)}}
            except WireError:
                response = {'id': request_id, 'error': {'code': -32001, 'message': 'ZCode 拒绝请求或协议不兼容，请检查开发机服务'}}
            except (EOFError, TimeoutError):
                # A write may have reached Core. Close; the phone must query its
                # original command ID after reconnect, never automatically retry.
                return
            except Exception:
                response = {'id': request_id, 'error': {'code': -32000, 'message': '桥接请求失败，请检查开发机 ZCode 状态'}}
            raw = json.dumps(response, ensure_ascii=False, separators=(',', ':'))
            if len(raw.encode()) > 8 * 1024 * 1024:
                raw = json.dumps({'id': request_id, 'error': {'code': -32002, 'message': '会话快照过大，请缩小历史范围'}})
            print(raw, flush=True)
    except Exception:
        print('PocketHelm: ZCode bridge unavailable; check local ZCode Server.', file=sys.stderr)
    finally:
        # SSH EOF / normal termination releases native subscriptions explicitly.
        if bridge:
            try:
                bridge.indexes.pause()
            except Exception:
                pass
        if client:
            client.close()


if __name__ == '__main__':
    for signum in (signal.SIGTERM, signal.SIGHUP):
        signal.signal(signum, lambda *_: sys.exit(0))
    main()
