# Storage and Backups

Jupiter keeps its persistent state under:

```text
~/.jupiter
```

The main database is:

```text
~/.jupiter/jupiter.sqlite
```

## SQLite setup

Jupiter enables WAL journaling, foreign keys, and a busy timeout. The application uses a single-connection Hikari pool.

Flyway handles schema migrations at startup.

## What’s actually in the database?

Projects, workspaces, sessions, messages, tool traces, review state, project settings, MCP configuration, OAuth state, Git-update state, lifecycle-hook settings, and token usage.

Sensitive text fields are encrypted at the persistence boundary.

## The backup rule

You need two things:

1. the SQLite database state
2. the matching Jupiter encryption key

A database backup without the key is not a useful recovery plan for encrypted values.

## Backing up a live database

Use a SQLite-aware backup method, or stop Jupiter before copying the database files.

With WAL enabled, copying only `jupiter.sqlite` while the application is active may miss data that is still in the WAL.

## Restore

Restore the database state and start Jupiter with the original key. A mismatched key is rejected during encrypted-state validation.
