Jupiter is powerful software running next to real source code. Its protections reduce accidental credential exposure,
but they do not turn coding agents or the terminal into a general-purpose sandbox.







## What Jupiter protects

- Sensitive persisted values are encrypted using the instance encryption key.
- The encryption key is not exposed to the integrated terminal, MCP templates, or agent commands.
- Agent `run_command` receives only explicitly allowlisted host variables plus project variables.
- Managed child processes such as Git commands and lifecycle hooks do not automatically inherit Jupiter's
  HTTP-authentication credentials or encryption key.
- Optional HTTP Basic authentication protects every route except `GET /health`.
- Agent-displayed images are served with MIME-sniffing and caching protections.

## Process and credential boundaries

Different process types intentionally receive different environments.

Agent commands are the most restricted: they start from an empty environment, receive allowlisted host variables, then
project variables.

The integrated terminal is user-controlled and therefore receives a broader environment. It can receive bootstrap
runtime variables, including HTTP-authentication credentials, but never the encryption key.

Container initialization scripts are trusted setup code and receive the original environment, including the encryption
key. Treat those scripts accordingly.

These boundaries protect Jupiter's own sensitive credentials. They do not discover every unrelated secret already
present in the host environment. For example, an `OPENAI_API_KEY` in a broadly inherited environment is not
automatically removed.

## What Jupiter does not guarantee

There is no OS-level filesystem sandbox around agent tools.

The integrated terminal is a real shell running with the permissions of the Jupiter OS user.

Jupiter does not provide per-user authorization, per-project ACLs, container-per-agent isolation, or network-egress
controls.

## Deployment implications

Only give Jupiter access to users you trust with development access on that host.

For stronger isolation, run Jupiter inside a VM, container, or dedicated host designed around the repositories and
credentials it may reach.

See [Remote Deployment](Remote-Deployment) and [Encryption and Key Management](Encryption-and-Key-Management).
