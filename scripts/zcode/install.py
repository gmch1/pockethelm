#!/usr/bin/env python3
"""Install the experimental bridge in the existing forced-command entry point.

Run on the development host. Uses the same private deployment config as pair.py.
No daemon restart, network listener, SSH key rewrite or token generation.
"""
import argparse
from pathlib import Path
import shlex
import re
import sys
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from pairing.config import DEFAULT_CONFIG, load
from pairing.server import atomic


def dispatcher(codex, bridge, extra_routes=None):
    # Trusted installer registry. Route values are argv, never shell supplied by a phone.
    if extra_routes is not None and not isinstance(extra_routes, dict):
        raise ValueError('Endpoint registry must be a mapping')
    routes = {'pockethelm-zcode-v1': ['/usr/bin/python3', str(bridge)]}
    for name, argv in (extra_routes or {}).items():
        if not isinstance(name, str) or not re.fullmatch(r'pockethelm-[a-z0-9-]+-v[0-9]+', name) or name in routes or name == 'pockethelm-codex-v1':
            raise ValueError('Invalid or duplicate engine endpoint')
        if not isinstance(argv, (list, tuple)) or not argv or not str(argv[0]).startswith('/') or any(not isinstance(v, str) or any(c in v for c in '\x00\n\r') for v in argv):
            raise ValueError('Endpoint requires a fixed absolute executable and argv')
        routes[name] = list(argv)
    lines = ['#!/bin/bash', 'set -euo pipefail', 'case "${SSH_ORIGINAL_COMMAND:-}" in']
    for name, argv in routes.items():
        lines.append('  ' + shlex.quote(name) + ') exec ' + ' '.join(shlex.quote(v) for v in argv) + ' ;;')
    # Old paired phones used the generated bash wrapper. It is recognized only as a
    # legacy route selector and is NEVER evaluated. All execution uses the fixed binary.
    lines += ['  pockethelm-codex-v1|""|"bash -lc "*) ;;',
        '  *) printf "%s\\n" "Unsupported PocketHelm engine endpoint" >&2; exit 64 ;;', 'esac',
        shlex.quote(str(codex)) + ' app-server daemon start >/dev/null',
        'exec ' + shlex.quote(str(codex)) + ' app-server proxy']
    return '\n'.join(lines) + '\n'


def install(config):
    root = Path.home() / '.local/share/pockethelm/zcode'
    proxy = dispatcher(config['codexBinary'], root / 'bridge.py', config.get('engineEndpoints'))
    root.mkdir(parents=True, exist_ok=True, mode=0o700)
    root.chmod(0o700)
    for name in ('bridge.py', 'wire.py', 'session_index.py'):
        atomic(root / name, (Path(__file__).parent / name).read_text(), 0o600)
    atomic(Path(config['proxyPath']), proxy, 0o700)
    print('ZCode bridge installed; existing Codex route preserved.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', type=Path, default=DEFAULT_CONFIG)
    args = parser.parse_args()
    install(load(args.config))
