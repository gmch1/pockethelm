import base64
import io
import json
from pathlib import Path
import struct
import sys
import unittest
import zlib
from unittest.mock import Mock

sys.path.insert(0, str(Path(__file__).parent))
from bridge import (Assembler, Bridge, RequestError, guard_for, project_rows, project_quota,
    project_interactions, interaction_answer, project_turn, project_file_changes)
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

    def test_guard_changes_with_team_scope_and_account(self):
        provider = {'providerId': 'team', 'accountState': {'connectionKey': 'account-a'}}
        settings = {'providerFamilyConnectionSelections': {'bigmodel': {'kind': 'team-coding-plan', 'projectId': 'one'}}}
        old = guard_for(provider, settings)
        settings['providerFamilyConnectionSelections']['bigmodel']['projectId'] = 'two'
        self.assertNotEqual(old, guard_for(provider, settings))
        provider['accountState']['connectionKey'] = 'account-b'
        self.assertNotEqual(old, guard_for(provider, settings))

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


if __name__ == '__main__':
    unittest.main()
