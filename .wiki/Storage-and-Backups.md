# Storage and Backups

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

A complete recovery requires both:

1. the SQLite database state
2. the matching Jupiter encryption key

Keep the encryption key separately from the database backup.

## Backing up a running instance

Use a SQLite-aware backup method, or stop Jupiter before copying the database files.

Copying only `jupiter.sqlite` while Jupiter is running can miss recently written state.

## Restore

Restore the database and start Jupiter with its original encryption key. A mismatched key is rejected during startup.
