Jupiter can expose itself as an MCP server. This enables use cases of having Jupiter control itself, or have another
agent control Jupiter.

This is separate from [MCP Servers](MCP-Servers), which configures outbound connections from Jupiter to other MCP
servers.

## Create a Credential

Open **Settings → Inbound MCP** and create a new authentication token.

Configure an MCP client with this instance's `http(s)://<Jupiter's deployment URL>/mcp` URL and an `Authorization:
Bearer <token>` header.

When Jupiter itself connects to its own endpoint as an outbound MCP server, configure a server entry in **Settings → MCP
Servers** with the URL as `http://localhost:7272/mcp` and an `Authorization` header containing the bearer token.

## Tools

The following tools are exposed. If you'd like to add more, please open a GitHub issue :)

|        Tool         |                                                                                                     Arguments                                                                                                     |                                                                                                                                                         Purpose                                                                                                                                                          |
|---------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `list_projects`     | none                                                                                                                                                                                                              | List projects this token can access.                                                                                                                                                                                                                                                                                     |
| `list_workspaces`   | `projectId`                                                                                                                                                                                                       | List project workspaces.                                                                                                                                                                                                                                                                                                 |
| `list_sessions`     | optional `projectId`, `workspaceId`                                                                                                                                                                               | List primary sessions for accessible projects, optionally narrowed to a project or workspace.                                                                                                                                                                                                                            |
| `list_agents`       | `projectId`                                                                                                                                                                                                       | List available primary agents.                                                                                                                                                                                                                                                                                           |
| `create_workspace`  | `projectId`, `branchName`, `createBranch`                                                                                                                                                                         | Create a workspace and its initial session.                                                                                                                                                                                                                                                                              |
| `post_message`      | `message` and exactly one target: `sessionId`; `workspaceId`; or `projectId` + `branchName` + `createBranch`. Optional `agentId`, `modelId`, `thinkingLevel`; `sessionName` is supported only with `workspaceId`. | Submit a message to a session, or create a session/workspace and submit there. Creating a workspace requires this credential to allow `create_workspace` too. Returns promptly with `accepted`, `status`, `sessionId`, `workspaceId`, `projectId`, and `assistantId`; it does not return the full streamed conversation. |
| `summarize_session` | `sessionId`                                                                                                                                                                                                       | Summarize an accessible session.                                                                                                                                                                                                                                                                                         |

