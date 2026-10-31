# Security model

PocketHelm is a personal remote-control client for a privileged development workflow. Valid device credentials can use the development account's Codex capabilities. The YOLO policy is intentional; it is not a multi-tenant sandbox.

## Controls in 0.2.x

- Empty client defaults. Private administrator configuration lives outside the repository and must be owner-only (0600).
- Profile and host keys are delivered through an authenticated, pinned SSH enrollment channel. The phone generates its own key. Administrator keys and Codex login credentials are never distributed.
- Enrollment tickets expire after five minutes and bind one public key. A stolen unbound ticket is a credential: anyone holding it may enroll first.
- Permanent jump access uses a dedicated unprivileged account. Server configuration restricts TCP forwarding to local forwarding to the configured destination, denies remote listeners and Unix socket forwarding, and denies shell, PTY, agent and X11 access.
- Target access uses a forced Codex proxy command. The target operating-system account, not the UI's default project directory, defines the access boundary.
- Durable device intent precedes permanent authorization. Re-pairing retains every recorded target route, including the previous single-target ledger format. All enrollment/revocation operations share a process lock. Retries reconcile grants; revocation tombstones prevent delayed grants from resurrecting a revoked key. The jump entry is removed before all target cleanups are attempted. An offline target leaves the device in `revoking` without preventing other targets from being cleaned up; `revoked` requires success on every target.
- Release builds are not debuggable. Signing inputs and actual device artifacts are excluded from the source tree. The existing private installation certificate is retained for upgrade compatibility; certificate subject text does not determine whether an APK is debuggable.
- Device private keys and cached configuration are encrypted using Android Keystore AES-GCM with field-bound AAD. Backup and device-transfer extraction are disabled. This does not protect a running app from a fully compromised device.
- Legacy v1 generated device credentials are retired on upgrade; imported manual SSH credentials are not removed. Old and new ticket versions are not interchangeable.

## Validation

Automated tests exercise grant write failures before/after persistence, re-pairing across targets, partial re-pairing, offline target revocation without skipping reachable targets, missing registry recovery, concurrent enrollment/revocation, and attempts to reuse revoked public keys. Live validation must additionally verify allowed target access, rejection of an unrelated target, rejection of reverse forwarding, rejection of shell commands, and failed authentication after revocation.

Target routes are SSH aliases and helper paths: retain the old alias mapping until its grants are revoked. Repointing the same alias cannot identify the old machine. Historical routes already erased by an older release cannot be reconstructed from the remaining device record and require administrator cleanup.

Release checks must inspect the compiled manifest (`debuggable=false`), certificate identity, production APK contents and fresh-install behavior. Debug-only instrumentation tests are not a substitute for the final release checks.

No claim is made that all dependency vulnerabilities, all Android versions, or the upstream Codex runtime have been exhaustively audited. Existing SSH sessions and running tasks are not terminated by removing an authorized key.

## Publishing

Never publish deployment configuration, enrollment QR/ticket files, private keys, screenshots containing real projects, personal test logs or store-upload credentials. Use the checked-in example configuration and synthetic demo data. The public repository is https://github.com/gmch1/pockethelm and starts from a reviewed source snapshot; earlier local history and backups are not published. No dedicated external vulnerability disclosure address has been established. Do not include credentials or private deployment details in public issues.
