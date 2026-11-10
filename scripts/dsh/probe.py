#!/usr/bin/env python3
"""P0 protocol probe for the DeepSeek dsh ACP profile (locked: dsh 0.2.2-rc.1).

Spawns `dsh --profile acp` against an isolated DSH_HOME and walks the ACP v1
surface step by step, recording a line-delimited wire transcript plus a
per-step result summary. Protocol-shape steps run without credentials; real
model turns require --run-turn and a usable provider login in the probe home.

Evidence layout (default ~/.local/state/pockethelm/dsh/probe-<timestamp>/):
  wire.jsonl              every raw dsh stdout line and every request we sent
  dsh-stderr.log          raw dsh stderr
  dsh-version.txt         output of `dsh --version`
  initialize-result.json  full initialize result
  session-new-result.json full session/new result of the first session
  summary.json            machine-readable step results
Exit status: 0 only when no step failed. Negative steps (invalid cursor,
cwd mismatch, missing provider) encode their expectation inside the step
function: the server rejecting the input is the passing path, so a ProbeFailure
from their require() calls is always a real failure.
"""
import argparse
import json
import os
import shutil
import subprocess
import sys
import threading
import time
from pathlib import Path

PROTOCOL_VERSION = 1
STEP_TIMEOUT = 30.0
QUIET_WINDOW = 4.0
PROMPT_TIMEOUT = 120.0
EXPECTED_CONCURRENT_PROMPT_ERROR = "a prompt is already in flight for this session"
EXPECTED_RESUME_CWD_ERROR = "session cwd does not match"
LIST_ITEM_FORBIDDEN_KEYS = ("createdAt", "title", "lastModified", "mtime", "updatedAt")


class ProbeFailure(Exception):
    pass


class AcpClient:
    """Newline-framed JSON-RPC 2.0 client over one dsh acp subprocess."""

    def __init__(self, argv, env, cwd, stderr_path):
        self.stderr_file = stderr_path.open("wb")
        self.proc = subprocess.Popen(argv, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
            stderr=self.stderr_file, env=env, cwd=str(cwd), text=True, bufsize=1)
        self._lock = threading.Lock()
        # Guards raw_log writes and stdin writes, both shared by the reader
        # thread (server-request replies) and request callers.
        self._io_lock = threading.Lock()
        self._next_id = 0
        self._pending = {}
        self.notifications = []
        self.unexpected_server_requests = []
        self.raw_log = None
        self._reader = threading.Thread(target=self._read_loop, daemon=True)
        self._reader.start()

    def _read_loop(self):
        for line in self.proc.stdout:
            line = line.rstrip("\n")
            self._record("in", line)
            try:
                message = json.loads(line)
            except ValueError:
                self.notifications.append({"_malformed": line})
                continue
            if "method" in message and "id" in message:
                self.unexpected_server_requests.append(message)
                self._send_raw(json.dumps({"jsonrpc": "2.0", "id": message["id"],
                    "error": {"code": -32601, "message": "probe does not implement this method"}}))
            elif "method" in message:
                self.notifications.append(message)
            elif "id" in message:
                with self._lock:
                    box = self._pending.get(message["id"])
                if box is not None:
                    box.append(message)
        # EOF: dsh closed stdout.

    def _record(self, direction, line):
        if self.raw_log is not None:
            with self._io_lock:  # plain Lock: callers must not hold it here
                self.raw_log.write(json.dumps({"dir": direction, "ts": round(time.time(), 3), "line": line}) + "\n")
                self.raw_log.flush()

    def _send_raw(self, line):
        frame = json.dumps({"dir": "out", "ts": round(time.time(), 3), "line": line})
        with self._io_lock:
            if self.raw_log is not None:
                self.raw_log.write(frame + "\n")
                self.raw_log.flush()
            self.proc.stdin.write(line + "\n")
            self.proc.stdin.flush()

    def request(self, method, params, timeout=STEP_TIMEOUT):
        with self._lock:
            self._next_id += 1
            message_id = self._next_id
            box = []
            self._pending[message_id] = box
        self._send_raw(json.dumps({"jsonrpc": "2.0", "id": message_id,
            "method": method, "params": params}))
        deadline = time.monotonic() + timeout
        while not box and time.monotonic() < deadline:
            time.sleep(0.02)
        with self._lock:
            self._pending.pop(message_id, None)
        if not box:
            raise ProbeFailure(f"{method}: no response within {timeout}s")
        return box[0]

    def notifications_for(self, session_id, since=0):
        picked = []
        for index, note in enumerate(self.notifications[since:], start=since):
            params = note.get("params") or {}
            if "_malformed" not in note and params.get("sessionId") == session_id:
                picked.append((index, note))
        return picked

    def wait_for_update(self, session_id, timeout=STEP_TIMEOUT, since=0):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            for index, note in self.notifications_for(session_id, since=since):
                return index, (note.get("params") or {}).get("update") or {}
            time.sleep(0.02)
        raise ProbeFailure(f"no session/update for {session_id} within {timeout}s")

    def close_stdin(self):
        try:
            self.proc.stdin.close()
        except OSError:
            pass

    def wait_exit(self, timeout=30.0):
        try:
            return self.proc.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            self.proc.kill()
            return "killed-after-timeout"

    def close(self):
        self.close_stdin()
        self.proc.kill()
        self.proc.wait(timeout=10)
        self.stderr_file.close()


