# HTTP Authentication

Jupiter has one intentionally simple built-in access gate: HTTP Basic authentication.

## Turn it on

Set a non-empty password:

```bash
JUPITER_HTTP_AUTH_PASSWORD='use-a-strong-secret'
```

The default username is `jupiter`. Change it with:

```bash
JUPITER_HTTP_AUTH_USERNAME='jupiter'
```

## What does it protect?

Everything except exactly:

```text
GET /health
```

That means the UI, static files, error routes, SSE connections, and WebSocket handshakes all require credentials once auth is enabled.

## Comparison behaviour

After decoding the Basic header, Jupiter compares the complete `username:password` value with `MessageDigest.isEqual`.

## Please use HTTPS

Basic authentication is encoding, not transport encryption.

If Jupiter sees Basic auth enabled while the public request still looks like HTTP, it raises a warning in the UI.

## What it is not

This is one shared access gate. v1 has no per-user identities, roles, or project-level authorisation boundaries.
