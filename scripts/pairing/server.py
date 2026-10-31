#!/usr/bin/env python3
"""Pinned-SSH enrollment. Only a restricted, short-lived ticket can call redeem."""
import base64
from contextlib import contextmanager
import datetime
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import pwd
import shlex
import signal
import struct
import subprocess
import sys
import tempfile
import time
import uuid

BASE = Path.home() / '.local/share/codex-remote'
AUTH = Path.home() / '.ssh/authorized_keys'
DEVICE_AUTH = AUTH
CONFIG = None


def public_key(value):
    if not isinstance(value, str) or len(value) > 500 or '\n' in value or '\r' in value:
        raise ValueError('设备公钥无效')
    parts = value.split()
    if len(parts) < 2 or parts[0] != 'ssh-ed25519':
        raise ValueError('仅支持 Ed25519 设备公钥')
    blob = base64.b64decode(parts[1], validate=True)
    if len(blob) != 51 or blob[:19] != struct.pack('!I', 11) + b'ssh-ed25519' + struct.pack('!I', 32):
        raise ValueError('设备公钥格式错误')
    device = hashlib.sha256(blob).hexdigest()[:24]
    return 'ssh-ed25519 ' + base64.b64encode(blob).decode() + ' codex-remote-device-' + device, device


def atomic(path, text, mode=0o600):
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    fd, name = tempfile.mkstemp(dir=path.parent, prefix='.codex-')
    try:
        with os.fdopen(fd, 'w') as f:
            os.fchmod(f.fileno(), mode)
            f.write(text); f.flush(); os.fsync(f.fileno())
        os.replace(name, path)
        parent = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY)
        try: os.fsync(parent)
        finally: os.close(parent)
    finally:
        if os.path.exists(name): os.unlink(name)


def save(path, data):
    atomic(path, json.dumps(data, ensure_ascii=False))


