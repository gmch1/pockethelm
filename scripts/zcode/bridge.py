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


def project_rows(rows):
    result = []
    for row in rows:
        kind = row.get('kind')
        if kind == 'turnHeader':
            continue
        item = {'id': str(row['rowId']), 'kind': kind, 'status': row.get('state', row.get('status', ''))}
        if kind in ('userInput', 'assistantText', 'reasoning'):
            item['text'] = row.get('text', '')[:120000]
            item['commandId'] = row.get('sourceCommandId', '')
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


class Bridge:
    def __init__(self, client):
        self.client = client
        self.client_id = None
        self.target = None
        self.subscription = None
        self.listener = None
        self.indexes = SessionIndexes(self, Assembler)
        self.client.event_handler = self.indexes.event

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
                payload = {'text': string(params.get('text'), 64000), 'modelSelection': chosen,
                    'mode': 'yolo', 'planEnabled': False, 'requestedDelivery': params['delivery'], 'inputOrigin': 'mobile'}
        elif kind == 'stop':
            payload = {'expectedForegroundExecutionId': string(params.get('executionId'))}
        envelope = {'commandId': command_id, 'clientId': self.client_id, 'sessionId': session_id,
            'type': kind, 'payload': payload, 'issuedAt': int(time.time() * 1000)}
        ack = self.agent('sendConversationCommandV4', dict(target, envelope=envelope))
        # Project only stable status/code, not raw upstream diagnostic messages.
        return pick(ack, ['commandId', 'status', 'reasonCode', 'revisionAtDecision', 'result'])

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
            return {'engine': 'zcode', 'bridgeVersion': 1, 'permission': 'Full access', 'protocolVersion': 3, 'sessionsIndex': True}
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
            snap = self.snapshot(params)
            config = snap['config']
            control = snap['control']
            return {'id': snap['sessionId'], 'title': snap['meta']['title'], 'revision': snap['revision'], 'logEpoch': snap['logEpoch'],
                'selection': config.get('modelSelection'), 'mode': config['mode'], 'phase': control['phase'],
                'canStop': control['canStop'], 'executionId': next((w['foregroundExecutionId'] for w in control['activeWorks'] if w.get('foregroundExecutionId')), ''),
                'items': project_rows(snap['rows']['window']), 'totalRows': snap['rows']['totalCount'],
                'firstRowId': snap['rows'].get('firstRowId'), 'hasMore': snap['rows']['totalCount'] > len(snap['rows']['window']),
                'queue': [pick(q, ['queueItemId', 'text', 'sourceCommandId']) for q in snap['queue']['items']],
                'interactionCount': len(snap['pendingInteractions']),
                'lastError': str((control.get('lastError') or {}).get('message', ''))[:2000]}
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
        if method == 'command/query':
            target = self.workspace(params)
            data = self.agent('queryConversationCommandsV4', dict(target, commands=[{
                'sessionId': params.get('sessionId'), 'commandId': string(params.get('commandId'), 100)}]))
            result = data['results'][0]['result']
            return {'ack': pick(result, ['commandId', 'status', 'reasonCode', 'result']) if isinstance(result, dict) else result}
        if method == 'quota':
            chosen = self.check_selection(params)
            require('coding-plan' in chosen['providerId'], '该来源暂未提供可读取的额度')
            view = self.call('model-selection', 'getView')
            provider = next(p for p in view['providers'] if p['providerId'] == chosen['providerId'])
            access = provider.get('config', {}).get('access', {})
            request = {'preferredProviderId': chosen['providerId'], 'range': 'today', 'timeZone': 'Asia/Shanghai'}
            if access.get('type') == 'zhipu-account':
                account = {'type': 'zhipu-account', 'family': access['accountType'], 'planKind': access['mode']}
                if access['mode'] == 'team-coding-plan':
                    settings = self.call('setting', 'get')
                    team = settings.get('providerFamilyConnectionSelections', {}).get(access['accountType'], {})
                    require(team.get('kind') == 'team-coding-plan', '团队计划已变化，请刷新')
                    account.update({k: string(team.get(k)) for k in ('productId', 'organizationId', 'projectId')})
                request['accountAccess'] = account
            data = self.call('usage-stats', 'getCodingPlanUsageSnapshot', request)
            self.check_selection(params)  # Do not label another account's quota with an old selection.
            quota = data.get('quota')
            require(isinstance(quota, dict), 'ZCode 暂未返回额度数据')
            limits = [{k: v for k, v in pick(limit, ['type', 'unit', 'number', 'usage', 'currentValue', 'remaining', 'percentage', 'nextResetTime']).items()
                if isinstance(v, str) or (type(v) in (int, float) and math.isfinite(v))} for limit in quota.get('limits', [])]
            return {'level': quota.get('level'), 'limits': limits, 'generatedAt': data.get('generatedAt')}
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
