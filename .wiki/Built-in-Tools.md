# Built-in Tools

These are the native tools Jupiter v1 registers for agents.

| Tool | What it does |
|---|---|
| `list_files` | Explore workspace files |
| `read_file` | Read text files |
| `search_code` | Search source with ripgrep |
| `write_file` | Write file content |
| `apply_patch` | Apply structured text patches |
| `display_image` | Show supported workspace images in chat |
| `run_command` | Run a shell command |
| `task` | Delegate to a configured subagent |

MCP tools are added dynamically when an agent has `mcp:*` permission.

## Permissions

An agent only sees tools allowed by its definition. Write and command capability is derived from those tool permissions.

## `run_command`

Commands run through `/bin/sh -c`, with a timeout and cancellation support. There is also a small denylist for a few obviously destructive command strings.

The environment starts empty. Jupiter then copies explicitly allowlisted host variables, overlays project variables, and strips its own sensitive credentials.

Large stdout/stderr is shortened inline; the full output is kept in a temporary file for the agent.

## Is this a filesystem sandbox?

No.

These tools operate on the real filesystem. Path handling normalizes workspace-relative paths, but Jupiter should **not** be treated as enforcing OS-level filesystem containment.

See [Security Model](Security-Model).
