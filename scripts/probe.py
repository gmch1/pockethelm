#!/usr/bin/env python3
"""Live protocol smoke test. Default is read-only; --run-turn creates only its own test thread.

Uses the same raw WebSocket-over-SSH-proxy transport as Android. Never prints session content or credentials.
"""
import argparse
import base64
import hashlib
import json
import os
import select
import shutil
import struct
import subprocess
import tempfile
import time
import shlex
from pathlib import Path


class Client:
    def __init__(self, via_tencent=False, deployment=None):
        command = ["codex", "app-server", "proxy"]
        if via_tencent:
            from pairing.config import load, DEFAULT_CONFIG
            cfg = load(deployment or DEFAULT_CONFIG)
            remote = "ssh -T -o BatchMode=yes -o StrictHostKeyChecking=yes " + shlex.quote(cfg["targetAlias"]) + " " + shlex.quote("bash -lc 'exec codex app-server proxy'")
            command = ["ssh", "-T", "-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=yes", "-o", "ConnectTimeout=10", cfg["adminSsh"], remote]
        self.process = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, bufsize=0, start_new_session=True)
        self.serial = 0
        self.server_requests = []
        key = base64.b64encode(os.urandom(16)).decode()
        self.process.stdin.write((f"GET / HTTP/1.1\r\nHost: localhost\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n").encode())
        headers = b""
        while not headers.endswith(b"\r\n\r\n"):
            headers += self.read(1)
            assert len(headers) < 16384
        assert headers.startswith(b"HTTP/1.1 101 "), headers
        expected = base64.b64encode(hashlib.sha1((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encode()).digest())
        assert expected.lower() in headers.lower()
        self.call("initialize", {"clientInfo": {"name": "codex_remote_android_smoke", "version": "0.1.0"}, "capabilities": {"experimentalApi": True}})
        self.send({"method": "initialized"})

    def read(self, size):
        data = b""
        while len(data) < size:
            if not select.select([self.process.stdout], [], [], 40)[0]:
                raise TimeoutError("App Server response timed out")
            part = os.read(self.process.stdout.fileno(), size - len(data))
            if not part:
                raise EOFError("Proxy connection closed")
            data += part
        return data

    def frame(self, payload, op=1):
        mask = os.urandom(4)
        n = len(payload)
        header = bytes([128 | op, 128 | n]) if n < 126 else bytes([128 | op, 254]) + struct.pack("!H", n) if n < 65536 else bytes([128 | op, 255]) + struct.pack("!Q", n)
        self.process.stdin.write(header + mask + bytes(v ^ mask[i % 4] for i, v in enumerate(payload)))

    def send(self, value):
        self.frame(json.dumps(value).encode())

    def receive(self):
        chunks = b""
        while True:
            first, second = self.read(2)
            length = second & 127
            if length == 126:
                length = struct.unpack("!H", self.read(2))[0]
            elif length == 127:
                length = struct.unpack("!Q", self.read(8))[0]
            assert length <= 16 * 1024 * 1024
            data = self.read(length)
            op = first & 15
            if op == 9:
                self.frame(data, 10)
            elif op == 8:
                raise EOFError("WebSocket closed")
            elif op in (0, 1):
                chunks += data
                if first & 128:
                    return json.loads(chunks)

    def call(self, method, params):
        self.serial += 1
        self.send({"id": self.serial, "method": method, "params": params})
        while True:
            value = self.pump()
            if value.get("id") == self.serial and "method" not in value:
                if "error" in value:
                    raise RuntimeError(f"{method}: {value['error']}")
                return value["result"]

    def pump(self):
        """Reads one frame; server-initiated requests (id + method) are filed for later."""
        value = self.receive()
        if "method" in value and "id" in value:
            self.server_requests.append(value)
        return value

    def wait_request(self, methods, timeout):
        deadline = time.monotonic() + timeout
        index = 0
        while time.monotonic() < deadline:
            while index < len(self.server_requests):
                value = self.server_requests[index]
                index += 1
                if value.get("method") in methods:
                    return value
            self.pump()
        raise TimeoutError(f"No server request {methods} within {timeout}s")

    def wait_request_polling(self, methods, thread_id, timeout, active_turn=None):
        """Waits for a server request while polling turn status to keep traffic flowing
        and to fail fast if the turn settles without ever asking for approval."""
        deadline = time.monotonic() + timeout
        index = 0
        while time.monotonic() < deadline:
            while index < len(self.server_requests):
                value = self.server_requests[index]
                index += 1
                if value.get("method") in methods:
                    return value
            turns = None
            try:
                turns = self.call("thread/turns/list", {"threadId": thread_id, "limit": 3,
                    "sortDirection": "desc", "itemsView": "notLoaded"})["data"]
            except RuntimeError:
                # "list_turns is not supported yet" happens transiently on fresh threads;
                # keep polling — the server request itself is the authoritative signal.
                pass
            current = turns[0] if turns else None
            if current and current["status"] == "failed":
                raise RuntimeError(f"approval smoke turn failed: {current.get('error')}")
            if current and current["status"] in ("completed", "interrupted") and (
                    active_turn is None or current["id"] == active_turn):
                items = self.call("thread/items/list", {"threadId": thread_id, "limit": 20,
                    "sortDirection": "desc"})["data"]
                summary = "; ".join(f"{v['item'].get('type')}:{v['item'].get('status')}" for v in items[:8])
                raise RuntimeError(f"turn settled ({current['status']}) without an approval request. items=[{summary}]")
            time.sleep(1)
        raise TimeoutError(f"No server request {methods} within {timeout}s")

    def respond(self, request_id, result):
        # Echo the id verbatim: the app-server RequestId may be a string or a number.
        self.send({"id": request_id, "result": result})

    def close(self):
        import signal
        try:
            os.killpg(self.process.pid, signal.SIGTERM)
        except ProcessLookupError:
            pass
        self.process.wait(timeout=10)


def cleanup_approval_thread(thread, via_jump, config):
    """Use an independent connection even when A/B failed or already disconnected."""
    client = Client(via_jump, config)
    try:
        deadline = time.monotonic() + 15
        interrupted = set()
        while True:
            turns = client.call("thread/turns/list", {"threadId": thread, "limit": 10,
                "sortDirection": "desc", "itemsView": "notLoaded"})["data"]
            active = [turn for turn in turns if turn["status"] == "inProgress"]
            if not active:
                break
            for turn in active:
                if turn["id"] not in interrupted:
                    client.call("turn/interrupt", {"threadId": thread, "turnId": turn["id"]})
                    interrupted.add(turn["id"])
            if time.monotonic() >= deadline:
                raise TimeoutError("Approval smoke cleanup did not observe a terminal turn")
            time.sleep(.2)
        client.call("thread/archive", {"threadId": thread})
    finally:
        client.close()


def run_approval_smoke(via_jump, config):
    """Real approval flow against the local app-server, incl. the takeover question.

    Client A starts an on-request turn and receives a genuine command approval request,
    then disconnects WITHOUT answering (the terminal-walked-away case). Client B attaches
    to the same thread and observes whether the still-pending approval is delivered to a
    late client. Either outcome is recorded honestly as PASS (takeover works) or BOUNDARY.
    """
    folder = tempfile.mkdtemp(prefix="codex-remote-approval-")
    client_a = None
    client_b = None
    thread = None
    try:
        client_a = Client(via_jump, config)
        result = client_a.call("thread/start", {"cwd": folder, "approvalPolicy": "untrusted", "sandbox": "read-only"})
        assert result["approvalPolicy"] == "untrusted"
        thread = result["thread"]["id"]
        client_a.call("thread/name/set", {"threadId": thread, "name": "Codex Remote approval takeover smoke"})
        turn = client_a.call("turn/start", {"threadId": thread,
            "input": [{"type": "text", "text": "Run this shell command now with the shell tool: echo PROBE_APPROVAL_TEST. "
                                               "You must actually execute the command; do not merely describe it. "
                                               "After it runs, reply DONE."}],
            "approvalPolicy": "untrusted", "sandboxPolicy": {"type": "readOnly"}})["turn"]
        request = None
        # Let the turn activate before polling: an immediate turns/list can observe a
        # transient pre-activation state (the working sequence waits ~2s first).
        time.sleep(2)
        request = client_a.wait_request_polling(["item/commandExecution/requestApproval"], thread, 120, active_turn=turn["id"])
        params = request["params"]
        print(f"PASS: real approval request on A (kind={params.get('kind')}, id={request['id']!r}, "
              f"command={params.get('command', '')[:60]!r}, decisions={params.get('availableDecisions')})", flush=True)
        # A walks away without answering.
        client_a.close()
        client_a = None

        client_b = Client(via_jump, config)
        client_b.call("thread/resume", {"threadId": thread, "excludeTurns": True,
            "approvalPolicy": "untrusted", "sandbox": "read-only"})
        takeover = None
        try:
            takeover = client_b.wait_request_polling(["item/commandExecution/requestApproval", "serverRequest/resolved"],
                thread, 30)
        except TimeoutError:
            print("BOUNDARY: late client B received neither the pending approval nor a resolved notice "
                  "within 30s — takeover of existing approvals is NOT delivered by this app-server version; "
                  "the App can only answer approvals that arrive while connected.", flush=True)
        if takeover is None:
            # End the stuck turn from B so cleanup can archive the thread.
            turns = client_b.call("thread/turns/list", {"threadId": thread, "limit": 1,
                "sortDirection": "desc", "itemsView": "notLoaded"})["data"]
            active = next((t for t in turns if t["status"] == "inProgress"), None)
            if active:
                client_b.call("turn/interrupt", {"threadId": thread, "turnId": active["id"]})
            return
        if takeover.get("method") == "serverRequest/resolved":
            print("BOUNDARY: the pending approval was not replayed to B (resolved notice only) — "
                  "takeover unsupported; only approvals arriving while connected can be answered.", flush=True)
            return
        print("PASS: late client B received the still-pending approval; answering decline", flush=True)
        client_b.respond(takeover["id"], {"decision": "decline"})
        deadline = time.monotonic() + 90
        while time.monotonic() < deadline:
            turns = client_b.call("thread/turns/list", {"threadId": thread, "limit": 1,
                "sortDirection": "desc", "itemsView": "notLoaded"})["data"]
            if turns and turns[0]["status"] in ("completed", "interrupted", "failed"):
                break
            time.sleep(1)
        else:
            raise TimeoutError("Turn did not settle after the declined approval")
        items = client_b.call("thread/items/list", {"threadId": thread, "limit": 50, "sortDirection": "desc"})["data"]
        for v in items[:8]:
            item = v["item"]
            print(f"  item: {item.get('type')} status={item.get('status')} "
                  f"text={str(item.get('command') or item.get('text') or '')[:60]!r}", flush=True)
        # A declined command does not materialize a commandExecution item: the engine
        # records the refusal on the turn instead, and the agent reports it. Either the
        # item-level status or the agent's own report proves the decision was applied.
        declined = any(v["item"].get("type") == "commandExecution" and v["item"].get("status") == "declined"
            for v in items) or any("approval was rejected" in str(v["item"].get("text", ""))
            for v in items if v["item"].get("type") == "agentMessage")
        assert declined, "declined decision did not reach the turn"
        print("PASS: takeover decline landed; command item status is declined", flush=True)
    finally:
        cleaned = thread is None
        try:
            if thread:
                cleanup_approval_thread(thread, via_jump, config)
                cleaned = True
                print("Archived only the approval smoke thread", flush=True)
        except Exception as e:
            print(f"Approval smoke cleanup failed ({type(e).__name__}); retained thread {thread} and {folder}", flush=True)
            raise
        finally:
            for client in (client_a, client_b):
                if client:
                    try:
                        client.close()
                    except Exception:
                        pass
            if cleaned:
                shutil.rmtree(folder, ignore_errors=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--via-jump", action="store_true")
    parser.add_argument("--config", type=Path)
    parser.add_argument("--root", default=os.getcwd())
    parser.add_argument("--run-turn", action="store_true")
    parser.add_argument("--run-approval", action="store_true")
    args = parser.parse_args()
    if args.run_approval:
        run_approval_smoke(args.via_jump, args.config)
        return
    client = Client(args.via_jump, args.config)
    thread = None
    folder = None
    try:
        assert "data" in client.call("thread/list", {"limit": 1})
        assert "entries" in client.call("fs/readDirectory", {"path": args.root})
        print("PASS: SSH proxy WebSocket, initialize, thread/list, fs/readDirectory", flush=True)
        if not args.run_turn:
            return
        folder = tempfile.mkdtemp(prefix="codex-remote-smoke-")
        result = client.call("thread/start", {"cwd": folder, "approvalPolicy": "never", "sandbox": "danger-full-access"})
        assert result["approvalPolicy"] == "never" and result["sandbox"]["type"] == "dangerFullAccess"
        thread = result["thread"]["id"]
        client.call("thread/name/set", {"threadId": thread, "name": "Codex Remote integration smoke"})
        # Materialize a genuine turn, then verify the native queue and reconnect while it is running.
        turn = client.call("turn/start", {"threadId": thread, "input": [{"type": "text", "text": "Reply exactly CODEX_REMOTE_SMOKE_OK. Do not use tools or edit files."}],
                                            "approvalPolicy": "never", "sandboxPolicy": {"type": "dangerFullAccess"}})["turn"]
        queued = client.call("thread/queue/add", {"threadId": thread, "clientUserMessageId": "android-smoke-queue", "input": [{"type": "text", "text": "Reply exactly QUEUE_OK. Do not use tools or edit files."}]})
        assert queued["queuedSubmission"]["clientUserMessageId"] == "android-smoke-queue"
        client.close()
        client = Client(args.via_jump, args.config)
        result = client.call("thread/resume", {"threadId": thread, "excludeTurns": True, "approvalPolicy": "never", "sandbox": "danger-full-access"})
        assert result["approvalPolicy"] == "never" and result["sandbox"]["type"] == "dangerFullAccess"
        print("PASS: create/resume YOLO, native queue submission, transport reconnect", flush=True)
        deadline = time.monotonic() + 120
        while time.monotonic() < deadline:
            turns = client.call("thread/turns/list", {"threadId": thread, "limit": 5, "sortDirection": "desc", "itemsView": "notLoaded"})["data"]
            if any(t["status"] == "failed" for t in turns):
                raise RuntimeError("Smoke model turn failed")
            if len(turns) >= 2 and all(t["status"] == "completed" for t in turns[:2]):
                break
            time.sleep(1)
        else:
            raise TimeoutError("Native queued turn did not complete")
        items = client.call("thread/items/list", {"threadId": thread, "limit": 80, "sortDirection": "desc"})["data"]
        outputs = [v["item"].get("text", "") for v in items if v["item"]["type"] == "agentMessage"]
        assert any("CODEX_REMOTE_SMOKE_OK" in text for text in outputs)
        assert any("QUEUE_OK" in text for text in outputs)
        print("PASS: both turns completed after disconnect; history contains both results", flush=True)
        last = client.call("turn/start", {"threadId": thread, "input": [{"type": "text", "text": "Run sleep 20 using the shell tool, then reply DONE. Do not edit files."}]})["turn"]
        # turn/start acknowledges submission before the engine necessarily activates it.
        for _ in range(50):
            current = client.call("thread/turns/list", {"threadId": thread, "limit": 1, "sortDirection": "desc", "itemsView": "notLoaded"})["data"]
            if current and current[0]["id"] == last["id"] and current[0]["status"] == "inProgress":
                break
            time.sleep(.1)
        else:
            raise AssertionError("Turn did not become active")
        client.call("turn/interrupt", {"threadId": thread, "turnId": last["id"]})
        for _ in range(20):
            latest = client.call("thread/turns/list", {"threadId": thread, "limit": 1, "sortDirection": "desc", "itemsView": "notLoaded"})["data"][0]
            if latest["status"] == "interrupted":
                print("PASS: interrupt and persisted interrupted status", flush=True)
                break
            time.sleep(.3)
        else:
            raise AssertionError("Interrupted status not observed")
    finally:
        if thread:
            try:
                client.call("thread/archive", {"threadId": thread})
                print("Archived only the smoke test thread", flush=True)
            except Exception as e:
                print(f"Smoke cleanup needs attention: {type(e).__name__}", flush=True)
        client.close()
        if folder:
            shutil.rmtree(folder)


if __name__ == "__main__":
    main()
