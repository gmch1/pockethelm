import base64
import importlib.util
from pathlib import Path
import struct
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("pairing_server", Path(__file__).with_name("server.py"))
server = importlib.util.module_from_spec(spec)
spec.loader.exec_module(server)

def key(byte):
    blob = struct.pack("!I", 11) + b"ssh-ed25519" + struct.pack("!I", 32) + bytes([byte]) * 32
    return "ssh-ed25519 " + base64.b64encode(blob).decode() + " test"

class PairingTests(unittest.TestCase):
    def test_single_device_binding_is_idempotent_but_rejects_other_devices(self):
        state = {"expires": 200}
        pub, _ = server.public_key(key(1))
        server.bind(state, pub, 100)
        server.bind(state, pub, 101)
        with self.assertRaises(ValueError):
            server.bind(state, server.public_key(key(2))[0], 102)
        with self.assertRaises(ValueError):
            server.bind(state, pub, 200)

    def test_invalid_public_keys_never_reach_authorization(self):
        for value in [key(1) + "\ncommand=bad", "ssh-ed25519 Zm9v", key(1).replace("ed25519", "rsa")]:
            with self.assertRaises(ValueError):
                server.public_key(value)

    def test_authorization_preserves_existing_entries_and_is_reversible(self):
        with tempfile.TemporaryDirectory() as folder:
            old = server.AUTH
            server.AUTH = Path(folder) / "authorized_keys"
            original = "# existing access\n" + key(9) + "\n"
            server.AUTH.write_text(original)
            try:
                pub, device = server.public_key(key(1))
                marker = "codex-remote-device-" + device
                for _ in range(4):
                    server.update_authorized(marker, 'restrict,command="/bin/false" ' + pub)
                content = server.AUTH.read_text()
                self.assertTrue(content.startswith(original))
                self.assertEqual(content.count(marker), 1)
                server.update_authorized(marker)
                self.assertEqual(server.AUTH.read_text(), original)
            finally:
                server.AUTH = old

if __name__ == "__main__":
    unittest.main()
