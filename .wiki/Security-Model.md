# Security Model

Jupiter is powerful software running next to real source code. Credentials are kept out of places they don’t belong, but
it’s important not to confuse those protections with a sandbox.

## What Jupiter does protect

- Sensitive persisted text is encrypted with authenticated encryption at the repository boundary.
- Normal native startup reads a versioned NUL-delimited bootstrap envelope from an anonymous file descriptor rather than
  Java environment variables or arguments. Runtime-added variables in that envelope are removed from the JVM `/proc`
  environment after being captured for Spring.
- Linux startup applies `PR_SET_DUMPABLE=0`; the recommended Java launch also disables JVM attach.
- Untrusted managed processes, including agent commands, do not automatically inherit Jupiter’s HTTP-auth credentials or
  encryption key. Trusted interactive terminals deliberately restore bootstrap runtime variables, except the key;
  initialization scripts receive the original environment. This specific sanitizer does not include `OPENAI_API_KEY`.
- Agent `run_command` only receives allowlisted host variables plus project variables.
- Optional Basic auth protects every route except `GET /health`.
- Agent-displayed images disable MIME sniffing and caching.

## Runtime bootstrap trust boundary

The container entrypoint treats both root and user initialization scripts as trusted: each receives the complete
original environment, including `JUPITER_ENCRYPTION_KEY`. After those scripts finish, the entrypoint captures
runtime-added variables and unsets them before Java starts. Image-defined variables remain ordinary JVM environment
variables.

The encryption key is then available only to trusted initialization scripts and Jupiter's bootstrap `EncryptionKey`
bean. It is not exposed to the terminal, MCP templates, agent commands, or Spring's property environment. Trusted
terminals restore bootstrap runtime variables except the key. MCP `${env.NAME}` resolves project variables first, then
JVM variables, then bootstrap runtime variables. Agent `run_command` does not inherit bootstrap runtime variables.

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

If you need a stronger boundary, put Jupiter itself inside a VM, container, or dedicated host designed around the
repositories and credentials it may reach.

See [Encryption and Key Management](Encryption-and-Key-Management) and
[Process and Credential Isolation](Process-and-Credential-Isolation).
