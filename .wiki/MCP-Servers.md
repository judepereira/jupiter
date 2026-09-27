Jupiter can attach remote MCP servers and expose their tools only to the projects you choose.

![MCP server settings](images/mcp-servers.png)

## Server Catalogue

Each entry has:

- a name
- URL
- enabled state
- request headers
- the projects allowed to use it

The catalogue is global; exposure is project-specific.

## Environment Placeholders

URLs and header values can contain placeholders like this:

```text
${env.VARIABLE_NAME}
```

Project environment variables are checked first, with host/runtime environment values available as fallbacks.

Missing values fail resolution. For secrets, prefer environment variables rather than hard-coding tokens into URLs or
headers. Either ways, there is no way a rogue agent can get access to these, since they are stored encrypted, and are
never exposed to the agent's `run_command` tool, unless explicitly whitelisted.

## Runtime Behaviour

Enabled servers connect for projects with access. Configuration changes reconnect the affected project integrations.
Tool-list changes are picked up automatically.

## Tool-Name Collisions

Tool names must be unique across connected MCP servers. If two servers expose the same effective tool name, Jupiter
reports the collision instead of choosing one silently.
