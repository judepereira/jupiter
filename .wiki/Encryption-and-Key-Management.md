# Encryption and Key Management

Jupiter needs one stable 256-bit master key for each database. Keep the same key whenever you start Jupiter against that
database.

## Generate a key

```bash
openssl rand -base64 32
```

The value must be standard Base64 decoding to exactly 32 bytes.

## What the key protects

Jupiter encrypts sensitive persisted values before storing them in SQLite. The key itself is kept out of normal agent,
terminal, and MCP environments.

## Keep it stable and backed up

A wrong key causes startup failure. Losing the key makes encrypted data unrecoverable.

Back up the key separately from the SQLite database.

## Upgrading older databases

Jupiter migrates earlier plaintext persisted values to encrypted storage.

Back up both database and key before upgrading. Older backups, WAL files, or deleted database pages may still contain
plaintext written before migration.

## Docker and native startup

Docker accepts `JUPITER_ENCRYPTION_KEY` during startup and passes the key into Jupiter after trusted initialization
scripts run.

For normal native startup, use the bootstrap mechanism described in [Running Natively](Running-Natively) rather than
placing the key directly in the Java process environment.
