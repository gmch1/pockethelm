#!/usr/bin/env python3
"""Generate a five-minute pairing QR using private deployment configuration."""
import argparse
import base64
import html
import json
import os
from pathlib import Path
import shlex
import subprocess
import tempfile
from pairing.config import DEFAULT_CONFIG, load

ROOT = Path(__file__).resolve().parent.parent


def ssh(config, command, data=None):
    return subprocess.run(['ssh', '-T', '-o', 'BatchMode=yes', '-o', 'StrictHostKeyChecking=yes',
        '-o', 'ConnectTimeout=10', config['adminSsh'], command], input=data, stdout=subprocess.PIPE,
        stderr=subprocess.PIPE, check=True, timeout=45).stdout


def request(config, mode, data=None):
    command = 'python3 ' + shlex.quote(config['remoteBase']+'/server.py') + ' ' + mode
    result = json.loads(ssh(config, command, None if data is None else json.dumps(data).encode()+b'\n'))
    if isinstance(result, dict) and not result.get('ok', 'id' in result): raise ValueError('Pairing server refused operation')
    return result


def deploy(config):
    from pairing.server import atomic
    helper = (ROOT/'scripts/pairing/server.py').read_bytes()
    atomic(Path(config['targetHelper']), helper.decode(), 0o700)
    binary = Path(config['codexBinary'])
    if not binary.is_file(): raise ValueError('Configured Codex executable is missing')
    proxy = '#!/bin/bash\nset -euo pipefail\n' + shlex.quote(str(binary)) + ' app-server daemon start >/dev/null\nexec ' + shlex.quote(str(binary)) + ' app-server proxy\n'
    atomic(Path(config['proxyPath']), proxy, 0o700)
    atomic(Path.home()/'.local/share/codex-remote/target-config.json', json.dumps({'proxyPath': config['proxyPath']}))
    data = dict(config, serverCode=helper.decode(), targetHostKey=Path('/etc/ssh/ssh_host_ed25519_key.pub').read_text().split()[1])
    # Administrator-owned input/code; no deployment command is reachable with a phone credential.
    source = (ROOT/'scripts/pairing/install_jump.py').read_text()
    command = 'python3 -c ' + shlex.quote(source)
    return json.loads(ssh(config, command, json.dumps(data).encode()))


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--config', type=Path, default=DEFAULT_CONFIG)
    p.add_argument('--out', type=Path, default=Path.home()/'.local/state/pockethelm/connect')
    p.add_argument('--terminal', action='store_true')
    p.add_argument('--deploy', action='store_true', help='Install/update dedicated SSH account, policy and enrollment helpers')
    p.add_argument('--list-devices', action='store_true')
    p.add_argument('--revoke-ticket')
    p.add_argument('--revoke-device')
    args = p.parse_args(); config = load(args.config); os.umask(0o077)
    if args.deploy:
        result = deploy(config)
        if not result.get('ok'): raise ValueError('Deployment failed')
        print('Restricted device account and enrollment service deployed.'); return
    if args.list_devices:
        print(json.dumps(request(config, 'devices'), ensure_ascii=False)); return
    for flag, mode, size in ((args.revoke_ticket, 'revoke-ticket', 32), (args.revoke_device, 'revoke-device', 24)):
        if flag:
            from pairing.server import identifier
            request(config, mode+' '+identifier(flag,size)); print('Revocation completed.'); return
    import qrcode
    from pairing.server import atomic
    out = args.out.expanduser().resolve()
    if out == ROOT or ROOT in out.parents: p.error('Connection codes must remain outside the project')
    out.mkdir(parents=True, exist_ok=True, mode=0o700); out.chmod(0o700)
    if out.stat().st_uid != os.getuid(): raise ValueError('Output directory must be owned by this user')
    old_receipt=out/'receipt.json'
    if old_receipt.exists():
        from pairing.server import identifier
        request(config, 'revoke-ticket '+identifier(json.loads(old_receipt.read_text())['id']))
    with tempfile.TemporaryDirectory(prefix='pockethelm-pair-') as folder:
        key=Path(folder)/'key'
        subprocess.run(['ssh-keygen','-q','-t','ed25519','-N','','-C','pairing','-f',str(key)],check=True)
        issued=request(config,'issue',{'publicKey':key.with_suffix('.pub').read_text().strip()})
        pin=ssh(config,'cat /etc/ssh/ssh_host_ed25519_key.pub').decode().split()[1]
        profile=config['profile']
        payload=dict(v=2,host=profile['jumpHost'],port=profile['jumpPort'],user=issued['enrollmentUser'],hostKey=pin,key=key.read_text(),expires=issued['expires'])
        raw='codex-remote:pair:'+base64.urlsafe_b64encode(json.dumps(payload,separators=(',',':')).encode()).decode().rstrip('=')
        atomic(out/'ticket.txt',raw);atomic(out/'receipt.json',json.dumps(issued))
        qr=qrcode.QRCode(box_size=7,border=4);qr.add_data(raw);qr.make(fit=True)
        import io
        png=io.BytesIO();qr.make_image().save(png,format='PNG')
        # atomic() writes text; use an isolated private temporary image before replacement.
        fd,tmp=tempfile.mkstemp(dir=out)
        try:
            with os.fdopen(fd,'wb') as f:f.write(png.getvalue())
            os.replace(tmp,out/'connect.png')
        finally:
            if os.path.exists(tmp):os.unlink(tmp)
        if args.terminal:qr.print_ascii(invert=True)
        atomic(out/'connect.html','<!doctype html><meta charset="utf-8"><title>PocketHelm 配对</title><style>body{font:18px system-ui;text-align:center}img{width:min(85vw,640px)}</style><h1>用 PocketHelm 扫码连接</h1><p>'+html.escape(profile['name'])+' · 5 分钟有效 · 一台设备</p><img src="connect.png">')
    print('Pairing page:',out/'connect.html');print('QR:',out/'connect.png');print('Expires:',issued['expires'])

if __name__ == '__main__': main()
