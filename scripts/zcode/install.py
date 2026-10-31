#!/usr/bin/env python3
"""Install the experimental bridge in the existing forced-command entry point.

Run on the development host. Uses the same private deployment config as pair.py.
No daemon restart, network listener, SSH key rewrite or token generation.
"""
import argparse
from pathlib import Path
import shlex
import sys
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from pairing.config import DEFAULT_CONFIG, load
from pairing.server import atomic


def dispatcher(codex, bridge):
    # Existing phones send bash -lc ...; preserve the historical Codex route.
    # Never evaluate SSH_ORIGINAL_COMMAND. ZCode has one exact fixed command.
    return ('#!/bin/bash\nset -euo pipefail\n'
        'if [[ "${SSH_ORIGINAL_COMMAND:-}" == "pockethelm-zcode-v1" ]]; then\n'
        '  exec /usr/bin/python3 ' + shlex.quote(str(bridge)) + '\nfi\n' +
        shlex.quote(str(codex)) + ' app-server daemon start >/dev/null\nexec ' +
        shlex.quote(str(codex)) + ' app-server proxy\n')


def install(config):
    root = Path.home() / '.local/share/pockethelm/zcode'
    root.mkdir(parents=True, exist_ok=True, mode=0o700)
    root.chmod(0o700)
    for name in ('bridge.py', 'wire.py'):
        atomic(root / name, (Path(__file__).parent / name).read_text(), 0o600)
    atomic(Path(config['proxyPath']), dispatcher(config['codexBinary'], root / 'bridge.py'), 0o700)
    print('ZCode bridge installed; existing Codex route preserved.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', type=Path, default=DEFAULT_CONFIG)
    args = parser.parse_args()
    install(load(args.config))
