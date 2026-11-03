"""Cleanup tests use synthetic RPCs only; never start a model."""
import unittest
from unittest.mock import Mock, patch
import image_e2e as smoke


class CleanupTests(unittest.TestCase):
    def test_codex_waits_for_terminal_before_archive_and_remove(self):
        calls = []
        replies = iter([[{'id': 't', 'status': 'inProgress'}], [{'id': 't', 'status': 'inProgress'}],
                        [{'id': 't', 'status': 'interrupted'}]])
        def call(method, params):
            calls.append(method)
            if method == 'thread/turns/list': return {'data': next(replies)}
            return {}
        with patch.object(smoke.time, 'sleep'):
            smoke.cleanup_codex(Mock(call=call), 's', '/tmp/test-only')
        self.assertEqual(calls, ['thread/turns/list', 'turn/interrupt', 'thread/turns/list',
            'thread/turns/list', 'thread/archive', 'fs/remove'])

    def test_codex_does_not_remove_file_when_stop_cannot_be_verified(self):
        client = Mock()
        client.call.side_effect = RuntimeError('unreachable')
        with self.assertRaises(RuntimeError): smoke.cleanup_codex(client, 's', '/tmp/test-only')
        self.assertEqual([c.args[0] for c in client.call.call_args_list], ['thread/turns/list'])

    def test_codex_archive_failure_still_removes_terminal_image_and_reports_error(self):
        for rows in ([], [{'id': 't', 'status': 'completed'}]):
            with self.subTest(rows=rows):
                calls = []
                def call(method, params):
                    calls.append(method)
                    if method == 'thread/turns/list': return {'data': rows}
                    if method == 'thread/archive': raise RuntimeError('no rollout found')
                    return {}
                with self.assertRaisesRegex(RuntimeError, 'archive'):
                    smoke.cleanup_codex(Mock(call=call), 's', '/tmp/test-only')
                self.assertEqual(calls[-1], 'fs/remove')

    def test_codex_reports_both_independent_cleanup_failures(self):
        def call(method, params):
            if method == 'thread/turns/list': return {'data': []}
            raise RuntimeError(method)
        with self.assertRaisesRegex(RuntimeError, 'archive.*fs/remove'):
            smoke.cleanup_codex(Mock(call=call), 's', '/tmp/test-only')

    def test_zcode_first_send_precedes_rename_and_title_readback(self):
        bridge = Mock(client_id='test-client')
        order = []
        title = '[测试] image'
        def dispatch(method, params):
            order.append(method)
            if method == 'session/send': return {'status': 'accepted'}
            return {'title': title}
        def agent(method, params):
            self.assertEqual(order, ['session/send'])
            self.assertEqual(params['envelope']['type'], 'renameSession')
            order.append('renameSession')
            return {'status': 'accepted'}
        bridge.dispatch.side_effect = dispatch; bridge.agent.side_effect = agent
        smoke.send_and_name_zcode(bridge, dict(workspacePath='/tmp/test', sessionId='s',
            text='[测试] image', attachments=[{'ref': 'test-ref'}]), title)
        self.assertEqual(order, ['session/send', 'renameSession', 'session/read'])

    def test_zcode_rejected_send_does_not_rename_or_resend(self):
        bridge = Mock(client_id='test-client')
        bridge.dispatch.return_value = {'status': 'rejected'}
        with self.assertRaisesRegex(RuntimeError, 'session/send'):
            smoke.send_and_name_zcode(bridge, dict(workspacePath='/tmp/test', sessionId='s', text='[测试] image'), '[测试] image')
        bridge.dispatch.assert_called_once(); bridge.agent.assert_not_called()

    def test_zcode_rename_failure_propagates_without_resending(self):
        bridge = Mock(client_id='test-client')
        bridge.dispatch.return_value = {'status': 'accepted'}
        bridge.agent.return_value = {'status': 'rejected', 'reasonCode': 'rename-failed'}
        with self.assertRaisesRegex(RuntimeError, 'renameSession'):
            smoke.send_and_name_zcode(bridge, dict(workspacePath='/tmp/test', sessionId='s', text='[测试] image'), '[测试] image')
        bridge.dispatch.assert_called_once(); bridge.agent.assert_called_once()

    def test_zcode_stop_confirm_delete_and_verify(self):
        bridge = Mock(client_id='test-client')
        bridge.dispatch.side_effect = [dict(executionId='e', canStop=True),
                                       dict(executionId='', canStop=False)]
        bridge.agent.return_value = {'status': 'accepted'}
        bridge.projects.return_value = {'sessions': []}
        smoke.cleanup_zcode(bridge, {'workspacePath': '/tmp/test', 'sessionId': 'test-s'})
        commands = [c.args[1]['envelope'] for c in bridge.agent.call_args_list]
        self.assertEqual([c['type'] for c in commands], ['stop', 'deleteSession'])
        self.assertTrue(all(c['sessionId'] == 'test-s' for c in commands))
        bridge.projects.assert_called_once()

    def test_zcode_does_not_delete_if_stop_rejected(self):
        bridge = Mock(client_id='test-client')
        bridge.dispatch.return_value = dict(executionId='e', canStop=True)
        bridge.agent.return_value = {'status': 'rejected'}
        with self.assertRaises(RuntimeError):
            smoke.cleanup_zcode(bridge, {'workspacePath': '/tmp/test', 'sessionId': 'test-s'})
        self.assertEqual(bridge.agent.call_count, 1)
        bridge.projects.assert_not_called()


if __name__ == '__main__': unittest.main()
