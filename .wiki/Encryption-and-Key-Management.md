# Encryption and Key Management

Jupiter needs one stable 256-bit master key for each database. The “stable” part matters just as much as the “256-bit” part.

## Generate a key

```bash
openssl rand -base64 32
```

The input must be standard Base64 that decodes to exactly 32 bytes.

Jupiter’s stdin reader rejects missing input, invalid Base64, interior whitespace, excessive input, and keys that decode to the wrong size.

## What happens to the key?

Jupiter derives separate keys for encryption and blind indexes.

Sensitive text uses AES-256-GCM with a random 12-byte nonce, a 128-bit authentication tag, and associated data tied to the persisted field context.

Where deterministic equality lookup is needed without keeping plaintext, blind indexes use HMAC-SHA-256.

## Don’t rotate it by accident

Use the same key every time you start Jupiter against the same database.

The wrong key causes startup failure. Losing the key makes encrypted data unrecoverable.

Back it up separately from the SQLite database.

## Upgrading older databases

Jupiter includes a one-way migration for earlier plaintext persisted values.

Back up both database and key before upgrading. Historical WAL files, backups, deleted pages, or other old copies can still contain plaintext written before that migration.

## Docker

The Docker entrypoint accepts `JUPITER_ENCRYPTION_KEY` as bootstrap input, removes it before init scripts, then sends it to Java over stdin.
