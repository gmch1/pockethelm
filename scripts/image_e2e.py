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


def wait_until(read, terminal, timeout=60):
    deadline = time.monotonic() + timeout
    while True:
        value = read()
        if terminal(value):
            return value
        if time.monotonic() >= deadline:
            raise TimeoutError('test cleanup did not reach a terminal state')
        time.sleep(0.5)


def cleanup_codex(client, thread, path):
    # Stop verification is a hard gate. After it succeeds, archive and file cleanup
    # are independent: one failure must not prevent the other safe cleanup operation.
    errors = []
    if thread:
        def turns():
            return client.call('thread/turns/list', {'threadId': thread, 'limit': 1,
                'sortDirection': 'desc', 'itemsView': 'notLoaded'}).get('data') or []
        current = turns()
        if current and current[0]['status'] not in ('completed', 'failed', 'interrupted'):
            client.call('turn/interrupt', {'threadId': thread, 'turnId': current[0]['id']})
        wait_until(turns, lambda rows: not rows or rows[0]['status'] in ('completed', 'failed', 'interrupted'))
        try:
            client.call('thread/archive', {'threadId': thread})
            print('CLEANUP: Codex terminal confirmed; archived (not deleted)', flush=True)
        except Exception as error:
            errors.append(('archive', error))
    if path:
        try:
            client.call('fs/remove', {'path': path})
            print('CLEANUP: exact Codex image removed', flush=True)
        except Exception as error:
            errors.append(('fs/remove', error))
    if errors:
        stages = ', '.join(f'{stage}: {type(error).__name__}' for stage, error in errors)
        raise RuntimeError(f'Codex cleanup incomplete ({stages})') from errors[0][1]


def codex_image_smoke(model):
    from probe import Client
    client = Client(); thread = None; path = None
    try:
        import uuid
        path = f'/tmp/pockethelm-img-e2e-{uuid.uuid4().hex}.png'
        client.call('fs/writeFile', {'path': path, 'dataBase64': base64.b64encode(png_two_colors()).decode()})
        thread = client.call('thread/start', {'cwd': '/tmp', 'model': model,
            'approvalPolicy': 'never', 'sandbox': 'danger-full-access'})['thread']['id']
        client.call('thread/name/set', {'threadId': thread, 'name': '[测试] PocketHelm image attachment'})
        client.call('turn/start', {'threadId': thread, 'model': model,
            'input': [{'type': 'text', 'text': PROMPT}, {'type': 'localImage', 'path': path, 'detail': 'auto'}],
            'approvalPolicy': 'never', 'sandboxPolicy': {'type': 'dangerFullAccess'}})
        rows = wait_until(lambda: client.call('thread/turns/list', {'threadId': thread, 'limit': 1,
            'sortDirection': 'desc', 'itemsView': 'notLoaded'}).get('data') or [],
            lambda rows: bool(rows) and rows[0]['status'] in ('completed', 'failed', 'interrupted'), 180)
        assert rows[0]['status'] == 'completed'
        items = client.call('thread/items/list', {'threadId': thread, 'limit': 20, 'sortDirection': 'desc'})['data']
        replies = [v['item'].get('text', '') for v in items if v['item'].get('type') == 'agentMessage']
        assert any(reply_mentions_colors(reply) for reply in replies), 'model did not identify the image'
        print('PASS: Codex model identified image', flush=True)
    finally:
        try:
            cleanup_codex(client, thread, path)
        finally:
            client.close()


def native_session_command(bridge, target, kind, payload):
    import uuid
    ack = bridge.agent('sendConversationCommandV4', dict(
        {k: v for k, v in target.items() if k != 'sessionId'}, envelope={
            'commandId': str(uuid.uuid4()), 'clientId': bridge.client_id,
            'sessionId': target['sessionId'], 'type': kind, 'payload': payload,
            'issuedAt': int(time.time() * 1000)}))
    if ack.get('status') != 'accepted':
        raise RuntimeError(f'{kind} was not accepted: {ack.get("reasonCode")}')
    return ack


def cleanup_zcode(bridge, target):
    def read():
        return bridge.dispatch('session/read', target)
    current = read()
    if current.get('executionId'):
        native_session_command(bridge, target, 'stop',
            {'expectedForegroundExecutionId': current['executionId']})
    wait_until(read, lambda row: not row.get('executionId') and not row.get('canStop'))
    native_session_command(bridge, target, 'deleteSession', {})
    wait_until(lambda: bridge.projects()['sessions'],
        lambda rows: all(row['taskId'] != target['sessionId'] for row in rows))
    print('CLEANUP: ZCode stopped and native deletion verified', flush=True)


