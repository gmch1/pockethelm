"""ZCode Server 3.16.x ChannelRPC transport, over its authenticated local proxy.

Only this process reads the ingress token. Disconnect closes proxy-stdio, never
Server Core or an agent runtime. No third-party Python dependencies.
"""
import base64
import hashlib
import io
import json
import os
from pathlib import Path
import select
import struct
import subprocess
import time

MAX_FRAME = 16 * 1024 * 1024


class WireError(Exception):
    pass


def vql(value):
    if type(value) is not int or not 0 <= value <= 0xffffffff:
        raise WireError('Invalid integer')
    result = bytearray()
    while value > 127:
        result.append((value & 127) | 128)
        value >>= 7
    return bytes(result) + bytes([value])


def encode(value):
    if type(value) is int:
        return b'\x06' + vql(value)
    if isinstance(value, list):
        return b'\x04' + vql(len(value)) + b''.join(map(encode, value))
    raw = (value if isinstance(value, str) else json.dumps(value, allow_nan=False)).encode()
    return bytes([1 if isinstance(value, str) else 5]) + vql(len(raw)) + raw


def decode(stream, depth=0):
    if depth > 32:
        raise WireError('Nested frame')
    def exact(count):
        if count > MAX_FRAME:
            raise WireError('Oversized value')
        value = stream.read(count)
        if len(value) != count:
            raise WireError('Truncated frame')
        return value
    def number():
        result = 0
        for i in range(5):
            byte = exact(1)[0]
            result |= (byte & 127) << (7 * i)
            if byte < 128 and result <= 0xffffffff:
                return result
        raise WireError('Invalid length')
    tag = exact(1)[0]
    if tag == 0:
        return None
    if tag == 6:
        return number()
    if tag == 4:
        count = number()
        if count > 100000:
            raise WireError('Oversized array')
        return [decode(stream, depth + 1) for _ in range(count)]
    if tag not in (1, 2, 3, 5):
        raise WireError('Unknown type')
    raw = exact(number())
    if tag == 1:
        return raw.decode()
    if tag == 5:
        return json.loads(raw)
    return raw


