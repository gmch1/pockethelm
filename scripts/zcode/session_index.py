"""Bounded native sessions-index projections; no task execution or credential fields.

The JSONL adapter remains single-threaded. Foreground status reads pump native
frames via a cheap hello RPC; conversation reads share the same event router.
"""
import time
import uuid
from wire import WireError

PHASES = {'running', 'prewarming', 'waiting', 'completedSuccess', 'completedInterrupted', 'error', 'draft'}
MAX_WORKSPACES = 64
MAX_SESSIONS = 10000


class IndexProjection:
    def __init__(self, target, assembler):
        self.target = target
        self.key = target.get('workspaceIdentity') or target['workspacePath']
        self.assembler = assembler
        self.listener = None
        self.subscription = None
        self.epoch = None
        self.seq = None
        self.sessions = {}
        self.fresh = False
        self.invalid = False
        self.pending = []
        self.retry_at = 0

    def summary(self, value):
        if value.get('workspaceId') != self.key or value.get('phase') not in PHASES:
            raise ValueError('Invalid index summary')
        identity = value.get('sessionId')
        if not isinstance(identity, str) or not 0 < len(identity) <= 1024:
            raise ValueError('Invalid session identity')
        # Preview/tool inputs/account metadata deliberately stay on the host.
        return {'sessionId': identity, 'phase': value['phase']}

    def accept(self, wire):
        if self.invalid:
            return
        if self.subscription is None:
            if len(self.pending) >= 64:
                self.invalidate()
            else:
                self.pending.append(wire)
            return
        if wire.get('subscriptionId') != self.subscription:
            return
        try:
            frame = self.assembler.accept(wire)
            if frame is None:
                return
            if frame['topic'] != 'sessions-index/' + self.key or frame['subscriptionId'] != self.subscription:
                raise ValueError('Wrong subscription')
            start, end = frame['fromSeq'], frame['toSeq']
            if type(start) is not int or type(end) is not int or not 0 <= start <= end:
                raise ValueError('Invalid sequence')
            payload = frame['payload']
            if payload['kind'] == 'snapshot':
                snap = payload['snapshot']
                if snap['workspaceId'] != self.key or snap['logEpoch'] != self.epoch or snap['protocolVersion'] != 1:
                    raise ValueError('Wrong snapshot')
                if self.seq is not None and end < self.seq:
                    return
                sessions = {s['sessionId']: self.summary(s) for s in snap['sessions']}
            elif payload['kind'] == 'deltas':
                if self.seq is None:
                    raise ValueError('Missing snapshot')
                if end <= self.seq:
                    return
                if start != self.seq:
                    raise ValueError('Sequence gap')
                sessions = dict(self.sessions)
                for delta in payload['deltas']:
                    if delta['op'] == 'session.upserted':
                        summary = self.summary(delta['session'])
                        sessions[summary['sessionId']] = summary
                    elif delta['op'] == 'session.removed':
                        sessions.pop(delta['sessionId'], None)
                    else:
                        raise ValueError('Unknown delta')
            else:
                raise ValueError('Unknown index frame')
            if len(sessions) > MAX_SESSIONS:
                raise ValueError('Index too large')
            self.sessions, self.seq, self.fresh = sessions, end, True
        except Exception:
            # Never continue presenting an active task after losing the stream base.
            self.invalidate()

    def invalidate(self):
        self.fresh, self.invalid = False, True
        self.pending.clear()


class SessionIndexes:
    def __init__(self, bridge, assembler_factory):
        self.bridge = bridge
        self.assembler_factory = assembler_factory
        self.targets = {}
        self.entries = {}
        self.lifecycle_listener = None
        self.scope = "pockethelm-index-" + uuid.uuid4().hex

    def set_sessions(self, sessions):
        targets = {}
        for row in sessions:
            path, identity = row.get('workspacePath'), row.get('workspaceIdentity') or ''
            if not isinstance(path, str) or not path.startswith('/') or not isinstance(identity, str):
                continue
            if len(path) > 4096 or len(identity) > 1024:
                continue
            target = {'workspacePath': path}
            if identity:
                target['workspaceIdentity'] = identity
            targets[(path, identity)] = target
        self.targets = dict(list(targets.items())[:MAX_WORKSPACES])
        for key in list(self.entries):
            if key not in self.targets:
                self.close_entry(key)

    def event(self, listener, wire):
        if self.lifecycle_listener is not None and listener == self.lifecycle_listener:
            for entry in self.entries.values():
                if wire.get('workspaceKey') == entry.key:
                    entry.invalidate()
                    entry.retry_at = 0
            return True
        for entry in self.entries.values():
            if entry.listener == listener:
                entry.accept(wire)
                return True
        return False

    def close_entry(self, key):
        entry = self.entries[key]
        # Keep routing/consuming frames until unsubscribe finishes.
        entry.invalidate()
        try:
            if entry.subscription:
                self.bridge.agent('unsubscribeSessionsIndexV4', dict(entry.target, subscriptionId=entry.subscription, runtimePolicy='existing-only'))
        except WireError:
            pass
        finally:
            if entry.listener is not None:
                self.bridge.client.unlisten(entry.listener)
            self.entries.pop(key, None)

    def pause(self):
        for key in list(self.entries):
            self.close_entry(key)
        if self.lifecycle_listener is not None:
            self.bridge.client.unlisten(self.lifecycle_listener)
            self.lifecycle_listener = None
        return {}

    def read(self):
        if self.targets and self.lifecycle_listener is None:
            self.lifecycle_listener = self.bridge.client.listen('zcode-agent', 'onAgentRuntimeLifecycle', None)
        # A reply fences already-delivered native events without loading histories/tree.
        self.bridge.call('zcode-agent', 'helloConversationV4')
        started = 0
        for key, target in self.targets.items():
            entry = self.entries.get(key)
            if entry and not entry.invalid and entry.subscription:
                # A subscribe with no initial snapshot must be retried, never marked live.
                if entry.fresh or time.monotonic() < entry.retry_at:
                    continue
                entry.invalidate()
            if entry and time.monotonic() < entry.retry_at:
                continue
            if started >= 4:
                continue
            started += 1
            if entry:
                self.close_entry(key)
            entry = IndexProjection(target, self.assembler_factory())
            self.entries[key] = entry
            entry.retry_at = time.monotonic() + 15
            entry.listener = self.bridge.client.listen('zcode-agent', 'onDynamicSessionsIndexFrame', target)
            try:
                ack = self.bridge.agent('subscribeSessionsIndexV4', dict(target,
                    visibility='foreground', runtimePolicy='existing-only', subscriberScope=self.scope))['ack']
                entry.subscription, entry.epoch = ack['subscriptionId'], ack['logEpoch']
                pending, entry.pending = entry.pending, []
                for wire in pending:
                    entry.accept(wire)
            except WireError:
                # Dormant/unsupported workspace: no new CLI is started. Retry on later reads.
                entry.invalidate()
        if started:
            self.bridge.call('zcode-agent', 'helloConversationV4')
        return {'workspaces': [dict(target, fresh=bool(entry and entry.fresh),
                sessions=list(entry.sessions.values()) if entry and entry.fresh else [])
            for key, target in self.targets.items() for entry in [self.entries.get(key)]]}