def result_payload(response):
    if "error" in response:
        return None, response["error"]
    return response.get("result"), None


class Probe:
    def __init__(self, args):
        self.args = args
        self.evidence = Path(args.evidence_dir)
        self.evidence.mkdir(parents=True, exist_ok=True)
        self.home = Path(args.home)
        self.workspace = self.evidence / "workspace"
        self.other_workspace = self.evidence / "other-workspace"
        self.workspace.mkdir(parents=True, exist_ok=True)
        self.other_workspace.mkdir(parents=True, exist_ok=True)
        self.raw_log = (self.evidence / "wire.jsonl").open("w")
        self.results = []
        self.client = None
        self.sessions = {}
        self.config_options = None

    # -- step harness ---------------------------------------------------
    def step(self, name):
        def wrap(fn):
            started = time.monotonic()
            try:
                detail = fn()
                status = "ok"
            except ProbeFailure as failure:
                detail = str(failure)
                status = "error"
            except Exception as unexpected:  # keep probing; record and continue
                detail = f"{type(unexpected).__name__}: {unexpected}"
                status = "error"
            elapsed = round(time.monotonic() - started, 2)
            self.results.append({"step": name, "status": status, "seconds": elapsed, "detail": detail})
            print(f"[{status:>14}] {name} ({elapsed}s): {json.dumps(detail, ensure_ascii=False, default=str)[:200]}")
            return self.results[-1]
        return wrap

    def run(self):
        self.step("dsh-version")(self._version)
        self.step("boot")(self._boot)
        self.step("initialize")(self._initialize)
        self.step("session-new-x3")(self._new_three)
        self.step("list-hides-active")(self._list_hides_active)
        self.step("invalid-cursor-rejected")(self._invalid_cursor)
        self.step("set-config-option")(self._set_config)
        self.step("close-then-listed")(self._close_then_listed)
        self.step("list-item-shape")(self._list_item_shape)
        self.step("resume-no-replay")(self._resume_no_replay)
        self.step("resume-cwd-mismatch")(self._resume_cwd_mismatch)
        if self.args.run_turn:
            self.step("prompt-real-turn")(self._prompt_real)
            self.step("concurrent-prompt-observation")(self._concurrent_prompt)
        else:
            self.step("prompt-no-provider")(self._prompt_no_provider)
        self.step("close-all-then-listed")(self._close_all_listed)
        if self.args.page_size is not None:
            self.step("list-paging")(self._list_paging)
        self.step("stdin-eof-exit")(self._eof_exit)
        failed = [r for r in self.results if r["status"] == "error"]
        summary = {"dsh": self.args.dsh, "home": str(self.home), "runTurn": self.args.run_turn,
            "results": self.results, "failed": len(failed)}
        (self.evidence / "summary.json").write_text(json.dumps(summary, indent=2, ensure_ascii=False) + "\n")
        self.raw_log.close()
        if self.client is not None and self.client.proc.poll() is None:
            self.client.close()
        print(f"\nevidence: {self.evidence}\nfailed steps: {len(failed)}")
        return 1 if failed else 0

    # -- steps ----------------------------------------------------------
    def _version(self):
        out = subprocess.run([self.args.dsh, "--version"], capture_output=True, text=True, timeout=20)
        (self.evidence / "dsh-version.txt").write_text(out.stdout + out.stderr)
        require(out.returncode == 0, f"dsh --version failed: {out.stderr[:200]}")
        return out.stdout.strip()

    def _boot(self):
        if self.home.exists() and not self.args.keep_home:
            shutil.rmtree(self.home)
        self.home.mkdir(parents=True, exist_ok=True)
        os.chmod(self.home, 0o700)
        env = os.environ.copy()
        env["DSH_HOME"] = str(self.home)
        self.client = AcpClient([self.args.dsh, "--profile", "acp"], env,
            self.workspace, self.evidence / "dsh-stderr.log")
        self.client.raw_log = self.raw_log
        # Bounded early-exit window for a clear diagnostic; process readiness is
        # gated by the initialize response, not by a fixed boot sleep.
        deadline = time.monotonic() + 2.0
        while time.monotonic() < deadline and self.client.proc.poll() is None:
            time.sleep(0.05)
        require(self.client.proc.poll() is None, "dsh exited during boot, see dsh-stderr.log")
        return f"pid {self.client.proc.pid}"

    def _initialize(self):
        response = self.client.request("initialize", {
            "protocolVersion": PROTOCOL_VERSION, "clientCapabilities": {}})
        result, error = result_payload(response)
        require(error is None, f"initialize rejected: {error}")
        require(result.get("protocolVersion") == PROTOCOL_VERSION,
            f"protocolVersion mismatch: {result.get('protocolVersion')}")
        text = json.dumps(result)
        hints = {key: (key in text) for key in ("session/delete", "session/fork", "session/list", "image", "authMethod")}
        (self.evidence / "initialize-result.json").write_text(json.dumps(result, indent=2, ensure_ascii=False) + "\n")
        return {"protocolVersion": result.get("protocolVersion"), "surfaceHints": hints,
            "serverInfo": result.get("serverInfo")}

    def _new_three(self):
        created = []
        for index in range(3):
            response = self.client.request("session/new", {"cwd": str(self.workspace), "mcpServers": []})
            result, error = result_payload(response)
            require(error is None, f"session/new #{index} rejected: {error}")
            session_id = result.get("sessionId")
            require(bool(session_id), f"session/new #{index} returned no sessionId")
            created.append(session_id)
            if index == 0:
                self.config_options = result.get("configOptions")
                (self.evidence / "session-new-result.json").write_text(
                    json.dumps(result, indent=2, ensure_ascii=False) + "\n")
        self.sessions = {"s1": created[0], "s2": created[1], "s3": created[2]}
        return {"created": created}

    def _list_once(self):
        response = self.client.request("session/list", {})
        result, error = result_payload(response)
        require(error is None, f"session/list rejected: {error}")
        return result

    def _listed_ids(self):
        ids, cursor = [], None
        while True:
            params = {} if cursor is None else {"cursor": cursor}
            response = self.client.request("session/list", params)
            result, error = result_payload(response)
            require(error is None, f"session/list rejected: {error}")
            ids.extend(item.get("sessionId") for item in (result or {}).get("sessions", []))
            cursor = (result or {}).get("nextCursor")
            if cursor is None:
                return ids
            require(len(ids) < 500, "session/list paging did not terminate")

    def _close(self, tag):
        response = self.client.request("session/close", {"sessionId": self.sessions[tag]})
        _, error = result_payload(response)
        require(error is None, f"session/close {tag} rejected: {error}")

    def _list_hides_active(self):
        listed = self._listed_ids()
        leaked = [tag for tag, sid in self.sessions.items() if sid in listed]
        require(not leaked, f"active sessions visible in list: {leaked}")
        return {"visible": listed}

    def _invalid_cursor(self):
        response = self.client.request("session/list", {"cursor": "!!bad!!"})
        _, error = result_payload(response)
        require(error is not None, "invalid cursor was accepted")
        return error

    @staticmethod
    def _options_list(result):
        """session/new and set_config_option both wrap the option list in configOptions."""
        if isinstance(result, dict):
            return result.get("configOptions") or []
        return result or []

    def _set_config(self):
        s1 = self.sessions["s1"]
        since = len(self.client.notifications)
        options = self.config_options or []
        model_option = next((o for o in options if o.get("id") == "model"), None)
        require(model_option is not None, "no model config option in session/new result")
        current = model_option.get("currentValue")
        require(bool(current), f"model option lacks currentValue: {model_option}")
        choices = [choice["value"] for group in model_option.get("options", [])
            for choice in group.get("options", [])]
        alternative = next((value for value in choices if value != current), None)
        detail = {"currentValue": current, "choiceCount": len(choices), "reasoningOption": None}
        if alternative is not None:
            try:
                response = self.client.request("session/set_config_option",
                    {"sessionId": s1, "configId": "model", "value": alternative})
                result, error = result_payload(response)
                require(error is None, f"set_config_option(model={alternative}) rejected: {error}")
                after_model = next((o for o in self._options_list(result)
                    if o.get("id") == "model"), {})
                require(after_model.get("currentValue") == alternative,
                    f"model switch not reflected: current={after_model.get('currentValue')!r}")
                detail["switchedTo"] = alternative
            finally:
                response = self.client.request("session/set_config_option",
                    {"sessionId": s1, "configId": "model", "value": current})
                _, error = result_payload(response)
                require(error is None, f"set_config_option(back to {current}) rejected: {error}")
        reasoning = next((o for o in options if o.get("id") == "reasoning_effort"), None)
        if reasoning is not None:
            detail["reasoningOption"] = {"currentValue": reasoning.get("currentValue"),
                "values": [choice["value"] for choice in reasoning.get("options", [])]}
        try:
            self.client.wait_for_update(s1, timeout=QUIET_WINDOW, since=since)
            detail["configUpdateNotification"] = True
        except ProbeFailure:
            detail["configUpdateNotification"] = False
        return detail

    def _close_then_listed(self):
        self._close("s3")
        listed = self._listed_ids()
        require(self.sessions["s3"] in listed, "closed session did not reappear in list")
        require(self.sessions["s1"] not in listed and self.sessions["s2"] not in listed,
            "still-active sessions leaked into list")
        return {"visible": listed, "returnedOrder": listed}

    def _list_item_shape(self):
        response = self.client.request("session/list", {})
        result, _ = result_payload(response)
        items = (result or {}).get("sessions", [])
        require(items, "list empty, cannot verify item shape")
        leaked = sorted({key for item in items for key in item if key in LIST_ITEM_FORBIDDEN_KEYS})
        require(not leaked, f"list items expose forbidden keys: {leaked}")
        keys = sorted({key for item in items for key in item})
        return {"itemKeys": keys, "count": len(items), "nextCursor": (result or {}).get("nextCursor")}

    def _resume_no_replay(self):
        s2 = self.sessions["s2"]
        self._close("s2")
        since = len(self.client.notifications)
        response = self.client.request("session/resume",
            {"sessionId": s2, "cwd": str(self.workspace), "mcpServers": []})
        result, error = result_payload(response)
        require(error is None, f"session/resume rejected: {error}")
        require("configOptions" in (result or {}), f"resume result lacks configOptions: {sorted(result or {})}")
        time.sleep(QUIET_WINDOW)
        replayed = self.client.notifications_for(s2, since=since)
        require(not replayed, f"resume replayed {len(replayed)} updates")
        return {"configOptionsIds": [o.get("id") for o in (result or {}).get("configOptions", [])],
            "replayed": 0}

    def _resume_cwd_mismatch(self):
        s2 = self.sessions["s2"]
        self._close("s2")  # failed resume path closes the agent; close again first for a clean state
        response = self.client.request("session/resume",
            {"sessionId": s2, "cwd": str(self.other_workspace), "mcpServers": []})
        _, error = result_payload(response)
        require(error is not None, "resume with wrong cwd was accepted")
        text = json.dumps(error)
        require(EXPECTED_RESUME_CWD_ERROR in text, f"unexpected mismatch error: {error}")
        return error

    def _prompt_text(self, session_id, text, timeout):
        response = self.client.request("session/prompt",
            {"sessionId": session_id, "prompt": [{"type": "text", "text": text}]}, timeout=timeout)
        return result_payload(response)

    def _prompt_no_provider(self):
        result, error = self._prompt_text(self.sessions["s1"], "回复 ok", PROMPT_TIMEOUT)
        if error is None:
            return {"note": "unexpectedly accepted; probe home appears to have usable credentials",
                "result": result}
        return error

    def _prompt_real(self):
        s1 = self.sessions["s1"]
        since = len(self.client.notifications)
        result, error = self._prompt_text(s1, "回复 ok", PROMPT_TIMEOUT)
        require(error is None, f"prompt rejected: {error}")
        kinds = []
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline:
            try:
                index, update = self.client.wait_for_update(s1, timeout=3, since=since)
                kinds.append(update.get("sessionUpdate"))
                since = index + 1
            except ProbeFailure:
                break
        return {"promptResult": result, "updateKinds": kinds}

    def _concurrent_prompt(self):
        s1 = self.sessions["s1"]
        first_result = []
        first = threading.Thread(
            target=lambda: first_result.append(self._prompt_text(s1, "从一数到十，每数一个停顿一秒", PROMPT_TIMEOUT)))
        first.start()
        time.sleep(2.0)
        _, error = self._prompt_text(s1, "hi", 10)
        first.join(timeout=PROMPT_TIMEOUT)
        if error is None:
            # The first turn may have settled within the sleep window; the rejection
            # itself is asserted against a fake server in the P2 JVM tests.
            return {"note": "first prompt settled before the second was sent; rejection not exercised"}
        require(EXPECTED_CONCURRENT_PROMPT_ERROR in json.dumps(error),
            f"unexpected concurrent error: {error}")
        return {"secondPromptError": error}

    def _close_all_listed(self):
        self._close("s1")
        listed = self._listed_ids()
        missing = [tag for tag, sid in self.sessions.items() if sid not in listed]
        require(not missing, f"closed sessions missing from list: {missing}")
        return {"visible": listed, "returnedOrder": listed}

    def _list_paging(self):
        page_size = self.args.page_size
        seen, pages, cursor = [], 0, None
        while True:
            params = {} if cursor is None else {"cursor": cursor}
            response = self.client.request("session/list", params)
            result, error = result_payload(response)
            require(error is None, f"session/list with cursor rejected: {error}")
            items = (result or {}).get("sessions", [])
            require(len(items) <= page_size, f"page returned {len(items)} items, page size {page_size}")
            seen.extend(item.get("sessionId") for item in items)
            pages += 1
            cursor = (result or {}).get("nextCursor")
            if cursor is None:
                break
            require(pages < 50, "cursor paging did not terminate")
        require(len(seen) == len(set(seen)), f"duplicate session ids across pages: {seen}")
        require(set(seen) >= set(self.sessions.values()), f"paging missed created sessions: {seen}")
        return {"pages": pages, "idsInOrder": seen}

    def _eof_exit(self):
        self.client.close_stdin()
        code = self.client.wait_exit(timeout=30)
        return {"exitCode": code}