class Client:
    def __init__(self, binary=None):
        self.process = None
        self.serial = 0
        self.buffer = b''
        self.events = []
        self.deadline = time.monotonic() + 20
        try:
            token = (Path.home() / '.zcode/server/run/ingress-token').read_text().strip()
            if not token or '\r' in token or '\n' in token:
                raise WireError('Invalid local authentication')
            self.process = subprocess.Popen([str(binary or Path.home() / '.zcode/server/bin/zcode'), 'proxy-stdio'],
                stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, bufsize=0)
            key = base64.b64encode(os.urandom(16)).decode()
            self.write((f'GET /ws HTTP/1.1\r\nHost: localhost\r\nUpgrade: websocket\r\n'
                f'Connection: Upgrade\r\nSec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n'
                f'Authorization: Bearer {token}\r\n\r\n').encode())
            header = b''
            while not header.endswith(b'\r\n\r\n'):
                header += self.read(1)
                if len(header) > 16384:
                    raise WireError('Invalid WebSocket upgrade')
            lines = header.decode('ascii').split('\r\n')
            fields = dict(line.split(':', 1) for line in lines[1:] if ':' in line)
            fields = {k.lower(): v.strip() for k, v in fields.items()}
            accept = base64.b64encode(hashlib.sha1((key + '258EAFA5-E914-47DA-95CA-C5AB0DC85B11').encode()).digest()).decode()
            if not lines[0].startswith('HTTP/1.1 101 ') or fields.get('sec-websocket-accept') != accept:
                raise WireError('Local ZCode authentication failed')
            if self.receive()[0] != [200]:
                raise WireError('Unsupported RPC handshake')
        except Exception:
            self.close()
            raise

    def read(self, count):
        result = bytearray()
        while len(result) < count:
            remaining = self.deadline - time.monotonic()
            if remaining <= 0 or not select.select([self.process.stdout], [], [], remaining)[0]:
                raise TimeoutError('ZCode RPC timeout')
            chunk = os.read(self.process.stdout.fileno(), count - len(result))
            if not chunk:
                raise EOFError('ZCode proxy closed')
            result.extend(chunk)
        return bytes(result)

    def write(self, data):
        view = memoryview(data)
        while view:
            remaining = self.deadline - time.monotonic()
            if remaining <= 0 or not select.select([], [self.process.stdin], [], remaining)[1]:
                raise TimeoutError('ZCode write timeout')
            size = os.write(self.process.stdin.fileno(), view[:65536])
            if not size:
                raise EOFError('ZCode proxy closed')
            view = view[size:]

    def frame(self, payload, opcode=2):
        size = len(payload)
        if size > MAX_FRAME:
            raise WireError('Oversized frame')
        header = (bytes([128 | opcode, 128 | size]) if size < 126 else
            bytes([128 | opcode, 254]) + struct.pack('!H', size) if size < 65536 else
            bytes([128 | opcode, 255]) + struct.pack('!Q', size))
        mask = os.urandom(4)
        self.write(header + mask + bytes(b ^ mask[i % 4] for i, b in enumerate(payload)))

    def receive(self):
        while True:
            if len(self.buffer) >= 13:
                kind, _, _, size = struct.unpack('!BIII', self.buffer[:13])
                if size > MAX_FRAME:
                    raise WireError('Oversized RPC frame')
                if len(self.buffer) >= 13 + size:
                    data, self.buffer = self.buffer[13:13 + size], self.buffer[13 + size:]
                    if kind == 1:
                        stream = io.BytesIO(data)
                        result = decode(stream), decode(stream)
                        if stream.read(1):
                            raise WireError('Trailing RPC data')
                        return result
                    continue
            a, b = self.read(2)
            opcode, size = a & 15, b & 127
            if a & 0x70 or b & 128:
                raise WireError('Unexpected WebSocket flags')
            if size == 126:
                size = struct.unpack('!H', self.read(2))[0]
            elif size == 127:
                size = struct.unpack('!Q', self.read(8))[0]
            if size > MAX_FRAME or (opcode >= 8 and (size > 125 or not a & 128)):
                raise WireError('Oversized WebSocket frame')
            data = self.read(size)
            if opcode == 9:
                self.frame(data, 10)
            elif opcode == 8:
                raise EOFError('ZCode WebSocket closed')
            elif opcode in (0, 2):
                self.buffer += data
                if len(self.buffer) > 2 * MAX_FRAME:
                    raise WireError('Oversized receive buffer')
            elif opcode != 10:
                raise WireError('Unsupported WebSocket opcode')

    def call(self, channel, method, args=None, timeout=60):
        self.deadline = time.monotonic() + timeout
        self.serial += 1
        payload = encode([100, self.serial, channel, method]) + encode(args or [])
        self.frame(struct.pack('!BIII', 1, 0, 0, len(payload)) + payload)
        while True:
            header, body = self.receive()
            if header[0] == 204:
                self.event(header, body)
            if len(header) > 1 and header[1] == self.serial:
                if header[0] != 201:
                    # Raw errors can contain provider URLs, headers or credentials.
                    raise WireError('ZCode rejected ' + channel + '.' + method)
                return body

    def event(self, header, body):
        self.events.append((header[1], body))
        if len(self.events) > 1024:
            raise WireError('Event backlog exceeded')

    def listen(self, channel, name, params):
        self.deadline = time.monotonic() + 20
        self.serial += 1
        payload = encode([102, self.serial, channel, name]) + encode(params)
        self.frame(struct.pack('!BIII', 1, 0, 0, len(payload)) + payload)
        return self.serial

    def unlisten(self, identity):
        self.deadline = time.monotonic() + 20
        payload = encode([103, identity]) + b'\x00'
        self.frame(struct.pack('!BIII', 1, 0, 0, len(payload)) + payload)
        self.events.clear()

    def close(self):
        if self.process is not None:
            self.process.terminate()
            try:
                self.process.wait(timeout=3)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait()
            for stream in (self.process.stdin, self.process.stdout):
                stream.close()
            self.process = None
