Jupiter is powerful software running next to real source code. Its protections reduce accidental credential exposure,
but they do not turn coding agents or the terminal into a general-purpose sandbox.

## What Jupiter Protects

- Sensitive persisted values are encrypted using the instance encryption key.
- The encryption key is not exposed to the integrated terminal, MCP templates, or agent commands.
- Agent `run_command` receives only explicitly whitelisted host variables plus project variables.
- Managed child processes such as Git commands and lifecycle hooks do not automatically inherit Jupiter's
  HTTP-authentication credentials or encryption key.
- Optional HTTP Basic authentication protects every route except `GET /health`.
- Agent-displayed images are served with MIME-sniffing and caching protections.

## Process and Credential Boundaries

Different process types intentionally receive different environments.

Agent commands are the most restricted: they start from an empty environment, receive whitelisted host variables, then
project variables.

The integrated terminal is user-controlled and therefore receives a broader environment. It can receive bootstrap
runtime variables, including HTTP-authentication credentials, but never the encryption key.

Container initialization scripts are trusted setup code and receive the original environment, including the encryption
key. Treat those scripts accordingly.

## What Jupiter Does Not Guarantee

There is no OS-level filesystem sandbox around agent tools. This is by design, as there are numerous ways in which an
agent can break out of such imposed limits. Instead, the container provides a global sandbox, while a future network
firewall will protect access to the internet.

The integrated terminal is a real shell running with the permissions of the Jupiter OS user.

## Deployment Implications

Only give Jupiter access to users you trust with development access on that host.

For stronger isolation, run Jupiter inside a VM, container, or dedicated host designed around the repositories and
credentials it may reach. The default approach is to run it via the Docker container.

See [Remote Deployment](Remote-Deployment) and [Encryption and Key Management](Encryption-and-Key-Management).
