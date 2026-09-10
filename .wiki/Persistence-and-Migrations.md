# Persistence and Migrations

Jupiter uses SQLite for durable state and Flyway to evolve the schema.

## Database

By default the database lives at:

```text
~/.jupiter/jupiter.sqlite
```

WAL mode, foreign keys, and a busy timeout are enabled.

## Schema changes

Flyway migrations cover the project/workspace/session model, messages, tool calls, MCP, usage data, lifecycle hooks, Git-update state, and encryption-related changes.

They run during application startup.

## Where encryption happens

Encryption is handled at the repository boundary.

That means application services work with normal plaintext domain values, while `AppStateRepository` takes care of encrypting and decrypting the persisted fields. I prefer that to leaking ciphertext concerns through every service layer.

## Upgrade caution

The encryption migration for older Jupiter state can be one-way from the point of view of previous application versions.

Back up the database **and** its matching key before upgrading. See [Encryption and Key Management](Encryption-and-Key-Management).
