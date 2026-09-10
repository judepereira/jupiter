# Lifecycle Hooks

Lifecycle hooks are small shell scripts Jupiter runs after selected agent events. They’re useful for glue: notifications, local automation, cleanup, and similar jobs.

> TODO: add screenshot

## Available events

v1 has hooks for:

- assistant completed
- assistant errored
- subagent completed

Each event has its own script.

## How scripts run

Jupiter writes the script to a temporary file under `/tmp` and runs it with `/bin/bash`.

Where POSIX permissions are available, Jupiter attempts to make that temporary file owner-readable/writable only.

The hook receives project environment variables plus project, workspace, and session names. Jupiter’s own sensitive credentials are removed before launch.

## Timeouts

Hooks have a configurable timeout.

On timeout or application shutdown, Jupiter terminates the process group and descendants, escalating from TERM to KILL when needed. The temporary script file is deleted afterwards.

## Failures

A non-zero exit, timeout, or launch failure produces a system error balloon.

One thing to keep in mind: a completion hook is **after** the agent turn. If the hook fails, the already-completed turn doesn’t magically become transactional and roll back.
