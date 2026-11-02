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
from bridge import Assembler, Bridge, RequestError, guard_for, project_rows, project_quota
from wire import Client, WireError, decode, encode
from install import dispatcher


class BridgeTests(unittest.TestCase):
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


if __name__ == '__main__':
    unittest.main()
