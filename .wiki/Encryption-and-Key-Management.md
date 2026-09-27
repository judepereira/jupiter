Jupiter needs one stable 256-bit master key for its database. Keep the same key whenever you start Jupiter against that
database.

## Generate a Key

```bash
openssl rand -base64 32
```

The value must be standard Base64 decoding to exactly 32 bytes.

## What the Key Protects

Jupiter encrypts sensitive persisted values before storing them in SQLite. The key itself is kept out of normal agent,
terminal, and MCP environments.

## Keep It Stable and Backed Up

A wrong key causes startup failure. Losing the key makes encrypted data unrecoverable.

Back up the key separately from the SQLite database.

## Docker Startup

Docker accepts `JUPITER_ENCRYPTION_KEY` during startup, and passes the key into Jupiter after trusted initialization
scripts run.
