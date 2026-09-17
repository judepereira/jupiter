# MCP Servers

Jupiter can attach remote MCP servers and expose their tools only to the projects you choose.

![MCP server settings](images/mcp-servers.png)

## Server catalogue

Each entry has:

- a name
- URL
- enabled state
- request headers
- the projects allowed to use it

The catalogue is global; exposure is project-specific.

## Environment placeholders

URLs and header values can contain placeholders like this:

```text
${env.VARIABLE_NAME}
```

Project environment variables are checked first, with host/runtime environment values available as fallbacks. The
Jupiter encryption key is never available to `${env.NAME}`.

Missing values fail resolution. For secrets, prefer project environment variables rather than hard-coding tokens into
URLs or headers.

## Runtime behaviour

Enabled servers connect for exposed projects. Configuration changes reconnect the affected project integrations.
Connection failures surface as system balloons, and tool-list changes are picked up automatically.

## Tool-name collisions

Tool names must be unique across connected MCP servers. If two servers expose the same effective tool name, Jupiter
reports the collision instead of choosing one silently.
