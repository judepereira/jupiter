# Security Model

Jupiter is powerful software running next to real source code. Credentials are kept out of places they don’t belong, but it’s important not to confuse those protections with a sandbox.

## What Jupiter does protect

- Sensitive persisted text is encrypted with authenticated encryption at the repository boundary.
- Normal native startup reads the master key from stdin rather than Java environment variables or arguments.
- Linux startup applies `PR_SET_DUMPABLE=0`; the recommended Java launch also disables JVM attach.
- Jupiter’s encryption and HTTP-auth credentials are removed before managed child processes start. This specific sanitizer does not include `OPENAI_API_KEY`.
- Agent `run_command` only receives allowlisted host variables plus project variables.
- Optional Basic auth protects every route except `GET /health`.
- Agent-displayed images disable MIME sniffing and caching.

## A few extra guardrails

`run_command` has timeouts, cancellation, and a small denylist for a handful of obviously destructive command strings.

Useful? Yes. A general shell sandbox? Definitely not.

## What Jupiter does **not** guarantee

There is no OS-level filesystem sandbox around agent tools.

Workspace paths are normalized, but that should not be treated as a malicious-path containment boundary.

The integrated terminal is a real shell running with the permissions of the Jupiter OS user.

v1 also has no multi-user authorisation, per-project ACLs, container-per-agent isolation, or network-egress controls.

## What this means for deployment

Only give Jupiter access to users you trust with powerful development access on that host.

If you need a stronger boundary, put Jupiter itself inside a VM, container, or dedicated host designed around the repositories and credentials it may reach.

See [Encryption and Key Management](Encryption-and-Key-Management) and [Process and Credential Isolation](Process-and-Credential-Isolation).
