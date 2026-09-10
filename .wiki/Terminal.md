# Terminal

The terminal in Jupiter is a real PTY, not a textarea pretending to be one.

> TODO: add screenshot

## Shell

Jupiter starts `$SHELL`, falling back to `/bin/bash`, as a login shell.

It starts in the active workspace directory and sets `TERM=xterm-256color`.

## Tabs

Each workspace can have multiple terminal tabs. You can create, switch, and close them independently.

## WebSocket transport

Input, output, and resize events use WebSockets.

The server keeps up to 200,000 characters of recent output so a newly attached browser can replay the tail of a running terminal.

## Environment

The terminal inherits the Jupiter process environment, then overlays project environment variables.

Before launch, Jupiter strips its own encryption and HTTP-authentication credentials.

This is intentionally broader than the environment given to agent `run_command`, because the terminal is directly controlled by the authenticated user. See [Environment Variables](Environment-Variables).
