import json
from pathlib import Path
import tempfile
import unittest
from config import load

class ConfigTests(unittest.TestCase):
    def test_private_config_rejects_public_permissions_root_and_unknown_fields(self):
        example=Path(__file__).resolve().parents[2]/'config/deployment.example.json'
        with tempfile.TemporaryDirectory() as d:
            path=Path(d)/'deployment.json';path.write_text(example.read_text());path.chmod(0o600)
            self.assertEqual(load(path)['deviceUser'],'pockethelm')
            path.chmod(0o644)
            with self.assertRaises(ValueError):load(path)
            path.chmod(0o600)
            for mutate in (
                lambda v:v.update(deviceUser='root'),
                lambda v:v['profile'].update(user='root'),
                lambda v:v['profile'].update(binary='arbitrary-command'),
                lambda v:v['profile'].update(extra='unrecognized'),
                lambda v:v['profile'].update(jumpHost='host;command'),
                lambda v:v.update(adminSsh='-oProxyCommand=command'),
            ):
                value=json.loads(example.read_text());mutate(value);path.write_text(json.dumps(value))
                with self.assertRaises(ValueError):load(path)

if __name__=='__main__':unittest.main()
