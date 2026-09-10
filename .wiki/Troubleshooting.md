# Troubleshooting

Most Jupiter failures fall into a few predictable buckets. I’d check these before diving into server logs.

## “The encryption key is wrong”

Use the same Base64 key that created or migrated the existing database.

Do **not** generate a fresh key for an existing `~/.jupiter` directory. That creates a new secret, not a clever way to recover the old one.

## “ripgrep is unavailable”

Install `rg` for a native deployment. The Docker image already includes it.

## The browser shows the connection-loss overlay

First, make sure Jupiter itself is still alive. If it is, check reverse-proxy idle timeouts and streaming support.

## Chat streaming breaks behind a proxy

Make sure Server-Sent Events aren’t buffered or killed by a short timeout.

## The terminal doesn’t connect

Check WebSocket upgrade support and make sure authentication reaches the WebSocket handshake.

## Jupiter warns about HTTPS

Basic auth is enabled, but Jupiter thinks the public request is HTTP.

Use HTTPS and forward `Forwarded` or `X-Forwarded-Proto` correctly from the trusted proxy.

## MCP failed

Check the server URL, headers, project exposure, `${env.NAME}` placeholders, and the referenced environment variables.

Also check whether two connected servers are producing the same effective tool name.

## Automatic Git update keeps getting skipped

If the branch has no upstream, Jupiter needs either `origin` or exactly one unambiguous remote with the same branch name.

## Jupiter asks before closing a workspace

That means it found uncommitted changes or unpushed commits. Review the worktree before forcing removal.
