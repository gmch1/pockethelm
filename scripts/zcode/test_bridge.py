import copy
import base64
import fcntl
import io
import json
import os
from pathlib import Path
import struct
import sys
import time
import unittest
import zlib
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).parent))
from bridge import (Assembler, Bridge, RequestError, guard_for, project_rows, project_quota,
    project_interactions, interaction_answer, project_turn, project_file_changes,
    ATTACHMENT_ROW_MAX, ATTACHMENT_TRANSACTIONS_MAX, recovery_paths)
from wire import Client, WireError, decode, encode
from install import dispatcher


def make_snapshot(pending):
    return {'sessionId': 's1', 'revision': 7, 'logEpoch': 'e1',
        'meta': {'title': 't'}, 'config': {'mode': 'yolo', 'modelSelection': None},
        'control': {'phase': 'waiting', 'canStop': False, 'activeWorks': []},
        'rows': {'window': [], 'totalCount': 0}, 'queue': {'items': []},
        'pendingInteractions': pending}


def user_input_interaction(interaction_id='i-1', **payload):
    body = {'kind': 'userInput', 'prompt': '选择部署目标', 'freeText': True, **payload}
    return {'interactionId': interaction_id, 'kind': 'userInput', 'payload': body}


class BridgeTests(unittest.TestCase):
    def test_free_text_fixture_matches_real_session_read_projection(self):
        # Android reads this same bridge-generated artifact through its actual RpcClient/UI.
        fixture = Path(__file__).resolve().parents[2] / 'android/app/src/androidTest/assets/free-text-interactions.json'
        cases = json.loads(fixture.read_text())
        self.assertEqual(len(cases), 4)
        for case in cases:
            with self.subTest(payload=case['snapshot']['pendingInteractions'][0]['payload']):
                bridge = Bridge(Mock()); bridge.client_id = 'pockethelm-test'
                bridge.snapshot = Mock(return_value=case['snapshot'])
                actual = bridge.dispatch('session/read', {})
                self.assertEqual(actual, case['sessionRead'])
                detail = actual['interactions'][0]
                self.assertTrue(detail['supported'])
                self.assertTrue(detail['freeText'])
                self.assertFalse(detail.get('questions'))
                self.assertFalse(detail.get('options'))

    def test_empty_questions_preserve_nonempty_simple_options(self):
        detail = project_interactions([user_input_interaction(questions=[],
            options=[{'optionId': 'continue', 'label': '继续'}])])[0][0]
        self.assertTrue(detail['supported'])
        self.assertEqual(detail['options'], [{'optionId': 'continue', 'label': '继续'}])

    def test_free_text_does_not_bypass_invalid_structured_payload(self):
        for fields in ({'questions': {}}, {'questions': ''}, {'questions': [None]},
                       {'questions': [{'question': 'Q', 'options': []}]},
                       {'questions': [], 'options': {}}, {'questions': [], 'options': [None]},
                       {'questions': [], 'options': [{'optionId': 'x' * 1025}]},
                       {'questions': [], 'freeText': False}, {'options': [], 'freeText': False}):
            with self.subTest(fields=fields):
                detail = project_interactions([user_input_interaction(**fields)])[0][0]
                self.assertFalse(detail['supported'])

    def quota_bridge(self, kind):
        bridge = Bridge(Mock()); bridge.client_id = 'test'
        chosen = {'providerId': 'account:bigmodel-' + kind, 'modelId': 'model'}
        bridge.check_selection = Mock(return_value=chosen)
        view = {'providers': [{'providerId': chosen['providerId'], 'config': {'access': {
            'type': 'zhipu-account', 'accountType': 'bigmodel', 'mode': kind}}}]}
        return bridge, view, chosen

    def test_start_plan_uses_bound_entitlement_without_account_data(self):
        bridge, view, chosen = self.quota_bridge('start-plan')
        bridge.call = Mock(side_effect=[view, {'quota': {'limits': [{'number': 1000,
            'remaining': 750, 'currentValue': 250, 'period': 'one_time', 'unitType': 'token',
            'bucketId': 'SECRET', 'userPlanId': 'SECRET',
            'usageDetails': [{'modelCode': 'model', 'displayName': 'Model', 'secret': 'SECRET'}]}]},
            'subscription': {'identityMasked': 'SECRET'}}])
        result = bridge.dispatch('quota', {})
        self.assertEqual(bridge.call.call_args.args[:2], ('usage-stats', 'getEntitlementSnapshot'))
        request = bridge.call.call_args.args[2]
        self.assertEqual(request['accountAccess'], {'type': 'zhipu-account', 'family': 'bigmodel', 'planKind': 'start-plan'})
        self.assertEqual(request['preferredProviderId'], chosen['providerId'])
        self.assertTrue(request['requirePreferredProvider'])
        self.assertFalse(request['allowEnvApiKey'])
        self.assertNotIn('range', request)
        self.assertEqual(bridge.check_selection.call_count, 2)
        self.assertEqual(result['planKind'], 'start-plan')
        self.assertEqual(result['limits'][0]['remaining'], 750)
        self.assertEqual(result['limits'][0]['usageDetails'], [{'modelCode': 'model', 'displayName': 'Model'}])
        self.assertNotIn('SECRET', json.dumps(result))

    def test_paid_quota_keeps_monitor_and_team_scope(self):
        for kind in ('individual-coding-plan', 'team-coding-plan'):
            bridge, view, chosen = self.quota_bridge(kind)
            team = {'kind': kind, 'productId': 'product', 'organizationId': 'org', 'projectId': 'project'}
            responses = [view]
            if kind == 'team-coding-plan':
                responses.append({'providerFamilyConnectionSelections': {'bigmodel': team}})
            responses.append({'quota': {'limits': [{'unit': 3, 'number': 5, 'remaining': 8}]}})
            bridge.call = Mock(side_effect=responses)
            result = bridge.dispatch('quota', {})
            self.assertEqual(bridge.call.call_args.args[:2], ('usage-stats', 'getCodingPlanUsageSnapshot'))
            account = bridge.call.call_args.args[2]['accountAccess']
            self.assertEqual(account['planKind'], kind)
            if kind == 'team-coding-plan':
                self.assertEqual(account['projectId'], 'project')
            self.assertEqual(result['limits'][0]['number'], 5)

    def test_quota_changed_account_rejects_completed_response(self):
        bridge, view, _ = self.quota_bridge('start-plan')
        bridge.check_selection.side_effect = [bridge.check_selection.return_value, RequestError('changed')]
        bridge.call = Mock(side_effect=[view, {'quota': {'limits': []}}])
        with self.assertRaises(RequestError):
            bridge.dispatch('quota', {})

    def test_quota_missing_invalid_and_expired_are_not_zero(self):
        result = project_quota({'quota': {'limits': [None, {'remaining': None, 'usage': True,
            'number': float('inf'), 'currentValue': -1, 'percentage': 'bad', 'usageDetails': [None]}]}}, 'start-plan')
        self.assertEqual(result['limits'], [{'usageDetails': []}])
        self.assertEqual(project_quota({'quota': None, 'unavailableReason': 'no_plan', 'startPlanExpired': True}, 'start-plan'),
            {'planKind': 'start-plan', 'limits': [], 'unavailableReason': 'no_plan', 'startPlanExpired': True})

    def test_wire_roundtrip_including_bool_null_unicode(self):
        for value in [None, False, True, [100, 1, '频道', 'method'], {'text': '中文\n', 'bool': True}]:
            self.assertEqual(decode(io.BytesIO(encode(value))), value)

    def test_wire_rejects_truncated_oversized_and_nested(self):
        for value in [b'', b'\x01\x05abc', b'\x04\xff\xff\xff\x7f', b'\x01\xff\xff\xff\xff\x7f', b'\x04\x01' * 40 + b'\x00']:
            with self.assertRaises(WireError):
                decode(io.BytesIO(value))

    def test_fragment_checksum_and_length(self):
        raw = json.dumps({'payload': {'kind': 'snapshot'}}).encode()
        assembler = Assembler()
        wire = dict(wireVersion=3, kind='fragment', subscriptionId='s', logicalFrameId='f',
            fragmentCount=2, logicalBytes=len(raw), checksum={'algorithm': 'crc32', 'value': f'{zlib.crc32(raw):08x}'})
        self.assertIsNone(assembler.accept(dict(wire, fragmentIndex=0, dataBase64=base64.b64encode(raw[:10]).decode())))
        result = assembler.accept(dict(wire, fragmentIndex=1, dataBase64=base64.b64encode(raw[10:]).decode()))
        self.assertEqual(result, json.loads(raw))
        with self.assertRaises(RequestError):
            Assembler().accept(dict(wire, fragmentCount=1, fragmentIndex=0, dataBase64=base64.b64encode(raw).decode(), checksum={'algorithm': 'crc32', 'value': '00000000'}))

    def test_split_websocket_and_rpc_frames(self):
        payload = encode([201, 12]) + encode({'ok': True})
        rpc = struct.pack('!BIII', 1, 0, 0, len(payload)) + payload
        frames = bytes([2, 8]) + rpc[:8] + bytes([137, 1]) + b'p' + bytes([128, len(rpc)-8]) + rpc[8:]
        client = Client.__new__(Client); client.buffer = b''
        stream = io.BytesIO(frames)
        client.read = stream.read; client.frame = Mock()
        self.assertEqual(client.receive(), ([201, 12], {'ok': True}))
        client.frame.assert_called_once_with(b'p', 10)

    def test_unknown_operations_cannot_forward_credentials_or_settings(self):
        client = Mock(); bridge = Bridge(client); bridge.client_id = 'pockethelm-test'
        for name in ['credential.load', 'setting.update', 'exec', 'model-selection.getView', 'resolveInteraction']:
            with self.assertRaises(RequestError):
                bridge.dispatch(name, {})
        client.call.assert_not_called()

    def test_guard_ignores_refresh_timestamp_and_key_order(self):
        provider = {'providerId': 'p', 'accountState': {'connectionKey': 'a'},
            'effectiveConfig': {'mode': 'start-plan'}}
        settings = {'providerFamilyDomain': {'bigmodel': 'cn'},
            'providerFamilyConnectionSelections': {}, 'providerFamilyDomainUpdatedAt': 1000}
        expected = guard_for(provider, settings)
        for timestamp in (2000, None):
            changed = dict(settings, providerFamilyDomainUpdatedAt=timestamp)
            self.assertEqual(expected, guard_for(provider, changed))
        del settings['providerFamilyDomainUpdatedAt']
        self.assertEqual(expected, guard_for(provider, dict(reversed(list(settings.items())))))

    def test_each_identity_change_independently_rejects_old_selection(self):
        original_provider = {'providerId': 'p', 'accountState': {'connectionKey': 'a'},
            'effectiveConfig': {'mode': 'start-plan'}}
        original_settings = {'providerFamilyDomain': {'bigmodel': 'cn'},
            'providerFamilyConnectionSelections': {'bigmodel': {'organizationId': 'org-a', 'projectId': 'one'}}}
        old = guard_for(original_provider, original_settings)
        for field in ('account', 'config', 'domain', 'team', 'project'):
            with self.subTest(field=field):
                provider, settings = copy.deepcopy(original_provider), copy.deepcopy(original_settings)
                if field == 'account': provider['accountState']['connectionKey'] = 'b'
                if field == 'config': provider['effectiveConfig']['mode'] = 'team-coding-plan'
                if field == 'domain': settings['providerFamilyDomain']['bigmodel'] = 'intl'
                if field == 'team': settings['providerFamilyConnectionSelections']['bigmodel']['organizationId'] = 'org-b'
                if field == 'project': settings['providerFamilyConnectionSelections']['bigmodel']['projectId'] = 'two'
                new = guard_for(provider, settings)
                self.assertNotEqual(old, new)
                client = Mock(); bridge = Bridge(client)
                bridge.catalog = lambda: {'providers': [{'id': 'p', 'guard': new,
                    'models': [{'id': 'flash', 'levels': []}]}]}
                with self.assertRaises(RequestError):
                    bridge.check_selection({'selection': {'providerId': 'p', 'modelId': 'flash'}, 'guard': old})
                client.call.assert_not_called()

    def test_stale_guard_and_provider_remap_prevent_any_send(self):
        client = Mock(); bridge = Bridge(client); bridge.client_id = 'pockethelm-test'
        chosen = {'providerId': 'p', 'modelId': 'm'}
        bridge.catalog = lambda: {'providers': [{'id': 'p', 'guard': 'new', 'models': [{'id': 'm', 'levels': []}]}]}
        with self.assertRaises(RequestError):
            bridge.check_selection({'selection': chosen, 'guard': 'old'})
        client.call.assert_not_called()
        client.call.return_value = {'effectiveSelection': {'providerId': 'other-plan', 'modelId': 'm'}}
        with self.assertRaises(RequestError):
            bridge.check_selection({'selection': chosen, 'guard': 'new'})
        self.assertEqual([c.args[1] for c in client.call.call_args_list], ['getView'])

    def test_row_projection_does_not_dump_metadata(self):
        rows = [{'rowId': 1, 'kind': 'assistantText', 'text': 'answer', 'state': 'complete', 'metadata': {'apiKey': 'SECRET'}},
            {'rowId': 2, 'kind': 'toolCall', 'toolName': 'Bash', 'status': 'success', 'inputText': 'pwd', 'output': {'text': 'ok', 'secret': 'SECRET'}}]
        projected = project_rows(rows)
        self.assertEqual(projected[0]['id'], '1')
        self.assertEqual(projected[0]['text'], 'answer')
        self.assertNotIn('SECRET', json.dumps(projected))

    def test_directory_read_keeps_only_directory_fields(self):
        client = Mock(); bridge = Bridge(client); bridge.client_id = 'pockethelm-test'
        client.call.return_value = {'path': '/home/dev/projects', 'entries': [
            {'name': 'zeta', 'path': '/home/dev/projects/zeta', 'type': 'directory', 'isSymbolicLink': False},
            {'name': 'notes.txt', 'path': '/home/dev/projects/notes.txt', 'type': 'file', 'size': 3},
            {'name': 'alpha', 'path': '/home/dev/projects/alpha', 'type': 'directory', 'isSymbolicLink': True},
            {'name': 'junk', 'path': '/home/dev/projects/junk', 'type': 'directory'}]}
        result = bridge.dispatch('directory/read', {'path': '/home/dev/projects/'})
        self.assertEqual(client.call.call_args_list[0].args, ('project', 'readDirectory', [{'path': '/home/dev/projects/'}]))
        self.assertEqual(result, {'path': '/home/dev/projects', 'parent': '/home/dev', 'hasParent': True, 'directories': [
            {'name': 'zeta', 'path': '/home/dev/projects/zeta'},
            {'name': 'alpha', 'path': '/home/dev/projects/alpha'},
            {'name': 'junk', 'path': '/home/dev/projects/junk'}]})
        self.assertNotIn('isSymbolicLink', json.dumps(result))

    def test_directory_read_defaults_to_server_home_and_toggles_hidden(self):
        client = Mock(); bridge = Bridge(client); bridge.client_id = 'pockethelm-test'
        client.call.return_value = {'path': '/home/dev', 'entries': [{'name': 'app', 'path': '/home/dev/app', 'type': 'directory'}]}
        result = bridge.dispatch('directory/read', {})
        self.assertEqual(client.call.call_args_list[0].args[2], [{}])
        self.assertEqual(result['parent'], '/home/dev'.rsplit('/', 1)[0])
        client.call.return_value = {'path': '/', 'entries': []}
        result = bridge.dispatch('directory/read', {'includeHidden': True})
        self.assertEqual(client.call.call_args_list[1].args[2], [{'includeHidden': True}])
        self.assertEqual((result['path'], result['parent'], result['hasParent'], result['directories']), ('/', '/', False, []))

    def test_directory_read_rejects_invalid_input_before_any_rpc(self):
        client = Mock(); bridge = Bridge(client); bridge.client_id = 'pockethelm-test'
        for params in [{'path': 'relative/path'}, {'path': ''}, {'path': '/a\x00b'}, {'path': 7},
            {'includeHidden': 'yes'}, {'path': '/ok', 'includeHidden': 1}]:
            with self.assertRaises(RequestError):
                bridge.dispatch('directory/read', params)
        client.call.assert_not_called()

    def test_directory_read_maps_server_rejection_to_clear_error(self):
        client = Mock(); bridge = Bridge(client); bridge.client_id = 'pockethelm-test'
        client.call.side_effect = WireError('ZCode rejected project.readDirectory')
        with self.assertRaises(RequestError):
            bridge.dispatch('directory/read', {'path': '/gone'})
        with self.assertRaises(RequestError):
            bridge.dispatch('directory/read', {'path': '/ok', 'includeHidden': True})
        self.assertEqual([c.args[1] for c in client.call.call_args_list], ['readDirectory', 'readDirectory'])

    def test_directory_read_skips_malformed_and_relative_entries(self):
        client = Mock(); bridge = Bridge(client); bridge.client_id = 'pockethelm-test'
        client.call.return_value = {'path': '/home/dev/projects', 'entries': [
            {'name': 'ok', 'path': '/home/dev/projects/ok', 'type': 'directory'},
            {'path': '/home/dev/projects/noname', 'type': 'directory'},
            {'name': 'nopath', 'type': 'directory'},
            {'name': 7, 'path': '/home/dev/projects/badname', 'type': 'directory'},
            {'name': 'badpath', 'path': 7, 'type': 'directory'},
            {'name': 'empty', 'path': '', 'type': 'directory'},
            {'name': 'relative', 'path': 'projects/relative', 'type': 'directory'},
            {'name': 'has\x00nul', 'path': '/home/dev/projects/x', 'type': 'directory'},
            'not-a-dict']}
        result = bridge.dispatch('directory/read', {})
        self.assertEqual(result['directories'], [{'name': 'ok', 'path': '/home/dev/projects/ok'}])

    def test_directory_read_rejects_relative_server_path(self):
        client = Mock(); bridge = Bridge(client); bridge.client_id = 'pockethelm-test'
        client.call.return_value = {'path': 'home/dev/projects', 'entries': []}
        with self.assertRaises(RequestError):
            bridge.dispatch('directory/read', {})
        client.call.assert_called_once()

    def test_directory_read_rejects_wrong_path_types(self):
        client = Mock(); bridge = Bridge(client); bridge.client_id = 'pockethelm-test'
        for bad in [{'path': 7}, {'path': None, 'entries': []}, {'path': ['a'], 'entries': []}]:
            client.call.return_value = bad
            with self.assertRaises(RequestError):
                bridge.dispatch('directory/read', {})

    def test_forced_dispatch_never_evaluates_original_command(self):
        script = dispatcher('/opt/codex', Path('/opt/bridge.py'))
        self.assertIn('== "pockethelm-zcode-v1"', script)
        self.assertNotIn('eval', script)
        self.assertNotIn('bash -c', script)

    # ---- Image attachments (task 2026-11-03-image-attachments) ----

    UPLOAD_TARGET = {'workspacePath': '/w', 'sessionId': 's'}

    def attachment_bridge(self):
        client = Mock()
        client.call = Mock(return_value={'state': 'staging', 'nextChunkIndex': 0})
        bridge = Bridge(client)
        bridge.client_id = 'pockethelm-test'
        bridge.session_target = Mock(side_effect=lambda *a, **k: dict(self.UPLOAD_TARGET))
        return bridge, client

    def upstream_attachment_calls(self, client):
        return [(c.args[1], c.args[2][0]) for c in client.call.call_args_list
            if str(c.args[1]).startswith('attachment')]

    def full_upload(self, bridge, data, upload_id='upload-1', name='photo.jpg'):
        import hashlib as h
        begin = bridge.dispatch('session/attachment/begin', dict(self.UPLOAD_TARGET,
            uploadId=upload_id, fileName=name, mime='image/jpeg', totalBytes=len(data),
            totalChunks=-(-len(data) // (96 * 1024)),
            checksum='sha256:' + h.sha256(data).hexdigest()))
        step = 96 * 1024
        chunks = [data[i:i + step] for i in range(0, len(data), step)]
        for index, chunk in enumerate(chunks):
            bridge.dispatch('session/attachment/chunk', {'uploadId': upload_id,
                'chunkIndex': index, 'dataBase64': base64.b64encode(chunk).decode()})
        return begin, bridge.dispatch('session/attachment/commit', {'uploadId': upload_id})

    def test_attachment_routes_all_stages_to_desktop_workspace_identity(self):
        import hashlib
        bridge, client = self.attachment_bridge()
        target = dict(self.UPLOAD_TARGET, workspaceIdentity='remote:server:test:/w')
        bridge.session_target = Mock(return_value=target)
        def upstream(channel, method, args):
            for key, value in target.items():
                if args[0].get(key) != value:
                    raise WireError('Request workspace is not owned by this CLI')
            if method == 'attachmentBeginV4':
                return {'state': 'staging', 'nextChunkIndex': 0}
            if method == 'attachmentCommitV4':
                return {'ref': 'zcode-artifact://desktop'}
            return {'nextChunkIndex': args[0].get('chunkIndex', 0) + 1}
        client.call = Mock(side_effect=upstream)
        self.full_upload(bridge, b'picture')
        bridge.dispatch('session/attachment/begin', dict(target, uploadId='abort-me',
            fileName='p.jpg', mime='image/jpeg', totalBytes=1, totalChunks=1,
            checksum='sha256:' + hashlib.sha256(b'x').hexdigest()))
        bridge.dispatch('session/attachment/abort', {'uploadId': 'abort-me'})
        self.assertEqual({m for m, _ in self.upstream_attachment_calls(client)},
            {'attachmentBeginV4', 'attachmentChunkV4', 'attachmentCommitV4', 'attachmentAbortV4'})

    def test_attachment_two_stage_chunking_aligns_phone_blocks_to_upstream(self):
        import hashlib as h
        bridge, client = self.attachment_bridge()
        client.call = Mock(side_effect=lambda ch, m, a: {'state': 'staging', 'nextChunkIndex': 0}
            if m == 'attachmentBeginV4' else ({'ref': 'zcode-artifact://u1'} if m == 'attachmentCommitV4' else {'nextChunkIndex': a[0].get('chunkIndex', 0) + 1}))
        # 5 phone chunks = 480KiB: upstream must see one full 384KiB block plus a 96KiB tail,
        # and official begin sees the UPSTREAM chunk count (2), never the phone count (5).
        data = bytes(range(256)) * (480 * 1024 // 256)
        begin, commit = self.full_upload(bridge, data)
        self.assertEqual(begin['state'], 'staging')
        calls = self.upstream_attachment_calls(client)
        self.assertEqual(calls[0][0], 'attachmentBeginV4')
        self.assertEqual(calls[0][1]['totalChunks'], 2)
        upstream_chunks = [c for c in calls if c[0] == 'attachmentChunkV4']
        self.assertEqual(len(upstream_chunks), 2)
        self.assertEqual(upstream_chunks[0][1]['chunkIndex'], 0)
        self.assertEqual(len(base64.b64decode(upstream_chunks[0][1]['dataBase64'])), 384 * 1024)
        self.assertEqual(upstream_chunks[1][1]['chunkIndex'], 1)
        self.assertEqual(len(base64.b64decode(upstream_chunks[1][1]['dataBase64'])), 96 * 1024)
        self.assertEqual(commit['state'], 'committed')
        self.assertEqual(commit['ref'], 'zcode-artifact://u1')
        self.assertEqual(base64.b64decode(upstream_chunks[0][1]['dataBase64']) +
            base64.b64decode(upstream_chunks[1][1]['dataBase64']), data)

    def test_attachment_single_partial_phone_chunk_commits_without_upstream_block(self):
        # totalBytes < 384KiB: nothing flushes until commit pushes one partial upstream chunk.
        bridge, client = self.attachment_bridge()
        client.call = Mock(side_effect=lambda ch, m, a: {'state': 'staging', 'nextChunkIndex': 0}
            if m == 'attachmentBeginV4' else ({'ref': 'zcode-artifact://r'} if m == 'attachmentCommitV4' else {'nextChunkIndex': a[0].get('chunkIndex', 0) + 1}))
        data = b'x' * 1000
        begin, commit = self.full_upload(bridge, data)
        self.assertEqual(begin['nextChunkIndex'], 0)
        chunks = [c for c in self.upstream_attachment_calls(client) if c[0] == 'attachmentChunkV4']
        self.assertEqual(len(chunks), 1)
        self.assertEqual(len(base64.b64decode(chunks[0][1]['dataBase64'])), 1000)
        self.assertEqual(commit['state'], 'committed')

    def test_attachment_idempotent_retry_and_mismatch_rejection(self):
        bridge, client = self.attachment_bridge()
        client.call = Mock(side_effect=lambda ch, m, a: {'state': 'staging', 'nextChunkIndex': 0}
            if m == 'attachmentBeginV4' else ({'ref': 'zcode-artifact://r'} if m == 'attachmentCommitV4' else {'nextChunkIndex': a[0].get('chunkIndex', 0) + 1}))
        data = b'a' * (2 * 96 * 1024 + 10)  # 3 phone chunks
        bridge.dispatch('session/attachment/begin', dict(self.UPLOAD_TARGET, uploadId='u',
            fileName='p.jpg', mime='image/jpeg', totalBytes=len(data), totalChunks=3,
            checksum='sha256:' + __import__('hashlib').sha256(data).hexdigest()))
        first = base64.b64encode(data[:96 * 1024]).decode()
        ack1 = bridge.dispatch('session/attachment/chunk', {'uploadId': 'u', 'chunkIndex': 0, 'dataBase64': first})
        self.assertEqual(ack1['nextChunkIndex'], 1)
        # Duplicate with identical bytes: idempotent, no double counting, no extra upstream chunk.
        ack2 = bridge.dispatch('session/attachment/chunk', {'uploadId': 'u', 'chunkIndex': 0, 'dataBase64': first})
        self.assertEqual(ack2['nextChunkIndex'], 1)
        # Duplicate with different bytes: rejected.
        with self.assertRaises(RequestError):
            bridge.dispatch('session/attachment/chunk', {'uploadId': 'u', 'chunkIndex': 0,
                'dataBase64': base64.b64encode(b'b' * (96 * 1024)).decode()})
        # Skipping ahead to the last chunk while chunk 1 is expected: out of order.
        with self.assertRaises(RequestError):
            bridge.dispatch('session/attachment/chunk', {'uploadId': 'u', 'chunkIndex': 2,
                'dataBase64': base64.b64encode(data[-10:]).decode()})
        # Re-begin with identical metadata returns progress; changed metadata is refused.
        again = bridge.dispatch('session/attachment/begin', dict(self.UPLOAD_TARGET, uploadId='u',
            fileName='p.jpg', mime='image/jpeg', totalBytes=len(data), totalChunks=3,
            checksum='sha256:' + __import__('hashlib').sha256(data).hexdigest()))
        self.assertEqual((again['state'], again['nextChunkIndex']), ('staging', 1))
        with self.assertRaises(RequestError):
            bridge.dispatch('session/attachment/begin', dict(self.UPLOAD_TARGET, uploadId='u',
                fileName='other.jpg', mime='image/jpeg', totalBytes=len(data), totalChunks=3,
                checksum='sha256:' + __import__('hashlib').sha256(data).hexdigest()))
        # Wrong-size and base64-garbage chunks never touch upstream.
        before = len(client.call.call_args_list)
        for bad in ({'uploadId': 'u', 'chunkIndex': 1, 'dataBase64': base64.b64encode(b'short').decode()},
                {'uploadId': 'u', 'chunkIndex': 1, 'dataBase64': '!!!not-base64!!!'}):
            with self.assertRaises(RequestError):
                bridge.dispatch('session/attachment/chunk', bad)
        self.assertEqual(len(client.call.call_args_list), before)

    def test_attachment_checksum_and_scope_guards(self):
        bridge, client = self.attachment_bridge()
        client.call = Mock(return_value={'state': 'staging', 'nextChunkIndex': 0})
        data = b'c' * 5000
        bad = dict(self.UPLOAD_TARGET, uploadId='u', fileName='p.jpg', mime='image/jpeg',
            totalBytes=len(data), totalChunks=1, checksum='sha256:' + '0' * 64)
        bridge.dispatch('session/attachment/begin', bad)
        bridge.dispatch('session/attachment/chunk', {'uploadId': 'u', 'chunkIndex': 0,
            'dataBase64': base64.b64encode(data).decode()})
        # Whole-file digest mismatch at commit: refused, transaction aborted upstream.
        with self.assertRaises(RequestError):
            bridge.dispatch('session/attachment/commit', {'uploadId': 'u'})
        self.assertNotIn('u', bridge.attachments)
        # Non-allowlisted mime and oversized totals are rejected before any upstream call.
        client.call.reset_mock()
        for params in (dict(self.UPLOAD_TARGET, uploadId='v', fileName='p.png', mime='image/png',
                totalBytes=100, totalChunks=1, checksum='sha256:' + '0' * 64),
                dict(self.UPLOAD_TARGET, uploadId='v', fileName='p.jpg', mime='image/jpeg',
                totalBytes=8 * 1024 * 1024 + 1, totalChunks=87, checksum='sha256:' + '0' * 64)):
            with self.assertRaises(RequestError):
                bridge.dispatch('session/attachment/begin', params)
        client.call.assert_not_called()

    def test_attachment_send_validates_refs_against_committed_scope(self):
        bridge, client = self.attachment_bridge()
        client.call = Mock(side_effect=lambda ch, m, a: {'state': 'staging', 'nextChunkIndex': 0}
            if m == 'attachmentBeginV4' else ({'ref': 'zcode-artifact://r1'}
            if m == 'attachmentCommitV4' else {'commandId': 'c', 'status': 'accepted', 'nextChunkIndex': a[0].get('chunkIndex', 0) + 1}))
        data = b'd' * 300
        self.full_upload(bridge, data)
        bridge.check_selection = Mock(return_value={'providerId': 'p', 'modelId': 'm'})
        attach = [{'ref': 'zcode-artifact://r1', 'fileName': 'photo.jpg', 'mime': 'image/jpeg', 'bytes': len(data)}]

        def sent_envelope():
            return [c for c in client.call.call_args_list
                if c.args[1] == 'sendConversationCommandV4'][-1].args[2][0]['envelope']

        # Empty text with an attachment is allowed and lands verbatim in the payload.
        bridge.dispatch('session/send', dict(self.UPLOAD_TARGET, commandId='cmd-0123456789abcdef',
            text='', delivery='queue', attachments=attach))
        envelope = sent_envelope()
        self.assertEqual(envelope['payload']['attachments'], attach)
        self.assertEqual(envelope['payload']['text'], '')
        # Forged ref, metadata mismatch, and too many attachments are all refused.
        for bad in ([{'ref': 'zcode-artifact://forged', 'fileName': 'photo.jpg', 'mime': 'image/jpeg', 'bytes': 300}],
                [{'ref': 'zcode-artifact://r1', 'fileName': 'renamed.jpg', 'mime': 'image/jpeg', 'bytes': 300}],
                attach + attach + attach + attach + attach):
            with self.assertRaises(RequestError):
                bridge.dispatch('session/send', dict(self.UPLOAD_TARGET, commandId='cmd-0123456789abcdef',
                    text='hi', delivery='queue', attachments=bad))
        # Neither text nor attachments: refused.
        with self.assertRaises(RequestError):
            bridge.dispatch('session/send', dict(self.UPLOAD_TARGET, commandId='cmd-0123456789abcdef',
                text='', delivery='queue'))

    def test_attachment_commit_response_loss_resolves_via_idempotent_begin(self):
        import hashlib as h
        bridge, client = self.attachment_bridge()
        state = {'commit_failed': False}
        data = b'e' * 200

        def upstream(ch, method, args):
            if method == 'attachmentBeginV4':
                if state['commit_failed']:
                    return {'state': 'committed', 'ref': 'zcode-artifact://recovered'}
                return {'state': 'staging', 'nextChunkIndex': 0}
            if method == 'attachmentCommitV4':
                if state['commit_failed']:
                    return {'ref': 'zcode-artifact://recovered'}
                state['commit_failed'] = True
                from wire import WireError
                raise WireError('lost')
            return {'nextChunkIndex': args[0].get('chunkIndex', 0) + 1}

        client.call = Mock(side_effect=upstream)
        bridge.dispatch('session/attachment/begin', dict(self.UPLOAD_TARGET, uploadId='upload-1',
            fileName='photo.jpg', mime='image/jpeg', totalBytes=len(data), totalChunks=1,
            checksum='sha256:' + h.sha256(data).hexdigest()))
        bridge.dispatch('session/attachment/chunk', {'uploadId': 'upload-1', 'chunkIndex': 0,
            'dataBase64': base64.b64encode(data).decode()})
        with self.assertRaises(RequestError):
            bridge.dispatch('session/attachment/commit', {'uploadId': 'upload-1'})
        # Retry after the lost response: idempotent begin reveals the committed ref.
        retry = bridge.dispatch('session/attachment/commit', {'uploadId': 'upload-1'})
        self.assertEqual(retry['state'], 'committed')
        self.assertEqual(retry['ref'], 'zcode-artifact://recovered')

    def test_phone_retry_begin_recovers_unconfirmed_full_block(self):
        from wire import WireError
        for received in (False, True):
            with self.subTest(server_received=received):
                bridge, client = self.attachment_bridge()
                state = {'failed': False, 'next': 0}
                def upstream(ch, method, args):
                    if method == 'attachmentBeginV4':
                        return {'state': 'staging', 'nextChunkIndex': state['next']}
                    if method == 'attachmentChunkV4':
                        if not state['failed']:
                            state['failed'] = True
                            if received: state['next'] = 1
                            raise WireError('lost')
                        self.assertEqual(args[0]['chunkIndex'], state['next'])
                        state['next'] += 1
                        return {'nextChunkIndex': state['next']}
                    return {'ref': 'zcode-artifact://retry'}
                client.call = Mock(side_effect=upstream)
                data = b'x' * (5 * 96 * 1024)
                params = dict(self.UPLOAD_TARGET, uploadId='retry', fileName='p.jpg', mime='image/jpeg',
                    totalBytes=len(data), totalChunks=5, checksum='sha256:' + __import__('hashlib').sha256(data).hexdigest())
                bridge.attachment_begin(params)
                def send(index):
                    return bridge.attachment_chunk(dict(uploadId='retry', chunkIndex=index,
                        dataBase64=base64.b64encode(data[index*96*1024:(index+1)*96*1024]).decode()))
                for index in range(3): send(index)
                with self.assertRaises(RequestError): send(3)
                # Exact App retry: begin -> next reported phone index, never manual rewind.
                index = bridge.attachment_begin(params)['nextChunkIndex']
                self.assertEqual(index, 4)
                send(index)
                self.assertEqual(bridge.attachment_commit({'uploadId': 'retry'})['state'], 'committed')

    def test_bad_upstream_ack_does_not_advance_or_release_buffer(self):
        bridge, client = self.attachment_bridge()
        client.call = Mock(return_value={'state': 'staging', 'nextChunkIndex': 0})
        data = b'x' * (4 * 96 * 1024)
        with self.assertRaises(RequestError): self.full_upload(bridge, data)
        txn = bridge.attachments['upload-1']
        self.assertEqual(txn.upstream_flushed, 0)
        self.assertEqual(len(txn.buffer), len(data))
        self.assertTrue(txn.pending_upstream)

    def test_attachment_abort_and_transaction_limits(self):
        import hashlib as h
        bridge, client = self.attachment_bridge()
        client.call = Mock(return_value={'state': 'staging', 'nextChunkIndex': 0})
        data = b'f' * 100
        params = {'fileName': 'p.jpg', 'mime': 'image/jpeg', 'totalBytes': len(data),
            'totalChunks': 1, 'checksum': 'sha256:' + h.sha256(data).hexdigest()}
        for index in range(ATTACHMENT_TRANSACTIONS_MAX):
            self.assertEqual(bridge.dispatch('session/attachment/begin',
                dict(self.UPLOAD_TARGET, uploadId=f'limited-{index}', **params))['state'], 'staging')
        with self.assertRaises(RequestError):
            bridge.dispatch('session/attachment/begin',
                dict(self.UPLOAD_TARGET, uploadId='limited-overflow', **params))
        # Abort is idempotent and frees the slot.
        self.assertEqual(bridge.dispatch('session/attachment/abort', {'uploadId': 'limited-0'})['state'], 'aborted')
        self.assertEqual(bridge.dispatch('session/attachment/abort', {'uploadId': 'limited-0'})['state'], 'aborted')
        again = bridge.dispatch('session/attachment/begin',
            dict(self.UPLOAD_TARGET, uploadId='limited-0', **params))
        self.assertEqual(again['state'], 'staging')
        self.assertEqual([c.args[1] for c in client.call.call_args_list].count('attachmentAbortV4'), 1)

    def test_attachment_backpressure_blocks_and_resyncs_after_upstream_failures(self):
        import hashlib as h
        from wire import WireError
        bridge, client = self.attachment_bridge()
        state = {'fail_push': True, 'official_next': 0}

        def upstream(ch, method, args):
            if method == 'attachmentBeginV4':
                return {'state': 'staging', 'nextChunkIndex': state['official_next']}
            if method == 'attachmentChunkV4':
                if state['fail_push']:
                    raise WireError('upstream down')
                state['official_next'] = args[0].get('chunkIndex', 0) + 1
                return {'nextChunkIndex': state['official_next']}
            if method == 'attachmentCommitV4':
                return {'ref': 'zcode-artifact://after-retry'}
            return {}

        client.call = Mock(side_effect=upstream)
        data = b'z' * (5 * 96 * 1024)  # 5 phone chunks -> 2 upstream blocks (384K + 96K)
        bridge.dispatch('session/attachment/begin', dict(self.UPLOAD_TARGET, uploadId='bp',
            fileName='p.jpg', mime='image/jpeg', totalBytes=len(data), totalChunks=5,
            checksum='sha256:' + h.sha256(data).hexdigest()))
        block = 96 * 1024
        # Chunks 1-3 aggregate without a full block: all accepted, buffer grows.
        for index in range(3):
            ack = bridge.dispatch('session/attachment/chunk', {'uploadId': 'bp', 'chunkIndex': index,
                'dataBase64': base64.b64encode(data[index * block:(index + 1) * block]).decode()})
            self.assertEqual(ack['nextChunkIndex'], index + 1)
        txn = bridge.attachments['bp']
        # Chunk 4 completes the first 384KiB upstream block, whose push fails.
        with self.assertRaises(RequestError):
            bridge.dispatch('session/attachment/chunk', {'uploadId': 'bp', 'chunkIndex': 3,
                'dataBase64': base64.b64encode(data[3 * block:4 * block]).decode()})
        self.assertTrue(txn.pending_upstream)
        # Backpressure: a NEW chunk is refused while the block is unconfirmed — the
        # buffer never exceeds one upstream block plus nothing else.
        self.assertLessEqual(len(txn.buffer), 384 * 1024)
        with self.assertRaises(RequestError):
            bridge.dispatch('session/attachment/chunk', {'uploadId': 'bp', 'chunkIndex': 4,
                'dataBase64': base64.b64encode(data[4 * block:]).decode()})
        self.assertEqual(txn.next_index, 4)
        # Retransmitting chunk 4 (duplicate) retries the flush behind a progress resync;
        # while the push keeps failing it still refuses without advancing.
        with self.assertRaises(RequestError):
            bridge.dispatch('session/attachment/chunk', {'uploadId': 'bp', 'chunkIndex': 3,
                'dataBase64': base64.b64encode(data[3 * block:4 * block]).decode()})
        # The upstream actually received the block earlier: resync adopts its progress,
        # drops the confirmed bytes, and the transaction continues cleanly.
        state['fail_push'] = False
        state['official_next'] = 1
        ack = bridge.dispatch('session/attachment/chunk', {'uploadId': 'bp', 'chunkIndex': 3,
            'dataBase64': base64.b64encode(data[3 * block:4 * block]).decode()})
        self.assertEqual(ack['nextChunkIndex'], 4)
        self.assertFalse(txn.pending_upstream)
        self.assertEqual(txn.upstream_flushed, 384 * 1024)
        # Remaining chunk and commit finish the upload normally.
        bridge.dispatch('session/attachment/chunk', {'uploadId': 'bp', 'chunkIndex': 4,
            'dataBase64': base64.b64encode(data[4 * block:]).decode()})
        result = bridge.dispatch('session/attachment/commit', {'uploadId': 'bp'})
        self.assertEqual(result['ref'], 'zcode-artifact://after-retry')

    def test_attachment_committed_retry_validates_every_metadata_field(self):
        import hashlib as h
        bridge, client = self.attachment_bridge()
        client.call = Mock(side_effect=lambda ch, m, a: {'state': 'staging', 'nextChunkIndex': 0}
            if m == 'attachmentBeginV4' else ({'ref': 'zcode-artifact://r9'}
            if m == 'attachmentCommitV4' else {'nextChunkIndex': a[0].get('chunkIndex', 0) + 1}))
        data = b'abc'
        good = dict(self.UPLOAD_TARGET, uploadId='dup', fileName='photo.jpg', mime='image/jpeg',
            totalBytes=3, totalChunks=1, checksum='sha256:' + h.sha256(data).hexdigest())
        bridge.dispatch('session/attachment/begin', good)
        bridge.dispatch('session/attachment/chunk', {'uploadId': 'dup', 'chunkIndex': 0,
            'dataBase64': base64.b64encode(data).decode()})
        committed = bridge.dispatch('session/attachment/commit', {'uploadId': 'dup'})
        self.assertEqual(committed['ref'], 'zcode-artifact://r9')
        # Same id with ANY changed begin field is refused instead of handing out the old ref.
        for changed in ({**good, 'fileName': 'different.jpg'},
                {**good, 'totalBytes': 4, 'totalChunks': 1,
                 'checksum': 'sha256:' + h.sha256(b'abcd').hexdigest()},
                {**good, 'totalChunks': 2}):
            with self.assertRaises(RequestError):
                bridge.dispatch('session/attachment/begin', changed)
        again = bridge.dispatch('session/attachment/begin', good)
        self.assertEqual((again['state'], again['ref']), ('committed', 'zcode-artifact://r9'))

    def test_attachment_row_projection_is_bounded_and_marks_truncation(self):
        rows = [{'rowId': 1, 'kind': 'userInput', 'text': 'look', 'state': 'complete',
            'attachments': [{'ref': 'zcode-artifact://x', 'fileName': 'a.jpg', 'mime': 'image/jpeg', 'bytes': 10}]
            + [{'ref': 'zcode-artifact://y', 'fileName': f'n{i}.jpg', 'mime': 'image/jpeg', 'bytes': i}
               for i in range(ATTACHMENT_ROW_MAX + 2)]}]
        projected = project_rows(rows)[0]
        self.assertEqual(len(projected['attachments']), ATTACHMENT_ROW_MAX + 1)
        self.assertTrue(projected['attachments'][-1].get('truncated'))
        self.assertNotIn('ref', json.dumps(projected))
        # Malformed entries are skipped, control characters stripped from names.
        rows[0]['attachments'] = [{'fileName': 'bad\x1bname', 'mime': 'image/jpeg', 'bytes': 1},
            {'fileName': '', 'mime': 'image/jpeg', 'bytes': 1}, 'junk',
            {'fileName': 'ok.jpg', 'mime': 'image/jpeg', 'bytes': 2}]
        projected = project_rows(rows)[0]
        self.assertEqual([a['fileName'] for a in projected['attachments'][:2]], ['badname', 'ok.jpg'])

    def test_forced_dispatch_never_evaluates_original_command(self):
        script = dispatcher('/opt/codex', Path('/opt/bridge.py'))
        self.assertIn('== "pockethelm-zcode-v1"', script)
        self.assertNotIn('eval', script)
        self.assertNotIn('bash -c', script)
        self.assertEqual(script.count('SSH_ORIGINAL_COMMAND'), 1)

    # ---- Interactions: projection, completeness, presence (task R7/R8) ----

    def interaction_bridge(self, pending):
        bridge = Bridge(Mock()); bridge.client_id = 'pockethelm-test'
        bridge.snapshot = Mock(return_value=make_snapshot(pending))
        return bridge

    def test_interaction_projection_preserves_identity_verbatim(self):
        question = '要使用哪个数据库？' * 400  # 3600 chars, inside the 8192 identity bound
        pending = [user_input_interaction('i-user', questions=[{'question': question,
            'header': '数据库', 'multiSelect': True, 'options': [
                {'value': 'postgres', 'label': 'PostgreSQL' * 200, 'description': '推荐的默认选择'},
                {'value': 'mysql', 'label': 'MySQL'}]},
            {'question': '备注', 'header': '备注', 'options': [{'value': 'none', 'label': '无'}]}],
            currentQuestionIndex=1, answerDrafts={question: ['postgres']},
            sensitive=True, toolName='AskUserQuestion'),
            {'interactionId': 'i-perm', 'kind': 'permission', 'autoResolution':
                {'state': 'visibleCountdown', 'startedAt': 1, 'visibleAt': 2, 'deadlineAt': 99},
                'payload': {'kind': 'permission', 'toolCallId': 'c1', 'toolName': 'Bash',
                    'summary': 'rm -rf build', 'freeText': False, 'options': [
                        {'optionId': 'allow-once', 'label': '允许一次', 'kind': 'allowOnce'},
                        {'optionId': 'deny', 'label': '拒绝', 'kind': 'deny'}]}}]
        result = self.interaction_bridge(pending).dispatch('session/read', {})
        self.assertEqual(result['interactionCount'], 2)
        self.assertTrue(result['interactionsComplete'])
        user, permission = result['interactions']
        self.assertTrue(user['supported'])
        self.assertEqual(user['questions'][0]['question'], question)  # identity verbatim
        self.assertEqual(user['questions'][0]['options'][0]['value'], 'postgres')
        self.assertEqual(user['questions'][0]['options'][0]['label'], ('PostgreSQL' * 200)[:512])  # display clipped
        self.assertNotIn('autoResolution', user)
        self.assertEqual(user['answerDrafts'], {question: ['postgres']})
        self.assertEqual(user['currentQuestionIndex'], 1)
        self.assertTrue(user['sensitive'])
        self.assertTrue(permission['supported'])
        self.assertEqual([o['kind'] for o in permission['options']], ['allowOnce', 'deny'])
        self.assertEqual(permission['autoResolution'], {'state': 'visibleCountdown',
            'startedAt': 1, 'visibleAt': 2, 'deadlineAt': 99})

    def test_interaction_window_truncation_keeps_presence_authoritative(self):
        pending = [user_input_interaction(f'i-{n}', prompt=f'问题 {n}') for n in range(5)]
        bridge = self.interaction_bridge(pending)
        result = bridge.dispatch('session/read', {'checkInteractionIds': ['i-4', 'i-0', 'missing']})
        self.assertEqual(len(result['interactions']), 4)
        self.assertFalse(result['interactionsComplete'])  # truncated window is not proof
        self.assertEqual(result['interactionPresence'], [
            {'interactionId': 'i-4', 'pending': True},
            {'interactionId': 'i-0', 'pending': True},
            {'interactionId': 'missing', 'pending': False}])
        # Duplicate IDs and oversized batches are rejected before any snapshot use.
        for bad in (['i-0', 'i-0'], [f'i-{n}' for n in range(17)], ['i-0', 7]):
            with self.assertRaises(RequestError):
                bridge.dispatch('session/read', {'checkInteractionIds': bad})
        self.assertEqual(bridge.snapshot.call_count, 1)

    def test_interaction_projection_downgrades_oversized_items_wholesale(self):
        overlong = 'q' * 8193  # identity bound violated: whole item downgrades, never trimmed
        pending = [user_input_interaction('i-1', questions=[{'question': overlong,
            'header': 'h', 'options': [{'value': 'a', 'label': 'A'}]}]),
            user_input_interaction('i-2', questions=[{'question': 'ok?',
                'header': 'h', 'options': [{'value': 'a', 'label': 'A'}]}]),
            {'interactionId': 'i-3', 'kind': 'workspaceHookReview',
                'payload': {'kind': 'workspaceHookReview'}},
            {'interactionId': 'x' * 1025, 'kind': 'userInput',
                'payload': {'kind': 'userInput', 'prompt': 'p', 'freeText': False}},
            'garbage']
        result = self.interaction_bridge(pending).dispatch('session/read', {})
        first, second, hook, long_id = result['interactions']
        self.assertEqual((first['supported'], first['unsupportedReason']), (False, 'oversized'))
        self.assertEqual(first['interactionId'], 'i-1')
        self.assertTrue(second['supported'])
        self.assertEqual((hook['supported'], hook['unsupportedReason']), (False, 'unsupported_kind'))
        self.assertEqual(hook['interactionId'], 'i-3')
        self.assertEqual((long_id['supported'], long_id['unsupportedReason']), (False, 'invalid_id'))
        self.assertNotIn('interactionId', long_id)
        self.assertFalse(result['interactionsComplete'])  # unpreservable ID and window cut
        self.assertEqual(result['interactionCount'], 5)  # count still covers the hidden fifth
        self.assertEqual(result['items'], [])  # timeline unaffected by interaction downgrade

    def test_session_interact_answer_shapes_and_whitelist(self):
        client = Mock(); client.call.return_value = {'commandId': 'cmd', 'status': 'accepted'}
        bridge = Bridge(client); bridge.client_id = 'pockethelm-test'
        bridge.session_target = Mock(side_effect=lambda *a, **k: {'workspacePath': '/w', 'sessionId': 's'})
        question = '要使用哪个数据库？'

        def envelope():
            args = client.call.call_args.args
            self.assertEqual(args[:2], ('zcode-agent', 'sendConversationCommandV4'))
            return args[2][0]['envelope']

        content = {'answers': {question: 'postgres, 备注'},
            'answer_0': ['postgres', '备注'], 'answer': ['postgres', '备注']}
        for answer in ({'optionId': 'allow-once'}, {'freeText': '直接用默认'},
                {'action': 'accept', 'content': content}, {'action': 'decline'}, {'action': 'cancel'}):
            client.call.reset_mock()
            result = bridge.dispatch('session/interact', {'workspacePath': '/w', 'sessionId': 's',
                'interactionId': 'i-1', 'commandId': 'cmd-0123456789abcdef', 'answer': answer})
            self.assertEqual(result['status'], 'accepted')
            built = envelope()
            self.assertEqual(built['type'], 'resolveInteraction')
            self.assertEqual(built['payload'], {'interactionId': 'i-1', 'answer': answer})
            self.assertEqual(built['sessionId'], 's')
        for bad in ({}, {'optionId': 'a', 'freeText': 'b'}, {'action': 'accept'}, {'action': 'accept', 'content': {}},
                {'action': 'decline', 'content': content}, {'action': 'accept', 'content': {'other': 1}},
                {'action': 'accept', 'content': {'answer_16': 'x'}},
                {'action': 'accept', 'content': {'answers': {'q': ''}}},
                {'optionId': 'a' * 1025}, {'freeText': 'x' * 16385},
                {'action': 'accept', 'content': {'answer_0': ['ok', '']}}):
            with self.assertRaises(RequestError):
                bridge.dispatch('session/interact', {'workspacePath': '/w', 'sessionId': 's',
                    'interactionId': 'i-1', 'commandId': 'cmd-0123456789abcdef', 'answer': bad})
        client.call.reset_mock()
        client.call.assert_not_called()  # rejections never reached the wire

    def test_session_interact_rejects_overserialized_accept_answer(self):
        client = Mock()
        bridge = Bridge(client); bridge.client_id = 'pockethelm-test'
        bridge.session_target = Mock(side_effect=lambda *a, **k: {'workspacePath': '/w', 'sessionId': 's'})
        huge = {'answers': {('q1' * 4000): 'a' * 60000, ('q2' * 4000): 'b' * 60000}}
        with self.assertRaises(RequestError):
            bridge.dispatch('session/interact', {'workspacePath': '/w', 'sessionId': 's',
                'interactionId': 'i', 'commandId': 'cmd-0123456789abcdef',
                'answer': {'action': 'accept', 'content': huge}})
        client.call.assert_not_called()

    def test_snooze_sends_bounded_envelope(self):
        client = Mock(); client.call.return_value = {'status': 'accepted'}
        bridge = Bridge(client); bridge.client_id = 'pockethelm-test'
        bridge.session_target = Mock(side_effect=lambda *a, **k: {'workspacePath': '/w', 'sessionId': 's'})
        result = bridge.dispatch('session/interact/snooze', {'workspacePath': '/w', 'sessionId': 's',
            'interactionId': 'i-1', 'commandId': 'cmd-0123456789abcdef'})
        self.assertEqual(result['status'], 'accepted')
        envelope = client.call.call_args.args[2][0]['envelope']
        self.assertEqual(envelope['type'], 'snoozeInteractionAutoResolution')
        self.assertEqual(envelope['payload'], {'interactionId': 'i-1'})
        with self.assertRaises(RequestError):
            bridge.dispatch('session/interact/snooze', {'workspacePath': '/w', 'sessionId': 's',
                'interactionId': 'i' * 1025, 'commandId': 'cmd-0123456789abcdef'})

    def test_initialize_advertises_interaction_capability_at_version_one(self):
        client = Mock()
        client.call = Mock(side_effect=[{'protocolVersion': 3, 'clientMode': 'web-remote-replayable'}, {}])
        bridge = Bridge(client)
        result = bridge.dispatch('initialize', {'clientId': 'pockethelm-zcode-abcdefgh1234'})
        self.assertEqual(result['bridgeVersion'], 1)
        self.assertTrue(result['capabilities']['interactions'])

    def test_presence_check_rejects_non_string_before_deduplication(self):
        client = Mock()
        bridge = Bridge(client); bridge.client_id = 'pockethelm-test'
        bridge.snapshot = Mock()
        for check in ([{}], [[]], ['ok', 'ok'], [True]):
            with self.assertRaises(RequestError):
                bridge.dispatch('session/read', {'checkInteractionIds': check})
        bridge.snapshot.assert_not_called()

    # ---- P1: row identity, turn summary, file-change detail, queue commands ----

    def test_row_projection_carries_entity_id_and_skips_malformed(self):
        rows = [
            {'rowId': 1, 'kind': 'userInput', 'text': 'hi', 'entityId': 'turn-1'},
            {'rowId': 2, 'kind': 'assistantText', 'text': 'ok', 'entityId': ''},
            {'rowId': 3, 'kind': 'reasoning', 'text': 'hmm', 'entityId': 7},
            {'rowId': 4, 'kind': 'toolCall', 'entityId': 'x' * 1025},
        ]
        items = project_rows(rows)
        self.assertEqual(items[0]['entityId'], 'turn-1')
        self.assertNotIn('entityId', items[1])
        self.assertNotIn('entityId', items[2])
        self.assertNotIn('entityId', items[3])

    def test_turn_summary_uses_latest_turn_header_and_drops_garbage(self):
        none = project_turn([{'rowId': 5, 'kind': 'userInput', 'text': 'x'}])
        self.assertIsNone(none)
        self.assertIsNone(project_turn([]))
        rows = [
            {'rowId': 3, 'kind': 'turnHeader', 'state': 'completedSuccess',
                'entityId': 'turn-old', 'fileChanges': {'additions': 1, 'deletions': 1, 'files': 1}},
            {'rowId': 7, 'kind': 'turnHeader', 'state': 'running', 'entityId': 'turn-new',
                'activeMs': 112000, 'fileChanges': {'additions': 318, 'deletions': 0, 'files': 2,
                    'state': 'active'}},
        ]
        turn = project_turn(rows)
        self.assertEqual(turn, {'rowId': 7, 'entityId': 'turn-new', 'state': 'running',
            'activeMs': 112000, 'fileChanges': {'additions': 318, 'deletions': 0, 'files': 2, 'state': 'active'}})
        # Non-numeric counters and unknown enum states degrade to absence, never guesses.
        degraded = project_turn([{'rowId': 7, 'kind': 'turnHeader', 'state': 'weird',
            'entityId': 'turn-new', 'activeMs': -5, 'fileChanges': {'additions': 'many'}}])
        self.assertEqual(degraded, {'rowId': 7, 'entityId': 'turn-new', 'state': ''})

    def test_session_read_includes_latest_turn_summary(self):
        snapshot = make_snapshot([])
        snapshot['rows'] = {'window': [
            {'rowId': 3, 'kind': 'turnHeader', 'state': 'failed', 'entityId': 'turn-9',
                'activeMs': 4000, 'fileChanges': {'additions': 12, 'deletions': 4, 'files': 1}}],
            'totalCount': 1, 'firstRowId': 3}
        bridge = Bridge(Mock()); bridge.client_id = 'pockethelm-test'
        bridge.snapshot = Mock(return_value=snapshot)
        result = bridge.dispatch('session/read', {})
        self.assertEqual(result['turn'], {'rowId': 3, 'entityId': 'turn-9', 'state': 'failed',
            'activeMs': 4000, 'fileChanges': {'additions': 12, 'deletions': 4, 'files': 1}})
        bridge.snapshot = Mock(return_value=make_snapshot([]))  # window without turn headers
        self.assertNotIn('turn', bridge.dispatch('session/read', {}))

    def test_queue_commands_build_cas_envelope_from_user_snapshot_revision(self):
        client = Mock(); client.call.return_value = {'commandId': 'c', 'status': 'accepted', 'revisionAtDecision': 9}
        bridge = Bridge(client); bridge.client_id = 'pockethelm-test'
        bridge.session_target = Mock(side_effect=lambda *a, **k: {'workspacePath': '/w', 'sessionId': 's'})
        cases = [('session/queue/remove', 'deleteQueueItem', {'queueItemId': 'q1'}, {'queueItemId': 'q1'}),
                 ('session/queue/promote', 'sendQueuedNow', {'queueItemId': 'q1'}, {'queueItemId': 'q1'}),
                 ('session/queue/edit', 'editQueueItem', {'queueItemId': 'q1', 'text': '改写'},
                  {'queueItemId': 'q1', 'newText': '改写'})]
        for method, kind, params, payload in cases:
            with self.subTest(method=method):
                client.call.reset_mock()
                result = bridge.dispatch(method, dict(params, workspacePath='/w', sessionId='s',
                    commandId='cmd-0123456789abcdef', baseRevision=7))
                self.assertEqual(result['status'], 'accepted')
                envelope = client.call.call_args.args[2][0]['envelope']
                self.assertEqual(envelope['type'], kind)
                self.assertEqual(envelope['payload'], payload)
                self.assertEqual(envelope['baseRevision'], 7)
                self.assertEqual(envelope['sessionId'], 's')
        for bad_revision in (None, -1, '7', 1.5, True):
            with self.subTest(bad_revision=bad_revision):
                client.call.reset_mock()
                with self.assertRaises(RequestError):
                    bridge.dispatch('session/queue/remove', {'workspacePath': '/w', 'sessionId': 's',
                        'queueItemId': 'q1', 'commandId': 'cmd-0123456789abcdef', 'baseRevision': bad_revision})
                client.call.assert_not_called()

    def test_session_diff_sends_row_target_and_version(self):
        client = Mock()
        client.call.return_value = {'files': 2, 'additions': 5, 'deletions': 2, 'state': 'active',
            'items': [{'path': '/w/a.kt', 'additions': 5, 'deletions': 2, 'writeCount': 3,
                'toolNames': ['Edit', 'Bash'], 'patches': [{'oldStart': 1, 'oldLines': 1,
                    'newStart': 1, 'newLines': 2, 'lines': ['- old', '+ new', '+ extra']}]}]}
        bridge = Bridge(client); bridge.client_id = 'pockethelm-test'
        bridge.session_target = Mock(side_effect=lambda *a, **k: {'workspacePath': '/w', 'workspaceIdentity': 'host-a', 'sessionId': 's'})
        result = bridge.dispatch('session/diff', {'workspacePath': '/w', 'workspaceIdentity': 'host-a',
            'sessionId': 's', 'rowId': 7, 'entityId': 'turn-7', 'baseRevision': 9, 'baseLogEpoch': 'e1'})
        args = client.call.call_args.args
        self.assertEqual(args[:2], ('zcode-agent', 'conversationFileChangesV4'))
        request = args[2][0]
        self.assertEqual(request['sessionId'], 's')
        self.assertEqual(request['target'], {'rowId': 7, 'entityId': 'turn-7'})
        self.assertEqual(request['baseRevision'], 9)
        self.assertEqual(request['baseLogEpoch'], 'e1')
        self.assertEqual(request['workspacePath'], '/w')
        self.assertEqual(result['items'][0]['toolNames'], ['Edit', 'Bash'])
        self.assertEqual(result['items'][0]['patches'][0]['newLines'], 2)
        self.assertEqual(result['items'][0]['writeCount'], 3)

    def test_session_diff_rejects_bad_input_and_maps_wire_errors(self):
        client = Mock()
        bridge = Bridge(client); bridge.client_id = 'pockethelm-test'
        bridge.session_target = Mock(side_effect=lambda *a, **k: {'workspacePath': '/w', 'sessionId': 's'})
        base = {'workspacePath': '/w', 'sessionId': 's', 'rowId': 7, 'entityId': 'turn-7',
            'baseRevision': 9, 'baseLogEpoch': 'e1'}
        for key, value in (('rowId', 'x'), ('rowId', -1), ('entityId', ''), ('baseRevision', None), ('baseLogEpoch', '')):
            with self.subTest(key=key, value=value):
                client.call.reset_mock()
                with self.assertRaises(RequestError):
                    bridge.dispatch('session/diff', dict(base, **{key: value}))
                client.call.assert_not_called()
        from wire import WireError
        client.call.side_effect = WireError('rejected')
        with self.assertRaisesRegex(RequestError, '刷新'):
            bridge.dispatch('session/diff', base)

    def test_file_changes_projection_bounds_and_skips_malformed(self):
        data = {'files': 1, 'additions': 3, 'deletions': 0, 'state': 'reverted', 'items': [
            {'path': 'src/a.kt', 'additions': 3, 'toolNames': ['T%s' % i for i in range(12)],
             'patches': [{'lines': ['x' * 2000] + ['+ ok']} | {'oldStart': 1}, {'lines': []},
                         {'lines': [42]}, 'junk']},
            {'path': 5}, 'junk']}
        result = project_file_changes(data)
        item = result['items'][0]
        self.assertEqual(len(result['items']), 1)
        self.assertEqual(item['toolNames'], ['T%s' % i for i in range(8)])
        self.assertEqual(len(item['patches']), 1)
        self.assertEqual(item['patches'][0]['lines'], ['x' * 1000, '+ ok'])
        self.assertEqual(result['state'], 'reverted')
        self.assertEqual(result['deletions'], 0)
        self.assertNotIn('deletions', item)
        self.assertNotIn('writeCount', item)

    def test_initialize_advertises_p1_capabilities_at_version_one(self):
        client = Mock()
        client.call = Mock(side_effect=[{'protocolVersion': 3, 'clientMode': 'web-remote-replayable'}, {}])
        bridge = Bridge(client)
        result = bridge.dispatch('initialize', {'clientId': 'pockethelm-zcode-abcdefgh1234'})
        self.assertEqual(result['bridgeVersion'], 1)
        for capability in ('interactions', 'queueOps', 'turnSummary', 'fileChanges', 'indexMeta'):
            self.assertTrue(result['capabilities'][capability])


class StartPlanRecoveryTests(unittest.TestCase):
    # ---- Start Plan cross-day recovery (task 2026-11-03-start-plan-daily-recovery) ----

    def start_plan_record(self, executable=False, enabled=True, current=True,
                          availability='pending', reason=None):
        state = {'availability': availability, 'current': current}
        if reason:
            state['unavailableReason'] = reason
        return {'providerId': 'account:bigmodel-start-plan',
            'enabled': enabled, 'executable': executable, 'accountState': state,
            'effectiveConfig': {'access': {'type': 'zhipu-account',
                'accountType': 'bigmodel', 'mode': 'start-plan'}}}

    def model_view(self, models):
        return {'providers': [{'providerId': 'account:bigmodel-start-plan',
            'providerName': 'Start Plan', 'config': {'access': {
                'type': 'zhipu-account', 'accountType': 'bigmodel', 'mode': 'start-plan'}},
            'models': [{'modelId': m, 'config': {'optionSpecs': {
                'reasoningLevel': {'values': ['low']}}}} for m in models]}]}

    def old_data(self):
        # Pending cleared the model whitelist upstream: provider visible but 0 models.
        return {'view': self.model_view([]),
            'settings_view': {'providers': [self.start_plan_record()]},
            'settings': {}}

    def fresh_data(self):
        return {'view': self.model_view(['GLM-5.3-Flash']),
            'settings_view': {'providers': [self.start_plan_record(executable=True,
                availability='available')]},
            'settings': {}}

    def recovery_bridge(self, client, transport_factory):
        import tempfile
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.paths = recovery_paths(self._tmp.name)
        bridge = Bridge(client, recovery=self.paths)
        bridge.client_id = 'pockethelm-test'
        patcher = patch('bridge.Client', side_effect=transport_factory)
        patcher.start()
        self.addCleanup(patcher.stop)
        return bridge

    def test_catalog_recovers_pending_start_plan_via_settings_refresh(self):
        client = Mock()
        client.call.side_effect = [self.old_data()['view'], self.old_data()['settings_view'],
            self.old_data()['settings']]
        transport = Mock()
        transport.call.side_effect = [self.fresh_data()['view'], self.fresh_data()['settings_view'],
            self.fresh_data()['settings']]
        bridge = self.recovery_bridge(client, lambda: transport)
        result = bridge.dispatch('catalog', {})
        # Recovered from the empty-model pending view to a model-bearing view.
        provider = next(p for p in result['providers'] if p['id'] == 'account:bigmodel-start-plan')
        self.assertEqual(provider['models'], [{'id': 'GLM-5.3-Flash', 'levels': ['low']}])
        self.assertEqual(provider['planKind'], 'start-plan')
        # Isolated transport only: three bounded calls, budget never exceeded.
        self.assertEqual(transport.call.call_count, 3)
        for call in transport.call.call_args_list:
            self.assertEqual(call.args[:2], ('model-selection', 'getView') if
                call.args[0] == 'model-selection' else call.args[:2])
            self.assertLessEqual(call.kwargs['timeout'], 10.0)
            self.assertGreater(call.kwargs['timeout'], 0.0)
        transport.close.assert_called_once_with(cleanup_budget=3.0)
        # Attempt recorded (cooldown) and desensitized evidence logged.
        self.assertLessEqual(time.time() - float(self.paths['cooldown'].read_text()), 90.0)
        entry = json.loads(self.paths['log'].read_text().splitlines()[-1])
        self.assertEqual(entry['after'][0]['modelCount'], 1)
        self.assertEqual(entry['after'][0]['executable'], True)
        self.assertEqual(entry['before'][0]['modelCount'], 0)
        self.assertNotIn('token', json.dumps(entry))

    def test_recovery_cooldown_blocks_second_attempt(self):
        client = Mock()
        client.call.side_effect = [self.old_data()['view'], self.old_data()['settings_view'],
            self.old_data()['settings'], self.old_data()['view'],
            self.old_data()['settings_view'], self.old_data()['settings']]
        transport = Mock()
        transport.call.side_effect = [self.fresh_data()['view'], self.fresh_data()['settings_view'],
            self.fresh_data()['settings']]
        bridge = self.recovery_bridge(client, lambda: transport)
        first = bridge.dispatch('catalog', {})
        self.assertTrue(any(p['id'] == 'account:bigmodel-start-plan' for p in first['providers']))
        second = bridge.dispatch('catalog', {})
        # Cooldown active: no second transport, stale provider filtered from the list.
        self.assertEqual(transport.call.call_count, 3)
        self.assertFalse(any(p['id'] == 'account:bigmodel-start-plan' for p in second['providers']))

    def test_recovery_failures_return_complete_old_views(self):
        from wire import WireError
        cases = {
            'init': None,
            'first_view': [WireError('boom'), ],
            'second_view': [Mock(), WireError('boom')],
            'third_view': [Mock(), Mock(), WireError('boom')],
        }
        for name, effects in cases.items():
            with self.subTest(case=name):
                client = Mock()
                client.call.side_effect = [self.old_data()['view'], self.old_data()['settings_view'],
                    self.old_data()['settings']]
                transport = Mock()
                if effects is None:
                    factory = Mock(side_effect=WireError('handshake failed'))
                else:
                    transport.call.side_effect = effects
                    factory = lambda t=transport: t
                bridge = self.recovery_bridge(client, factory)
                result = bridge.dispatch('catalog', {})
                # Complete old views: stale provider filtered, no mixed data, no crash.
                self.assertFalse(any(p['id'] == 'account:bigmodel-start-plan'
                    for p in result['providers']))
                self.assertEqual(client.call.call_count, 3)
                entry = json.loads(self.paths['log'].read_text().splitlines()[-1])
                self.assertIn('error', entry)
                self.assertIsNone(entry['after'])

    def test_recovery_mutex_flock_and_storage_failures(self):
        import os as os_module
        client = Mock()
        client.call.side_effect = [self.old_data()['view'], self.old_data()['settings_view'],
            self.old_data()['settings']] * 4
        transport = Mock()
        bridge = self.recovery_bridge(client, lambda: transport)
        # Another process holds the lock: no recovery.
        self.paths['lock'].parent.mkdir(parents=True, exist_ok=True)
        holder = os_module.open(self.paths['lock'], os_module.O_RDWR | os_module.O_CREAT, 0o600)
        fcntl.flock(holder, fcntl.LOCK_EX)
        try:
            bridge.dispatch('catalog', {})
            transport.call.assert_not_called()
        finally:
            fcntl.flock(holder, fcntl.LOCK_UN); os_module.close(holder)
        # Cooldown file unwritable/unreadable (a directory): fail-closed, no recovery.
        self.paths['cooldown'].unlink(missing_ok=True)
        self.paths['cooldown'].mkdir()
        try:
            bridge.dispatch('catalog', {})
            transport.call.assert_not_called()
        finally:
            self.paths['cooldown'].rmdir()
        # Lock file path occupied by a directory: fail-closed too.
        self.paths['lock'].unlink(missing_ok=True)
        self.paths['lock'].mkdir()
        try:
            bridge.dispatch('catalog', {})
            transport.call.assert_not_called()
        finally:
            self.paths['lock'].rmdir()

    def test_recovery_skips_non_transient_states(self):
        variants = [
            self.start_plan_record(availability='unavailable', reason='not-connected'),
            self.start_plan_record(availability='unavailable', reason='not-authenticated'),
            self.start_plan_record(current=False),
            self.start_plan_record(enabled=False),
            self.start_plan_record(executable=True, availability='available'),
            self.start_plan_record(availability='available'),
        ]
        for record in variants:
            with self.subTest(availability=record['accountState']['availability'],
                              reason=record['accountState'].get('unavailableReason'),
                              current=record['accountState']['current'],
                              enabled=record['enabled'], executable=record['executable']):
                client = Mock()
                data = self.old_data()
                data['settings_view'] = {'providers': [record]}
                client.call.side_effect = [data['view'], data['settings_view'], data['settings']]
                transport = Mock()
                bridge = self.recovery_bridge(client, lambda: transport)
                bridge.dispatch('catalog', {})
                transport.call.assert_not_called()

    def test_recovery_deadline_boundaries_never_clamp_timeout(self):
        import bridge as bridge_module
        client = Mock()
        client.call.side_effect = [self.old_data()['view'], self.old_data()['settings_view'],
            self.old_data()['settings']] * 2
        transport = Mock()
        bridge = self.recovery_bridge(client, lambda: transport)
        # Budget already exhausted (negative): transport built, but no call is issued.
        with patch.object(bridge_module, 'RECOVERY_WORK_BUDGET_SECONDS', -1.0):
            result = bridge.dispatch('catalog', {})
        self.assertFalse(any(p['id'] == 'account:bigmodel-start-plan' for p in result['providers']))
        transport.call.assert_not_called()
        transport.close.assert_called_once_with(cleanup_budget=3.0)
        # 0.2s remaining is below the minimum step: same abort, no clamped 1s call.
        transport2 = Mock()
        bridge2 = self.recovery_bridge(client, lambda: transport2)
        with patch.object(bridge_module, 'RECOVERY_WORK_BUDGET_SECONDS', 0.2):
            bridge2.dispatch('catalog', {})
        transport2.call.assert_not_called()

    def test_wire_close_shares_cleanup_budget_and_survives_stubborn_child(self):
        import subprocess
        from wire import Client as WireClient
        def make(process_wait_effects):
            c = WireClient.__new__(WireClient)
            c.process = Mock()
            c.process.wait.side_effect = process_wait_effects
            c.process.stdin = Mock(); c.process.stdout = Mock()
            return c
        # terminate wait times out, kill issued, post-kill reclamation also times out:
        # close still returns bounded, closes fds, never raises.
        stubborn = make([subprocess.TimeoutExpired(cmd='p', timeout=3),
                         subprocess.TimeoutExpired(cmd='p', timeout=0)])
        proc = stubborn.process
        stubborn.close(cleanup_budget=3.0)
        proc.kill.assert_called_once()
        self.assertIsNone(stubborn.process)
        proc.stdin.close.assert_called_once()
        proc.stdout.close.assert_called_once()
        # Normal exit: terminate wait succeeds, no kill.
        normal = make([None])
        nproc = normal.process
        normal.close()
        nproc.kill.assert_not_called()
        self.assertIsNone(normal.process)


if __name__ == '__main__':
    unittest.main()