def require(condition, message):
    if not condition:
        raise ProbeFailure(message)


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--dsh", default=shutil.which("dsh") or "dsh", help="dsh executable path")
    parser.add_argument("--home", default=str(Path.home() / ".local/state/pockethelm/dsh/probe-home"),
        help="isolated DSH_HOME for the probe (fresh unless --keep-home)")
    parser.add_argument("--evidence-dir", default=None, help="evidence output directory")
    parser.add_argument("--keep-home", action="store_true", help="reuse the existing probe home")
    parser.add_argument("--run-turn", action="store_true",
        help="send real prompts (requires usable provider login in the probe home)")
    parser.add_argument("--page-size", type=int, default=None,
        help="write an acp profile patch with this sessionListPageSize and verify keyset paging; requires --keep-home")
    parser.add_argument("--provider", default=None,
        help="configure this llm provider route in the probe profile (requires --keep-home)")
    parser.add_argument("--model", default=None,
        help="model id to select for --provider; requires --provider")
    args = parser.parse_args()
    if args.evidence_dir is None:
        stamp = time.strftime("%Y%m%d-%H%M%S")
        args.evidence_dir = str(Path.home() / f".local/state/pockethelm/dsh/probe-{stamp}")
    if (args.page_size is not None or args.provider is not None) and not args.keep_home:
        parser.error("--page-size/--provider require --keep-home so the profile patch survives boot")
    if (args.model is None) != (args.provider is None):
        parser.error("--model requires --provider and vice versa")
    if args.page_size is not None or args.provider is not None:
        # User-layer patch entries REPLACE the whole entry config, including the
        # shipped provider/model defaults (verified against dsh 0.2.2-rc.1), so any
        # acp config we set must be complete.
        page_size = args.page_size or 100
        provider = args.provider or "deepseek-official"
        model = args.model or "deepseek-v4-flash"
        patch_dir = Path(args.home) / "profiles/acp"
        patch_dir.mkdir(parents=True, exist_ok=True)
        lines = []
        if provider != "deepseek-official":
            lines += [
                "- id: llm-pi-ai",
                '  name: "@deepseek-ai/dsh-llm-pi-ai"',
                "  config:",
                "    providers:",
                f"      {provider}:",
                f"        apiKeyEnv: {provider.upper().replace('-', '_')}_API_KEY",
            ]
        lines += [
            "- id: acp",
            '  name: "@deepseek-ai/dsh-acp"',
            "  config:",
            f"    provider: {provider}",
            f"    model: {model}",
            f"    sessionListPageSize: {page_size}",
        ]
        (patch_dir / "cordis.patch.yml").write_text("\n".join(lines) + "\n")
    sys.exit(Probe(args).run())


if __name__ == "__main__":
    main()
