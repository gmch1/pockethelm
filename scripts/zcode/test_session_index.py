import unittest
from unittest.mock import Mock, patch
from bridge import Assembler, Bridge
from session_index import IndexProjection
from wire import Client


def summary(phase='running', workspace='host-a', identity='s'):
    return dict(sessionId=identity, workspaceId=workspace, phase=phase, lastActivityAt=1,
                lastAssistantPreview='最近回复', metadata={'token': 'SECRET'})


def frame(payload, start=0, end=1, sub='sub', key='host-a'):
    return dict(wireVersion=3, kind='complete', subscriptionId=sub, frame=dict(
        subscriptionId=sub, topic='sessions-index/' + key, fromSeq=start, toSeq=end, payload=payload))


def snapshot(phase='running', epoch='epoch', key='host-a'):
    return frame(dict(kind='snapshot', snapshot=dict(protocolVersion=1, workspaceId=key,
        logEpoch=epoch, sessions=[summary(phase, key)])), key=key)


class IndexTests(unittest.TestCase):
    def entry(self):
        entry = IndexProjection({'workspacePath': '/work', 'workspaceIdentity': 'host-a'}, Assembler())
        entry.subscription, entry.epoch = 'sub', 'epoch'
        return entry

    def test_snapshot_delta_remove_and_duplicate(self):
        entry = self.entry(); entry.accept(snapshot())
        self.assertTrue(entry.fresh)
        self.assertEqual(entry.sessions['s']['phase'], 'running')
        # P1 display metadata flows; arbitrary host metadata still never does.
        self.assertEqual(entry.sessions['s']['lastAssistantPreview'], '最近回复')
        self.assertEqual(entry.sessions['s']['lastActivityAt'], 1)
        self.assertNotIn('SECRET', str(entry.sessions))
        self.assertNotIn('metadata', str(entry.sessions))
        delta = frame(dict(kind='deltas', deltas=[dict(op='session.upserted', session=summary('completedInterrupted'))]), 1, 2)
        entry.accept(delta); entry.accept(delta)
        self.assertEqual(entry.sessions['s']['phase'], 'completedInterrupted')
        entry.accept(frame(dict(kind='deltas', deltas=[dict(op='session.removed', sessionId='s')]), 2, 3))
        self.assertEqual(entry.sessions, {})

    def test_gap_wrong_epoch_or_workspace_invalidate_running(self):
        for bad in [snapshot(epoch='other'), snapshot(key='host-b'),
                    frame(dict(kind='deltas', deltas=[]), 3, 4),
                    frame(dict(kind='deltas', deltas=[dict(op='session.upserted', session=summary(workspace='host-b'))]), 1, 2)]:
            entry = self.entry(); entry.accept(snapshot()); entry.accept(bad)
            self.assertFalse(entry.fresh); self.assertTrue(entry.invalid)

    def test_late_old_subscription_cannot_change_current_stream(self):
        entry = self.entry(); entry.accept(snapshot())
        entry.accept(frame(dict(kind='deltas', deltas=[]), 5, 6, sub='old'))
        self.assertTrue(entry.fresh); self.assertEqual(entry.seq, 1)

    def test_native_subscriptions_are_reused_and_detached_without_starting_cli(self):
        client = Mock(); client.events = []
        bridge = Bridge(client)
        bridge.indexes.set_sessions([dict(workspacePath='/work', workspaceIdentity='host-a')])
        client.listen.side_effect = [41, 42]
        def call(channel, method, args):
            if method == 'subscribeSessionsIndexV4':
                self.assertEqual(args[0]['runtimePolicy'], 'existing-only')
                # Native snapshot may arrive before its subscribe ack.
                self.assertTrue(client.event_handler(42, snapshot()))
                return {'ack': {'subscriptionId': 'sub', 'logEpoch': 'epoch'}}
            return {}
        client.call.side_effect = call
        self.assertTrue(bridge.indexes.read()['workspaces'][0]['fresh'])
        bridge.indexes.read()
        self.assertEqual(sum(c.args[1] == 'subscribeSessionsIndexV4' for c in client.call.call_args_list), 1)
        bridge.indexes.pause()
        self.assertEqual(bridge.indexes.entries, {})
        self.assertEqual([c.args[0] for c in client.unlisten.call_args_list], [42, 41])
        self.assertEqual(sum(c.args[1] == 'unsubscribeSessionsIndexV4' for c in client.call.call_args_list), 1)

    def test_removing_workspace_releases_only_its_subscription(self):
        client = Mock(); bridge = Bridge(client)
        entry = self.entry(); entry.listener = 42
        bridge.indexes.entries[('/work', 'host-a')] = entry
        bridge.indexes.set_sessions([])
        self.assertFalse(bridge.indexes.entries)
        client.unlisten.assert_called_once_with(42)

    def test_runtime_exit_invalidates_and_restart_allows_resubscribe(self):
        bridge = Bridge(Mock())
        entry = self.entry(); entry.accept(snapshot()); entry.retry_at = 99999999999
        bridge.indexes.entries[('/work', 'host-a')] = entry
        bridge.indexes.lifecycle_listener = 45
        bridge.indexes.event(45, {'workspaceKey': 'host-b', 'state': 'unavailable'})
        self.assertTrue(entry.fresh)
        bridge.indexes.event(45, {'workspaceKey': 'host-a', 'state': 'unavailable'})
        self.assertFalse(entry.fresh); self.assertEqual(entry.retry_at, 0)

    def test_old_snapshot_cannot_roll_back_newer_terminal_state(self):
        entry = self.entry(); entry.accept(snapshot())
        entry.accept(frame(dict(kind='deltas', deltas=[dict(op='session.upserted', session=summary('error'))]), 1, 2))
        entry.accept(snapshot())
        self.assertEqual(entry.sessions['s']['phase'], 'error')

    def test_stdin_eof_releases_subscriptions_before_closing_proxy(self):
        import bridge as module
        client, adapter, stdin = Mock(), Mock(), Mock()
        stdin.buffer.readline.return_value = b''
        order = Mock(); order.attach_mock(adapter.indexes.pause, 'pause'); order.attach_mock(client.close, 'close')
        with patch.object(module, 'Client', return_value=client), patch.object(module, 'Bridge', return_value=adapter), patch.object(module.sys, 'stdin', stdin):
            module.main()
        self.assertEqual([c[0] for c in order.mock_calls], ['pause', 'close'])

    def test_conversation_unlisten_preserves_unrelated_frames(self):
        client = Client.__new__(Client)
        client.events = [(1, 'a'), (2, 'b')]; client.frame = Mock()
        client.unlisten(1)
        self.assertEqual(client.events, [(2, 'b')])

    def test_p1_metadata_fields_flow_bounded(self):
        entry = self.entry()
        rich = dict(summary(), pendingInteractionSummary={'permissionCount': 1, 'userInputCount': 2},
            hasBackgroundWork=True, lastOutcome='error')
        entry.accept(frame(dict(kind='snapshot', snapshot=dict(protocolVersion=1, workspaceId='host-a',
            logEpoch='epoch', sessions=[rich]))))
        self.assertTrue(entry.fresh)
        projected = entry.sessions['s']
        self.assertEqual(projected['pendingInteractionSummary'], {'permissionCount': 1, 'userInputCount': 2})
        self.assertTrue(projected['hasBackgroundWork'])
        self.assertEqual(projected['lastOutcome'], 'error')

    def test_malformed_optional_metadata_is_skipped_not_fatal(self):
        cases = [('pendingInteractionSummary', {'permissionCount': -1, 'userInputCount': 2}),
                 ('pendingInteractionSummary', {'permissionCount': True, 'userInputCount': 2}),
                 ('lastActivityAt', 'soon'), ('lastAssistantPreview', 123),
                 ('hasBackgroundWork', 'yes'), ('lastOutcome', 'vibes'), ('lastAssistantPreview', '')]
        for key, value in cases:
            with self.subTest(key=key, value=value):
                entry = self.entry()
                entry.accept(frame(dict(kind='snapshot', snapshot=dict(protocolVersion=1,
                    workspaceId='host-a', logEpoch='epoch', sessions=[dict(summary(), **{key: value})]))))
                self.assertTrue(entry.fresh, 'display-only garbage must not invalidate the stream')
                self.assertEqual(entry.sessions['s']['phase'], 'running')
                self.assertNotIn(key, entry.sessions['s'])
                self.assertNotIn('SECRET', str(entry.sessions))

    def test_preview_is_clipped_to_bound(self):
        entry = self.entry()
        entry.accept(frame(dict(kind='snapshot', snapshot=dict(protocolVersion=1, workspaceId='host-a',
            logEpoch='epoch', sessions=[dict(summary(), lastAssistantPreview='x' * 900)]))))
        self.assertEqual(len(entry.sessions['s']['lastAssistantPreview']), 512)


if __name__ == '__main__':
    unittest.main()
