#!/usr/bin/env python3
"""Image-attachment end-to-end smoke over the REAL dev-machine services.

Codex: writes a synthetic two-color PNG via fs/writeFile, sends it as a localImage
input through a real model turn, and verifies the reply names both colors.
ZCode: runs the bridge's two-stage attachment upload against the live server,
sends the artifact ref with sendText, and verifies the reply the same way.

The synthetic image carries no personal data; both threads are archived/cleaned.
"""
import base64
import json
import struct
import subprocess
import sys
import time
import zlib
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))

TOP, BOTTOM = '红色', '蓝色'


def png_two_colors(width=320, height=160, top=(255, 0, 0), bottom=(0, 0, 255)):
    """Minimal RGB PNG: solid top half / bottom half, no ancillary chunks."""
    def chunk(tag, data):
        return struct.pack('>I', len(data)) + tag + data + struct.pack('>I', zlib.crc32(tag + data) & 0xFFFFFFFF)
    raw = b''.join(b'\x00' + bytes(color) * width
                   for color in ([top] * (height // 2) + [bottom] * (height // 2)))
    return (b'\x89PNG\r\n\x1a\n'
            + chunk(b'IHDR', struct.pack('>IIBBBBB', width, height, 8, 2, 0, 0, 0))
            + chunk(b'IDAT', zlib.compress(raw, 9))
            + chunk(b'IEND', b''))


PROMPT = ('[测试] 这张图片的上半部分和下半部分分别是什么颜色？请只用中文回答两个颜色词，'
          '格式：上=XX，下=XX。不要使用工具，不要读取文件。')


def reply_mentions_colors(text):
    return ('红' in text or 'red' in text.lower()) and ('蓝' in text or 'blue' in text.lower())


def codex_image_smoke():
    from probe import Client
    image = png_two_colors()
    client = Client()
    thread = None
    path = None
    try:
        import uuid
        path = f'/tmp/pockethelm-img-e2e-{uuid.uuid4().hex}.png'
        client.call('fs/writeFile', {'path': path, 'dataBase64': base64.b64encode(image).decode()})
        result = client.call('thread/start', {'cwd': '/tmp', 'approvalPolicy': 'never', 'sandbox': 'danger-full-access'})
        thread = result['thread']['id']
        client.call('thread/name/set', {'threadId': thread, 'name': '[测试] Image attachment e2e smoke'})
        # The turn uses the account's configured default model (thread-level setting).
        turn = client.call('turn/start', {'threadId': thread,
            'input': [{'type': 'text', 'text': PROMPT}, {'type': 'localImage', 'path': path, 'detail': 'auto'}],
            'approvalPolicy': 'never', 'sandboxPolicy': {'type': 'dangerFullAccess'}})['turn']
        deadline = time.monotonic() + 180
        status = None
        while time.monotonic() < deadline:
            time.sleep(2)
            turns = client.call('thread/turns/list', {'threadId': thread, 'limit': 1,
                'sortDirection': 'desc', 'itemsView': 'notLoaded'}).get('data') or []
            status = turns[0]['status'] if turns else None
            if status in ('completed', 'failed', 'interrupted'):
                break
        if status != 'completed':
            failure = next((t.get('error') for t in (turns or []) if t.get('error')), None)
            raise AssertionError(f'turn ended as {status}: {failure}')
        items = client.call('thread/items/list', {'threadId': thread, 'limit': 20,
            'sortDirection': 'desc'})['data']
        replies = [v['item'].get('text', '') for v in items if v['item'].get('type') == 'agentMessage']
        user = [v['item'] for v in items if v['item'].get('type') == 'userMessage']
        assert user and any(i.get('type') == 'localImage' for i in user[0].get('content', [])), \
            'history does not restore the image item'
        assert replies and reply_mentions_colors(replies[-1]), f'model reply does not name both colors: {replies[-1][:200]!r}'
        print(f'PASS: Codex real turn read the image; reply = {replies[-1].strip()[:80]!r}', flush=True)
        client.call('fs/remove', {'path': path})
    finally:
        cleanup = []
        if thread:
            try:
                turns = client.call('thread/turns/list', {'threadId': thread, 'limit': 1,
                    'sortDirection': 'desc', 'itemsView': 'notLoaded'}).get('data') or []
                if turns and turns[0]['status'] == 'inProgress':
                    client.call('turn/interrupt', {'threadId': thread, 'turnId': turns[0]['id']})
                    cleanup.append('interrupted live turn')
            except Exception as e:
                cleanup.append(f'WARN interrupt: {type(e).__name__}')
            try:
                client.call('thread/archive', {'threadId': thread})
                cleanup.append('archived')
            except Exception as e:
                cleanup.append(f'WARN archive: {type(e).__name__}')
        if path:
            try:
                client.call('fs/remove', {'path': path})
                cleanup.append('temp file removed')
            except Exception as e:
                cleanup.append(f'WARN fs/remove: {type(e).__name__}')
        client.close()
        print('CLEANUP:', '; '.join(cleanup) or 'nothing to clean', flush=True)


def zcode_image_smoke():
    """Drive the real bridge over its JSONL stdio — exactly the phone's wire path."""
    import hashlib
    image = png_two_colors()
    proc = subprocess.Popen(['python3', str(Path(__file__).parent / 'zcode' / 'bridge.py')],
        stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
    serial = [0]

    def dispatch(method, params):
        serial[0] += 1
        proc.stdin.write((json.dumps({'id': serial[0], 'method': method, 'params': params}) + '\n').encode())
        proc.stdin.flush()
        line = proc.stdout.readline()
        if not line:
            raise RuntimeError('bridge closed')
        reply = json.loads(line)
        if 'error' in reply:
            raise RuntimeError(f'{method}: {reply["error"]}')
        return reply['result']

    try:
        dispatch('initialize', {'clientId': 'pockethelm-image-e2e-000001'})
        projects = dispatch('projects', {})
        smoke = next((p for p in projects['projects'] if 'zcode-smoke' in p['roots'][0]), None)
        assert smoke, 'smoke workspace missing; create it once via the App or install smoke'
        workspace = smoke['roots'][0]
        catalog = dispatch('catalog', {})
        provider = catalog['providers'][0] if catalog['providers'] else None
        assert provider, 'no executable provider available'
        # Prefer the cheapest vision-capable tier when present.
        model = next((m['id'] for m in provider['models'] if 'Flash' in m['id']),
            provider['models'][-1]['id'] if provider['models'] else None)
        assert model, 'provider exposes no models'
        selection = {'providerId': provider['id'], 'modelId': model,
            'options': {'reasoningLevel': 'low'}}
        print(f'using {provider["id"]} / {model}', flush=True)
        stamp = int(time.time())
        created = dispatch('session/create', {'workspacePath': workspace, 'projectId': smoke['id'],
            'commandId': f'e2e-img-{stamp}-create-0001', 'selection': selection, 'guard': provider['guard']})
        session = created['result']['sessionId']
        upload_id = f'e2e-img-{stamp}'
        total = len(image)
        step = 96 * 1024
        chunks = (total + step - 1) // step
        dispatch('session/attachment/begin', {'workspacePath': workspace, 'sessionId': session,
            'uploadId': upload_id, 'fileName': 'pockethelm-e2e.jpg', 'mime': 'image/jpeg',
            'totalBytes': total, 'totalChunks': chunks,
            'checksum': 'sha256:' + hashlib.sha256(image).hexdigest()})
        for index in range(chunks):
            dispatch('session/attachment/chunk', {'uploadId': upload_id, 'chunkIndex': index,
                'dataBase64': base64.b64encode(image[index * step:(index + 1) * step]).decode()})
        ref = dispatch('session/attachment/commit', {'uploadId': upload_id})['ref']
        print(f'PASS: two-stage upload committed ref={ref[:44]}', flush=True)
        dispatch('session/send', {'workspacePath': workspace, 'sessionId': session,
            'commandId': f'{upload_id}-send-0001', 'selection': selection, 'guard': provider['guard'],
            'text': PROMPT, 'delivery': 'guide',
            'attachments': [{'ref': ref, 'fileName': 'pockethelm-e2e.jpg', 'mime': 'image/jpeg', 'bytes': total}]})
        deadline = time.monotonic() + 240
        reply = ''
        snapshot = {}
        while time.monotonic() < deadline and not reply:
            time.sleep(4)
            snapshot = dispatch('session/read', {'workspacePath': workspace, 'sessionId': session})
            reply = next((r['text'] for r in reversed(snapshot['items'])
                if r['kind'] == 'assistantText' and r['text'].strip()), '')
        assert reply and reply_mentions_colors(reply), f'model reply does not name both colors: {reply[:200]!r}'
        assert any(r.get('attachments') for r in snapshot['items'] if r['kind'] == 'userInput'), \
            'row projection lost the attachment metadata'
        print(f'PASS: ZCode real session read the image; reply = {reply.strip()[:80]!r}', flush=True)
        # Stop any still-running execution so nothing keeps consuming after the smoke.
        if snapshot.get('executionId'):
            dispatch('session/stop', {'workspacePath': workspace, 'sessionId': session,
                'commandId': f'{upload_id}-stop-00001', 'executionId': snapshot['executionId']})
        # The session stays in the smoke workspace (the bridge exposes no deletion);
        # its artifacts are session-scoped and reclaimed by the server's retention.
        print(f'NOTE: ZCode test session {session} left in smoke workspace (no delete API)', flush=True)
    finally:
        try:
            proc.stdin.close()
            proc.wait(timeout=10)
        except Exception as e:
            print(f'WARN bridge shutdown: {type(e).__name__}', flush=True)


if __name__ == '__main__':
    target = sys.argv[1] if len(sys.argv) > 1 else 'both'
    if target in ('codex', 'both'):
        codex_image_smoke()
    if target in ('zcode', 'both'):
        zcode_image_smoke()