def send_and_name_zcode(bridge, params, title):
    """A new V4 session is deferred until its first send; rename only after admission.
    The first prompt itself is test-marked so the short pre-rename interval is identifiable.
    Never retry the send here: an unknown outcome belongs to cleanup/reconciliation.
    """
    if not title.startswith('[测试]') or not params.get('text', '').startswith('[测试]'):
        raise ValueError('smoke session and first prompt must carry the test marker')
    ack = bridge.dispatch('session/send', params)
    if ack.get('status') != 'accepted':
        raise RuntimeError(f'session/send was not accepted: {ack.get("reasonCode")}')
    target = {k: params[k] for k in ('workspacePath', 'workspaceIdentity', 'sessionId') if k in params}
    native_session_command(bridge, target, 'renameSession', {'title': title})
    wait_until(lambda: bridge.dispatch('session/read', target), lambda row: row.get('title') == title)
    print('PASS: test session native title verified', flush=True)


def zcode_image_smoke(provider_id, model):
    import hashlib, io, uuid
    from PIL import Image
    sys.path.insert(0, str(Path(__file__).parent / 'zcode'))
    from bridge import Bridge
    from wire import Client
    image_out = io.BytesIO()
    Image.open(io.BytesIO(png_two_colors())).save(image_out, format='JPEG')
    image = image_out.getvalue()
    client = Client(); bridge = Bridge(client); target = None
    try:
        bridge.dispatch('initialize', {'clientId': 'pockethelm-image-e2e-' + uuid.uuid4().hex})
        smoke = next(p for p in bridge.projects()['projects'] if 'zcode-smoke' in p['roots'][0])
        provider = next(p for p in bridge.dispatch('catalog', {})['providers'] if p['id'] == provider_id)
        assert any(m['id'] == model for m in provider['models']), 'verified model is not available'
        selection = {'providerId': provider_id, 'modelId': model, 'options': {'reasoningLevel': 'low'}}
        created = bridge.dispatch('session/create', {'workspacePath': smoke['roots'][0], 'projectId': smoke['id'],
            'commandId': str(uuid.uuid4()), 'selection': selection, 'guard': provider['guard']})
        target = {'workspacePath': smoke['roots'][0], 'sessionId': created['result']['sessionId']}
        upload_id = str(uuid.uuid4()); step = 96 * 1024
        bridge.attachment_begin(dict(target, uploadId=upload_id, fileName='test.jpg', mime='image/jpeg',
            totalBytes=len(image), totalChunks=(len(image)+step-1)//step,
            checksum='sha256:' + hashlib.sha256(image).hexdigest()))
        for index, offset in enumerate(range(0, len(image), step)):
            bridge.attachment_chunk({'uploadId': upload_id, 'chunkIndex': index,
                'dataBase64': base64.b64encode(image[offset:offset+step]).decode()})
        ref = bridge.attachment_commit({'uploadId': upload_id})['ref']
        send_and_name_zcode(bridge, dict(target, commandId=str(uuid.uuid4()), selection=selection,
            guard=provider['guard'], text=PROMPT, delivery='queue',
            attachments=[{'ref': ref, 'fileName': 'test.jpg', 'mime': 'image/jpeg', 'bytes': len(image)}]),
            '[测试] PocketHelm image attachment')
        result = wait_until(lambda: bridge.dispatch('session/read', target),
            lambda row: any(r['kind'] == 'assistantText' and reply_mentions_colors(r.get('text',''))
                for r in row['items']), 240)
        assert any(r.get('attachments') for r in result['items'] if r['kind'] == 'userInput')
        print('PASS: ZCode model identified image', flush=True)
    finally:
        try:
            if target: cleanup_zcode(bridge, target)
        finally:
            for txn in list(bridge.attachments.values()): bridge.attachment_upstream_abort(txn)
            client.close()


if __name__ == '__main__':
    import argparse
    parser = argparse.ArgumentParser(description='Explicit-cost image smoke; no default paid model selection')
    parser.add_argument('engine', choices=['codex', 'zcode'])
    parser.add_argument('--model', required=True)
    parser.add_argument('--provider')
    parser.add_argument('--cost-evidence', type=Path, required=True,
        help='Private JSON with model, provider (ZCode), verifiedAt, source, vision=true, cheapestSuitable=true')
    args = parser.parse_args()
    evidence = json.loads(args.cost_evidence.read_text())
    assert evidence['model'] == args.model and evidence['vision'] is True and evidence['cheapestSuitable'] is True
    assert evidence['verifiedAt'] and evidence['source'], 'verify price/capability before running'
    if args.engine == 'zcode':
        assert args.provider and evidence['provider'] == args.provider
        zcode_image_smoke(args.provider, args.model)
    else:
        codex_image_smoke(args.model)
