import json
from pathlib import Path
import tempfile
import threading
import time
import unittest
from unittest.mock import patch
from concurrent.futures import ThreadPoolExecutor
from contextlib import contextmanager
from test_server import server, key


class RecoveryTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.patchers = [patch.object(server, 'BASE', Path(self.tmp.name)/'state'),
            patch.object(server, 'AUTH', Path(self.tmp.name)/'bootstrap/authorized_keys'),
            patch.object(server, 'DEVICE_AUTH', Path(self.tmp.name)/'device/authorized_keys')]
        for p in self.patchers: p.start()
        self.env = {'profile': {'host':'127.0.0.1','port':22000,'jumpUser':'pockethelm'},
            'pins':{},'targetAlias':'fixture','targetHelper':'/fixture/helper.py'}
        self.config = patch.object(server, 'CONFIG', self.env); self.config.start()
        self.pub, self.device = server.public_key(key(1))
        self.ticket = 'b'*32
        self.state = dict(self.env, expires=int(time.time())+300)
        server.save(server.BASE/'tickets'/(self.ticket+'.json'), self.state)
        self.target_grants = set()
        def target(state, mode, value):
            if mode == 'authorize': self.target_grants.add(server.public_key(value)[1])
            else: self.target_grants.discard(value)
        self.target = patch.object(server,'target_call',side_effect=target);self.target.start()

    def tearDown(self):
        self.target.stop();self.config.stop()
        for p in reversed(self.patchers):p.stop()
        self.tmp.cleanup()

    def grant(self):
        with server.state_lock(): return server.redeem(self.ticket,{'publicKey':self.pub})

    def revoke(self):
        with server.state_lock(): return server.revoke_device(self.device)

    def test_every_grant_write_failure_is_recoverable_and_revocable(self):
        # Inject failure before/after each durable stage, including "complete" and registry writes.
        original = server.atomic
        for after in (False, True):
            for fail_at in range(1,6):
                with self.subTest(after=after,stage=fail_at):
                    count=0
                    def faulty(*args,**kwargs):
                        nonlocal count
                        count+=1
                        if count==fail_at and not after:raise OSError('injected')
                        original(*args,**kwargs)
                        if count==fail_at and after:raise OSError('injected')
                    with patch.object(server,'atomic',side_effect=faulty):
                        with self.assertRaises(OSError):self.grant()
                    if self.target_grants or server.DEVICE_AUTH.exists() and self.device in server.DEVICE_AUTH.read_text():
                        self.assertTrue((server.BASE/'devices'/(self.device+'.json')).exists())
                    self.assertTrue(self.grant()['ok'])
                    self.assertEqual(next(d for d in server.devices() if d['id']==self.device)['status'],'active')
                    self.revoke()
                    self.assertNotIn(self.device,server.DEVICE_AUTH.read_text())
                    self.assertNotIn(self.device,self.target_grants)
                    # New fixture key for each failure, since revocation is permanent for a key.
                    self.pub,self.device=server.public_key(key(2+fail_at+(10 if after else 0)))
                    server.save(server.BASE/'tickets'/(self.ticket+'.json'),self.state)

    def test_unreachable_target_does_not_keep_jump_authorization(self):
        self.grant()
        with patch.object(server,'target_call',side_effect=OSError('offline')):
            with self.assertRaises(OSError):self.revoke()
        self.assertNotIn(self.device,server.DEVICE_AUTH.read_text())
        self.assertEqual(server.devices()[0]['status'],'revoking')
        self.assertFalse((server.BASE/'tickets'/(self.ticket+'.json')).exists())
        self.revoke();self.assertEqual(server.devices()[0]['status'],'revoked')
        self.assertNotIn(self.device,self.target_grants)

    def test_retry_cannot_resurrect_revoked_key_with_new_ticket(self):
        self.grant();self.revoke()
        server.save(server.BASE/'tickets'/(self.ticket+'.json'),self.state)
        with self.assertRaisesRegex(ValueError,'撤销'):self.grant()
        self.assertNotIn(self.device,server.DEVICE_AUTH.read_text())

    def test_orphan_can_be_listed_and_revoked_without_registry_file(self):
        server.update_authorized('codex-remote-device-'+self.device,'restrict '+self.pub,server.DEVICE_AUTH)
        self.assertEqual(server.devices()[0]['status'],'orphaned')
        self.revoke();self.assertNotIn(self.device,server.DEVICE_AUTH.read_text())

    def test_concurrent_grant_and_revoke_end_revoked(self):
        entered=threading.Event();release=threading.Event()
        def slow_target(state,mode,value):
            if mode=='authorize':
                entered.set();self.assertTrue(release.wait(3));self.target_grants.add(self.device)
            else:self.target_grants.discard(self.device)
        with patch.object(server,'target_call',side_effect=slow_target),ThreadPoolExecutor(max_workers=2) as pool:
            grant=pool.submit(self.grant);self.assertTrue(entered.wait(3))
            revoke=pool.submit(self.revoke);release.set()
            self.assertTrue(grant.result(5)['ok']);self.assertTrue(revoke.result(5)['ok'])
        self.assertNotIn(self.device,server.DEVICE_AUTH.read_text());self.assertFalse(self.target_grants)

    def test_target_tombstone_rejects_late_authorization(self):
        server.authorize_target(self.pub)
        server.revoke_device(self.device,target_only=True)
        with self.assertRaisesRegex(ValueError,'撤销'):server.authorize_target(self.pub)
        self.assertNotIn(self.device,server.AUTH.read_text())

    @contextmanager
    def routed_targets(self, offline=None, fail_after_grant=False):
        # Run the real target authorization/revocation code in isolated directories.
        def target(state, mode, value):
            alias = state['targetAlias']
            if alias in (offline or set()): raise OSError('offline: ' + alias)
            base = Path(self.tmp.name) / alias
            with patch.object(server, 'BASE', base), patch.object(server, 'AUTH', base/'authorized_keys'):
                if mode == 'authorize':
                    server.authorize_target(value)
                    if fail_after_grant: raise OSError('lost grant response')
                else: server.revoke_device(value, target_only=True)
        with patch.object(server, 'target_call', side_effect=target): yield

    def re_pair(self, alias):
        self.ticket = ('c' if alias == 'second' else 'd') * 32
        server.save(server.BASE/'tickets'/(self.ticket+'.json'), dict(self.state, targetAlias=alias))
        return self.grant()

    def assert_target_removed(self, alias):
        base = Path(self.tmp.name) / alias
        self.assertNotIn(self.device, (base/'authorized_keys').read_text())
        self.assertTrue((base/'target-revoked'/self.device).exists())

    def test_re_pair_preserves_legacy_route_and_revokes_every_target(self):
        with self.routed_targets():
            self.grant()
            path = server.BASE/'devices'/(self.device+'.json')
            legacy = json.loads(path.read_text()); del legacy['targets']; server.save(path, legacy)
            self.re_pair('second'); self.grant()  # Retrying must not duplicate routes.
            self.assertEqual(len(json.loads(path.read_text())['targets']), 2)
            self.assertTrue(self.revoke()['ok'])
            self.assert_target_removed('fixture'); self.assert_target_removed('second')
            self.assertNotIn(self.device, server.DEVICE_AUTH.read_text())
            self.assertEqual(server.devices()[0]['status'], 'revoked')

    def test_offline_old_target_does_not_prevent_new_target_cleanup(self):
        with self.routed_targets(): self.grant(); self.re_pair('second')
        with self.routed_targets(offline={'fixture'}):
            with self.assertRaisesRegex(OSError, 'offline'): self.revoke()
        self.assert_target_removed('second')
        self.assertNotIn(self.device, server.DEVICE_AUTH.read_text())
        self.assertEqual(server.devices()[0]['status'], 'revoking')
        with self.routed_targets(): self.revoke()
        self.assert_target_removed('fixture')
        self.assertEqual(server.devices()[0]['status'], 'revoked')

    def test_partial_re_pair_keeps_both_grants_revocable(self):
        with self.routed_targets(): self.grant()
        with self.routed_targets(fail_after_grant=True):
            with self.assertRaisesRegex(OSError, 'lost grant response'): self.re_pair('second')
        self.assertEqual(server.devices()[0]['status'], 'granting')
        with self.routed_targets(): self.revoke()
        self.assert_target_removed('fixture'); self.assert_target_removed('second')

if __name__=='__main__':unittest.main()
