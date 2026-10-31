"""Private operator configuration; never packaged in the Android app."""
import json
import os
from pathlib import Path
import re
import stat

DEFAULT_CONFIG = Path.home() / '.config/pockethelm/deployment.json'


def load(path):
    path = Path(path).expanduser()
    info = path.stat()
    if not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid() or info.st_mode & 0o077:
        raise ValueError('Private deployment config must be owned by this user with mode 0600')
    value = json.loads(path.read_text())
    if value.get('schemaVersion') != 2: raise ValueError('Unsupported deployment config version')
    name = value['deviceUser']
    if not re.fullmatch(r'[a-z_][a-z0-9_-]{1,30}', name) or name == 'root': raise ValueError('Use a dedicated non-root device account')
    for key in ('adminSsh', 'targetAlias'):
        if not re.fullmatch(r'[A-Za-z0-9_][A-Za-z0-9_.@:-]{0,252}', value[key]): raise ValueError('Invalid SSH alias')
    for key in ('remoteBase', 'targetHelper', 'proxyPath', 'codexBinary'):
        if not value[key].startswith('/') or any(c in value[key] for c in '\n\r\x00'): raise ValueError('Expected an absolute path')
    p = value['profile']
    expected = {'name', 'environmentId', 'configVersion', 'useJump', 'jumpHost', 'jumpPort', 'jumpUser', 'host', 'port', 'user', 'root', 'binary', 'socket', 'useDeviceKey'}
    if set(p) != expected or p['configVersion'] != 2 or p['useJump'] is not True or p['useDeviceKey'] is not True:
        raise ValueError('Invalid paired profile schema')
    if p['jumpUser'] != name or p['user'] == 'root': raise ValueError('Paired accounts must not use root')
    for key in ('jumpHost', 'host'):
        if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9.:-]{0,252}', p[key]): raise ValueError('Invalid host')
    for key in ('jumpPort', 'port'):
        if type(p[key]) is not int or not 1 <= p[key] <= 65535: raise ValueError('Invalid port')
    if not re.fullmatch(r'[a-zA-Z_][a-zA-Z0-9_.-]{0,63}', p['user']): raise ValueError('Invalid target account')
    if not re.fullmatch(r'[A-Za-z0-9_-]{1,80}', p['environmentId']): raise ValueError('Invalid environment ID')
    if not p['name'].strip() or len(p['name']) > 100: raise ValueError('Invalid environment name')
    if not p['root'].startswith('/') or any(c in p['root'] for c in '\r\n\x00'): raise ValueError('Invalid project directory')
    if p['binary'] != 'codex' or p['socket'] != '': raise ValueError('Paired devices use the forced server-side Codex proxy')
    return value
