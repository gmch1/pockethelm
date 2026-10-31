#!/usr/bin/env python3
"""Administrator-only deployment. Reads configuration/code on stdin over existing SSH."""
import json
import os
from pathlib import Path
import pwd
import re
import subprocess
import sys
import tempfile


def atomic(path, data, mode):
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o755)
    fd, name = tempfile.mkstemp(dir=path.parent)
    try:
        with os.fdopen(fd, 'wb') as f:
            os.fchmod(f.fileno(), mode); f.write(data); f.flush(); os.fsync(f.fileno())
        os.replace(name, path)
    finally:
        if os.path.exists(name): os.unlink(name)


def main():
    if os.geteuid() != 0: raise ValueError('Deployment requires administrator SSH')
    v = json.load(sys.stdin); user = v['deviceUser']; p = v['profile']
    if not re.fullmatch(r'[a-z_][a-z0-9_-]{1,30}', user) or user == 'root': raise ValueError('Invalid device account')
    home = '/var/lib/' + user
    try:
        account = pwd.getpwnam(user)
        if account.pw_uid == 0 or account.pw_dir != home or account.pw_gecos != 'PocketHelm SSH forwarding':
            raise ValueError('Existing account is not managed by PocketHelm')
    except KeyError:
        subprocess.run(['useradd', '--system', '--create-home', '--home-dir', home, '--shell', '/usr/sbin/nologin',
            '--comment', 'PocketHelm SSH forwarding', user], check=True)
    destination = p['host'] + ':' + str(int(p['port']))
    if not re.fullmatch(r'[A-Za-z0-9.:-]+', destination): raise ValueError('Invalid destination')
    auth = Path('/etc/ssh/pockethelm-keys') / user
    auth.parent.mkdir(mode=0o755, exist_ok=True)
    if not auth.exists(): atomic(auth, b'', 0o644)
    # This key file is public but root-owned, so the forwarding account cannot expand its rights.
    conf = ('# Managed by PocketHelm; applies only to its device account.\nMatch User ' + user + '\n'
        '    AuthenticationMethods publickey\n    PasswordAuthentication no\n    KbdInteractiveAuthentication no\n'
        '    AuthorizedKeysFile ' + str(auth) + '\n    AllowTcpForwarding local\n    AllowStreamLocalForwarding no\n'
        '    PermitOpen ' + destination + '\n    PermitListen none\n    GatewayPorts no\n'
        '    AllowAgentForwarding no\n    X11Forwarding no\n    PermitTTY no\n    PermitUserRC no\n'
        '    ForceCommand /bin/false\nMatch all\n')
    path = Path('/etc/ssh/sshd_config.d/95-pockethelm.conf')
    old = path.read_bytes() if path.exists() else None
    atomic(path, conf.encode(), 0o600)
    try:
        subprocess.run(['/usr/sbin/sshd', '-t'], check=True, capture_output=True)
        effective = subprocess.check_output(['/usr/sbin/sshd', '-T', '-C', 'user='+user+',host=localhost,addr=127.0.0.1'], text=True)
        for expected in ('allowtcpforwarding local', 'allowstreamlocalforwarding no', 'permitlisten none', 'permitopen '+destination,
                         'forcecommand /bin/false', 'authorizedkeysfile '+str(auth)):
            if expected not in effective.splitlines(): raise ValueError('SSH policy was overridden: '+expected)
        subprocess.run(['systemctl', 'reload', 'sshd'], check=True)
    except BaseException:
        if old is None: path.unlink(missing_ok=True)
        else: atomic(path, old, 0o600)
        raise
    base = Path.home() / '.local/share/codex-remote'
    if str(base) != v['remoteBase']: raise ValueError('Remote base must match the administrator home')
    base.mkdir(parents=True, exist_ok=True, mode=0o700)
    host_key = Path('/etc/ssh/ssh_host_ed25519_key.pub').read_text().split()[1]
    pins = {'host:'+p['jumpHost']+':'+str(p['jumpPort']): host_key,
        'host:'+p['host']+':'+str(p['port'])+' via '+p['jumpHost']+':'+str(p['jumpPort']): v['targetHostKey']}
    config = {k: v[k] for k in ('profile', 'targetAlias', 'targetHelper')}
    config.update(deviceAuthFile=str(auth), pins=pins)
    atomic(base/'server.py', v['serverCode'].encode(), 0o700)
    atomic(base/'config.json', json.dumps(config).encode(), 0o600)
    print(json.dumps({'ok':True, 'hostKey':host_key, 'deviceUser':user}))

if __name__ == '__main__': main()
