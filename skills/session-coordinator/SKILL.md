---
name: session-coordinator
description: Reuse and coordinate persistent Codex, ZCode, or other coding-agent sessions when the user asks one AI to drive another, continue delegated work, or hand work across models. Keeps implementation and review follow-ups in the same native session. Does not start delegation for ordinary coding or review tasks.
---

# Persistent coding sessions

Use the user's current division of work. If they ask you to implement, test, or review directly, do it yourself; this skill is not authorization to delegate. Preserve the chosen provider, plan, model, and reasoning setting.

## Resolve before creating

Identify the environment alias, canonical workspace path **on that environment**, engine, and workstream/role. These four fields identify a reusable session. The same role in two independent worktrees is separate. A model or quota-plan change alone does not create a new session identity.

Use `scripts/sessions.py get` to find the recorded native session. The default index lives in `~/.local/state/agent-session-coordinator/sessions.json`, outside the repository; `--state` can override it for tests. It contains session pointers and observed model/plan names, never keys, tokens, full transcripts, or connection strings.

If the index is empty, inspect the engine's existing session list and project task document first. Bind an existing matching workstream when identifiable. Do not pick the most recently used session blindly: it may belong to the user's own work or another controller. An ambiguous candidate is a reason to clarify ownership, not to start multiple replacements.

For ZCode, also save the upstream workspace identity with `bind --workspace-identity VALUE` when present; a path alone need not identify the workspace. The optional `--model` and `--plan` record observed selections, without changing the remote agent. The helper runs on the POSIX controller and uses `flock` to serialize local index updates.

```sh
python3 scripts/sessions.py get --environment dev --project /workspace/app --engine zcode --role implementation
python3 scripts/sessions.py bind --environment dev --project /workspace/app --engine zcode --role implementation --session EXISTING_ID --reason 'Adopt the existing implementation session after checking its task'
```

Paths above are examples. Run the helper relative to this skill directory or use its absolute path. `get` returns exit code 3 for no binding; that result does not itself authorize remote creation.

## Continue one workstream

- Before sending, read that exact session's state and recent tail. Match its environment/workspace and check whether an earlier instruction is still executing. One controller sends at a time; do not race a human or another agent writing to the same session.
- Keep requirements, implementation, review corrections, and verification of the same task in the same session. Send a concise delta and the task-document path rather than repeating the whole project brief.
- For Codex, use the installed app-server's `thread/resume` on the saved thread ID, then its normal turn API. For ZCode, address the saved session with its workspace path and identity through the existing bridge. Consult the project adapter documentation/code for its exact current schema; do not invent generic CLI flags or write the product's database.
- While running, use the engine's existing follow-up queue or steering operation as the user requested. A timeout, reconnect, model change, or review round is not a reason to create another thread. If submission has an unknown outcome, query the command receipt/history using its command ID before any retry.
- Do not start another agent to ask for status. Read status without injecting a prompt. Gather review findings once in the existing task document and send one focused follow-up.
- Prefer the existing model route during a workstream. When the user requests switching, check availability and verify the applied selection. Never silently replace the requested model or change subscription plans. Record observed model/plan metadata in the binding after verification.

## New sessions and cross-model work

Create a new session for a genuinely independent objective, an isolated worktree, or an unrecoverable/unsuitable old context. Record the reason. For a replacement, `bind --replace OLD_ID --reason '…'` checks the previous pointer atomically and retains its history. If creation/dispatch times out, reconcile the native session list/receipt first; do not retry creation blindly. Never delete old conversations to tidy a list.

Across engines, keep a separate native session pointer for each participant under the same task document. Pass a concise handoff: objective, constraints, current commit/diff, decisions, review IDs, validation evidence, and next action. Do not replay every tool log. A provider adapter calling GLM's API is not equivalent to driving ZCode's existing session, login, or subscription plan.

Conversation history and prompt/KV caching are different. Stable prefixes and continued sessions can improve reuse, but cache lifetime and hits are provider-specific; changing models does not transfer KV state. Report savings only from measured usage. Do not force all unrelated projects into one ever-growing conversation to chase a cache.

## Completion

Report task outcome and next action in normal language. Put native IDs, raw events, commands and intermediate logs in the private index or expandable diagnostics. The helper only stores pointers: it neither resumes a remote agent nor enforces a remote execution lock, and a successful bind does not prove the remote session is alive.
