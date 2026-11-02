"""Offline lifecycle regression tests; never start a model turn or touch real sessions."""
import unittest
from unittest.mock import Mock, patch

import probe


class ApprovalCleanupTests(unittest.TestCase):
    def test_pending_turn_is_interrupted_before_archive(self):
        client = Mock()
        client.call.side_effect = [
            {"data": [{"id": "turn", "status": "inProgress"}]}, {},
            {"data": [{"id": "turn", "status": "interrupted"}]}, {},
        ]
        with patch.object(probe, "Client", return_value=client), patch.object(probe.time, "sleep"):
            probe.cleanup_approval_thread("fixture", False, None)
        calls = client.call.call_args_list
        self.assertEqual([c.args[0] for c in calls],
                         ["thread/turns/list", "turn/interrupt", "thread/turns/list", "thread/archive"])
        self.assertTrue(all(c.args[1]["threadId"] == "fixture" for c in calls))
        client.close.assert_called_once()

    def test_failure_before_second_client_still_cleans_created_thread(self):
        first, cleanup = Mock(), Mock()
        first.call.side_effect = [
            {"approvalPolicy": "untrusted", "thread": {"id": "fixture"}}, {},
            RuntimeError("turn/start response lost"),
        ]
        cleanup.call.side_effect = [{"data": [{"id": "turn", "status": "inProgress"}]}, {},
                                    {"data": [{"id": "turn", "status": "interrupted"}]}, {}]
        with patch.object(probe, "Client", side_effect=[first, cleanup]), \
             patch.object(probe.tempfile, "mkdtemp", return_value="/synthetic/fixture"), \
             patch.object(probe.shutil, "rmtree") as remove, patch.object(probe.time, "sleep"):
            with self.assertRaisesRegex(RuntimeError, "response lost"):
                probe.run_approval_smoke(False, None)
        first.close.assert_called_once()
        cleanup.close.assert_called_once()
        remove.assert_called_once_with("/synthetic/fixture", ignore_errors=True)

    def test_cleanup_failure_is_visible_and_preserves_directory(self):
        first, cleanup = Mock(), Mock()
        first.call.side_effect = [{"approvalPolicy": "untrusted", "thread": {"id": "fixture"}}, {},
                                  RuntimeError("test failure")]
        cleanup.call.side_effect = RuntimeError("cannot inspect turn")
        with patch.object(probe, "Client", side_effect=[first, cleanup]), \
             patch.object(probe.tempfile, "mkdtemp", return_value="/synthetic/fixture"), \
             patch.object(probe.shutil, "rmtree") as remove:
            with self.assertRaisesRegex(RuntimeError, "cannot inspect"):
                probe.run_approval_smoke(False, None)
        remove.assert_not_called()
        first.close.assert_called_once()
        cleanup.close.assert_called_once()
        self.assertNotIn("thread/archive", [c.args[0] for c in cleanup.call.call_args_list])


if __name__ == "__main__":
    unittest.main()