@contextmanager
def state_lock():
    BASE.mkdir(parents=True, exist_ok=True, mode=0o700)
    # One lock orders ticket binding, grants and revocation across all processes.
    with (BASE / 'state.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        yield


def update_authorized(marker, line=None, auth=None):
    auth = auth or AUTH
    auth.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    with (auth.parent / '.codex-remote.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        old = auth.read_text() if auth.exists() else ''
        new = ''.join(v for v in old.splitlines(keepends=True) if not v.rstrip().endswith(' ' + marker))
        if line: new += ('' if not new or new.endswith('\n') else '\n') + line + '\n'
        if new != old: atomic(auth, new, auth.stat().st_mode & 0o777 if auth.exists() else 0o600)


def option_command(command):
    return 'command="' + command.replace('\\', '\\\\').replace('"', '\\"') + '"'


def identifier(value, size=32):
    if not re.fullmatch('[a-f0-9]{' + str(size) + '}', value): raise ValueError('标识无效')
    return value


def load_input():
    signal.alarm(30)
    line = sys.stdin.buffer.readline(16385)
    if len(line) > 16384: raise ValueError('请求过大')
    result = json.loads(line)
    signal.alarm(0)
    return result


def config():
    if CONFIG is None: raise ValueError('配对服务尚未配置')
    return CONFIG


def issue(v):
    env = config()
    now = int(time.time())
    for path in (BASE / 'tickets').glob('*.json'):
        if json.loads(path.read_text())['expires'] <= now: revoke_ticket(path.stem)
    pub, _ = public_key(v['publicKey'])
    ticket = uuid.uuid4().hex
    expires = now + 300
    state = {k: env[k] for k in ('profile', 'pins', 'targetAlias', 'targetHelper')}
    state.update(expires=expires)
    save(BASE / 'tickets' / (ticket + '.json'), state)
    expiry = datetime.datetime.fromtimestamp(expires, datetime.timezone.utc).strftime('%Y%m%d%H%M%SZ')
    command = '/usr/bin/python3 ' + shlex.quote(str(BASE / 'server.py')) + ' redeem ' + ticket
    line = 'restrict,expiry-time="' + expiry + '",' + option_command(command) + ' ' + ' '.join(pub.split()[:2]) + ' codex-remote-pair-' + ticket
    update_authorized('codex-remote-pair-' + ticket, line)
    return {'id': ticket, 'expires': expires, 'enrollmentUser': pwd.getpwuid(os.geteuid()).pw_name}


def bind(state, pub, now):
    if state.get('revoked') or state['expires'] <= now: raise ValueError('连接码已失效，请重新生成')
    if state.get('boundPublicKey') not in (None, pub): raise ValueError('连接码已被其他设备使用，请重新生成')
    state['boundPublicKey'] = pub


def authorize_target(pub):
    pub, device = public_key(pub)
    if (BASE / 'target-revoked' / device).exists(): raise ValueError('设备已撤销，请使用新设备密钥')
    settings = BASE / 'target-config.json'
    proxy = json.loads(settings.read_text())['proxyPath'] if settings.exists() else str(Path.home() / '.local/bin/codex-remote-proxy')
    command = shlex.quote(proxy)
    update_authorized('codex-remote-device-' + device, 'restrict,' + option_command(command) + ' ' + pub)
    return {'ok': True, 'device': device}


def target_call(state, mode, value):
    command = 'python3 ' + shlex.quote(state['targetHelper']) + ' ' + mode
    data = None
    if mode == 'authorize': data = json.dumps({'publicKey': value}).encode() + b'\n'
    else: command += ' ' + identifier(value, 24)
    result = subprocess.run(['ssh', '-T', '-o', 'BatchMode=yes', '-o', 'StrictHostKeyChecking=yes',
        '-o', 'ConnectTimeout=10', state['targetAlias'], command], input=data, capture_output=True, timeout=20, check=True)
    if not json.loads(result.stdout).get('ok'): raise ValueError('开发机授权操作未完成，请重试')


def target_routes(*records):
    routes = []
    for record in records:
        # Preserve the previous single-target record when upgrading the ledger.
        for value in [*record.get('targets', []), record]:
            if 'targetAlias' not in value: continue
            route = {k: value[k] for k in ('targetAlias', 'targetHelper')}
            if route not in routes: routes.append(route)
    return routes


def redeem(ticket, v):
    ticket = identifier(ticket)
    path = BASE / 'tickets' / (ticket + '.json')
    if not path.exists(): raise ValueError('连接码已失效，请重新生成')
    pub, device = public_key(v['publicKey'])
    state = json.loads(path.read_text())
    bind(state, pub, int(time.time()))
    device_path = BASE / 'devices' / (device + '.json')
    record = json.loads(device_path.read_text()) if device_path.exists() else {}
    if record.get('status') in ('revoking', 'revoked'): raise ValueError('设备已撤销，请使用新设备密钥')
    # The revocable intent must be durable BEFORE either permanent grant is written.
    # Re-pairing must not erase the route needed to revoke an earlier grant.
    record['targets'] = target_routes(record, state)
    record.update(publicKey=pub, pairedAt=record.get('pairedAt', int(time.time())), status='granting',
        targetAlias=state['targetAlias'], targetHelper=state['targetHelper'], profile=state['profile'])
    save(device_path, record)
    save(path, state)
    # Always reconcile every stage on retry, even if an earlier ticket says complete.
    target_call(state, 'authorize', pub)
    profile = state['profile']
    destination = str(profile['host']) + ':' + str(int(profile['port']))
    if not re.fullmatch(r'[A-Za-z0-9.:-]+', destination): raise ValueError('服务器连接配置无效')
    options = 'restrict,port-forwarding,permitopen="' + destination + '",command="/bin/false"'
    update_authorized('codex-remote-device-' + device, options + ' ' + pub, DEVICE_AUTH)
    record['status'] = 'active'
    save(device_path, record)
    state['complete'] = True
    save(path, state)
    return {'ok': True, 'profile': profile, 'pins': state['pins']}


def revoke_ticket(ticket):
    ticket = identifier(ticket)
    path = BASE / 'tickets' / (ticket + '.json')
    if path.exists():
        state = json.loads(path.read_text()); state['revoked'] = True; save(path, state)
    update_authorized('codex-remote-pair-' + ticket)
    path.unlink(missing_ok=True)
    return {'ok': True}


def revoke_device(device, target_only=False):
    device = identifier(device, 24)
    if target_only:
        atomic(BASE / 'target-revoked' / device, 'revoked\n')
        update_authorized('codex-remote-device-' + device)
        return {'ok': True}
    path = BASE / 'devices' / (device + '.json')
    if path.exists(): record = json.loads(path.read_text())
    else:
        # An orphaned legacy grant must still be removable using the admin-owned route.
        env = config(); record = {k: env[k] for k in ('targetAlias', 'targetHelper')}
    record['targets'] = target_routes(record)
    record['status'] = 'revoking'; save(path, record)
    # Block the public entry first, even when the target machine is unavailable.
    update_authorized('codex-remote-device-' + device, auth=DEVICE_AUTH)
    for ticket in (BASE / 'tickets').glob('*.json'):
        pub = json.loads(ticket.read_text()).get('boundPublicKey')
        if pub and public_key(pub)[1] == device: revoke_ticket(ticket.stem)
    failures = []
    for target in record['targets']:
        try: target_call(target, 'revoke-target', device)
        except Exception as error: failures.append(error)
    # Keep every route and the revoking state until all targets confirm cleanup.
    if failures: raise failures[0]
    record['status'] = 'revoked'; record['revokedAt'] = int(time.time()); save(path, record)
    return {'ok': True}


def devices():
    records = {p.stem: json.loads(p.read_text()) for p in (BASE / 'devices').glob('*.json')}
    if DEVICE_AUTH.exists():
        for match in re.finditer(r' codex-remote-device-([a-f0-9]{24})$', DEVICE_AUTH.read_text(), re.M):
            records.setdefault(match[1], {'status': 'orphaned'})
    return [{'id': device, 'status': record.get('status', 'legacy'), 'pairedAt': record.get('pairedAt')}
        for device, record in records.items()]


def main():
    global CONFIG, DEVICE_AUTH
    try:
        mode = sys.argv[1]
        if mode not in ('authorize', 'revoke-target'):
            CONFIG = json.loads((BASE / 'config.json').read_text())
            DEVICE_AUTH = Path(CONFIG['deviceAuthFile'])
            if CONFIG['profile']['jumpUser'] == 'root': raise ValueError('手机必须使用专用无特权账号')
        value = load_input() if mode in ('issue', 'redeem', 'authorize') else None
        with state_lock():
            if mode == 'issue': result = issue(value)
            elif mode == 'redeem': result = redeem(sys.argv[2], value)
            elif mode == 'authorize': result = authorize_target(value['publicKey'])
            elif mode == 'revoke-ticket': result = revoke_ticket(sys.argv[2])
            elif mode == 'revoke-device': result = revoke_device(sys.argv[2])
            elif mode == 'revoke-target': result = revoke_device(sys.argv[2], target_only=True)
            elif mode == 'devices': result = devices()
            else: raise ValueError('不支持的操作')
        print(json.dumps(result, ensure_ascii=False), flush=True)
    except ValueError as e:
        print(json.dumps({'ok': False, 'error': str(e)}, ensure_ascii=False), flush=True); sys.exit(1)
    except Exception:
        print(json.dumps({'ok': False, 'error': '操作未完成；撤销会先关闭跳板入口，请重试以完成开发机清理'}, ensure_ascii=False), flush=True); sys.exit(1)

if __name__ == '__main__': main()
