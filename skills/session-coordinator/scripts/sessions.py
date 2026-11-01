#!/usr/bin/env python3
"""Private native-session pointers; no network calls or remote agent creation."""
import argparse
import fcntl
import json
import os
import posixpath
from pathlib import Path
import tempfile
from datetime import datetime, timezone


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--state", type=Path, default=Path.home() / ".local/state/agent-session-coordinator/sessions.json")
    sub = parser.add_subparsers(dest="command", required=True)
    for command in ("get", "bind"):
        p = sub.add_parser(command)
        for field in ("environment", "project", "engine", "role"):
            p.add_argument("--" + field, required=True)
        if command == "bind":
            p.add_argument("--session", required=True)
            p.add_argument("--reason", required=True)
            p.add_argument("--replace")
            p.add_argument("--model")
            p.add_argument("--plan")
            p.add_argument("--workspace-identity", dest="workspaceIdentity")
    args = parser.parse_args()
    for field in ("environment", "project", "engine", "role"):
        if not getattr(args, field).strip():
            parser.error(field + " must not be blank")
    if not args.project.startswith("/"):
        parser.error("project must be an absolute path on the selected environment")
    # Do not resolve this using local symlinks: it may be a path on a remote host.
    scope = dict(environment=args.environment, project=posixpath.normpath(args.project), engine=args.engine, role=args.role)
    key = json.dumps(scope, sort_keys=True, ensure_ascii=False)
    if args.command == "bind" and (not args.session.strip() or not args.reason.strip()):
        parser.error("session and reason must not be blank")
    args.state.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    lock_fd = os.open(str(args.state) + ".lock", os.O_CREAT | os.O_RDWR, 0o600)
    with os.fdopen(lock_fd, "w") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        data = json.loads(args.state.read_text()) if args.state.exists() else {"version": 1, "sessions": {}}
        if data.get("version") != 1 or not isinstance(data.get("sessions"), dict):
            parser.error("unsupported registry format; refusing to overwrite")
        existing = data["sessions"].get(key)
        if args.command == "get":
            print(json.dumps(existing, ensure_ascii=False))
            return 0 if existing is not None else 3
        old_id = existing.get("session") if existing else None
        if args.replace is not None and args.replace != old_id:
            parser.error("previous pointer changed; inspect the binding before replacing")
        if old_id is not None and old_id != args.session and args.replace != old_id:
            parser.error("binding already exists; reuse it or explicitly replace the previous session")
        history = list(existing.get("history", [])) if existing else []
        if old_id and old_id != args.session:
            history.append({k: v for k, v in existing.items() if k != "history"})
        record = dict(scope, session=args.session, reason=args.reason,
                      updatedAt=datetime.now(timezone.utc).isoformat(), history=history)
        for field in ("model", "plan", "workspaceIdentity"):
            value = getattr(args, field)
            if value is None and existing and old_id == args.session:
                value = existing.get(field)
            if value is not None:
                record[field] = value
        data["sessions"][key] = record
        fd, temp = tempfile.mkstemp(prefix=".sessions-", dir=args.state.parent)
        try:
            with os.fdopen(fd, "w") as out:
                json.dump(data, out, ensure_ascii=False, indent=2)
                out.write("\n")
                out.flush()
                os.fsync(out.fileno())
            os.replace(temp, args.state)
        finally:
            if os.path.exists(temp):
                os.unlink(temp)
        print(json.dumps(record, ensure_ascii=False))
        return 0


if __name__ == "__main__":
    raise SystemExit(main())
