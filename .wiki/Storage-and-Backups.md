Jupiter keeps persistent state under:

```text
~/.jupiter
```

The main database is:

```text
~/.jupiter/jupiter.sqlite
```

## What is stored

The database contains projects, workspaces, sessions, messages, tool traces, review state, project settings, MCP
configuration, provider authentication state, Git-update state, lifecycle-hook settings, and token usage.

Sensitive values are encrypted before persistence.

## Backup requirements

A complete recovery requires:

1. everything under `~/.jupiter`
2. the matching Jupiter encryption key

Keep the encryption key separately from the database backup.

## Backing up a running instance

Use a SQLite-aware backup method, or stop Jupiter before copying the database files. It's best to backup everything
under `~/.jupiter`.

## Restore

Restore `~/.jupiter`, and start Jupiter with its original encryption key. A mismatched key is rejected during startup.
