import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from install import dispatcher


class EndpointRegistryTests(unittest.TestCase):
    def test_builtin_legacy_and_third_routes_never_evaluate_phone_command(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            codex = root / 'codex tool'
            codex.write_text('#!/bin/sh\nprintf "codex\\n"\n')
            codex.chmod(0o700)
            zcode = root / 'bridge.py'
            zcode.write_text('print("zcode")\n')
            third = root / 'third.py'
            third.write_text('print("third")\n')
            script = root / 'dispatch'
            script.write_text(dispatcher(codex, zcode, {'pockethelm-third-v1': ['/usr/bin/python3', str(third)]}))
            script.chmod(0o700)
            for command, expected in [('pockethelm-codex-v1', 'codex'), ('', 'codex'),
                                      ("bash -lc 'ignored'", 'codex'), ('pockethelm-zcode-v1', 'zcode'),
                                      ('pockethelm-third-v1', 'third')]:
                result = subprocess.run([str(script)], env={**os.environ, 'SSH_ORIGINAL_COMMAND': command}, text=True, capture_output=True)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(result.stdout.strip(), expected)
            marker = root / 'must-not-exist'
            for command in ['pockethelm-missing-v1', f'touch {marker}', f"bash -lc 'touch {marker}'"]:
                result = subprocess.run([str(script)], env={**os.environ, 'SSH_ORIGINAL_COMMAND': command}, text=True, capture_output=True)
                self.assertFalse(marker.exists())
                self.assertEqual(result.returncode, 0 if command.startswith('bash -lc ') else 64)

    def test_invalid_registrations_fail_before_generating_script(self):
        for mapping in [[], {'pockethelm-third-v1': '/bin/true'}, {3: ['/bin/true']}, {'pockethelm-codex-v1': ['/bin/true']}, {'*': ['/bin/true']},
                        {'pockethelm-third-v1': ['relative']}, {'pockethelm-third-v1': ['/bin/true', '\n']}]:
            with self.assertRaises(ValueError):
                dispatcher('/bin/true', '/tmp/bridge', mapping)
