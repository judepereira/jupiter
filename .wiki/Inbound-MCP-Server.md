# Inbound MCP server

Jupiter can expose project-scoped tools to an MCP client. This is separate from [MCP Servers](MCP-Servers), which
configures outbound connections from Jupiter to other MCP servers.

## Set up a credential

Open **Settings → Inbound MCP credentials**. Create a named credential, choose either **All projects, including future
projects** or selected projects, and select allowed tools. Create additional named credentials for different clients or
access scopes. The automatically-created **Default** credential initially covers the projects currently available and
all seven tools; you can edit its scope like any other credential.

The bearer token is shown when created and only when you explicitly choose **Reveal** or **Rotate**. Copy it into the
MCP client securely. Rotate replaces the token, so update every client using it; revoke disables it. A credential's
project and tool permissions can be edited without changing its token. There are no MCP tools for managing credentials:
use Jupiter's settings UI.

Configure an MCP client with the instance's `/mcp` URL and an `Authorization: Bearer <token>` header. The endpoint uses
Streamable HTTP. Use HTTPS when connecting over an untrusted network, and only connect to a Jupiter instance whose
identity you trust. The token grants access to Jupiter tools and is not a sandbox for the connected agent or host
operating system; configure a separate network/OS sandbox appropriate to your deployment.

When Jupiter itself connects to its own endpoint as an outbound MCP server, configure a server entry in **Settings → MCP
Servers** with the `/mcp` URL and an `Authorization` header containing the bearer token. Update that header after
rotation. Outbound server settings and their project access remain separate from inbound token permissions.

## Tools

All IDs are integers. Arguments not listed are rejected. Successful tool results are returned in a JSON object under
`result`. Errors are returned with `isError: true` and structured `error` data containing a stable `code` and safe
`message`: `INVALID_ARGUMENT`, `RESOURCE_UNAVAILABLE`, `SESSION_BUSY`, or `EXECUTION_FAILED`. An inaccessible or
nonexistent project, workspace, or session is reported indistinguishably as `RESOURCE_UNAVAILABLE`.

|        Tool         |                                                                                                     Arguments                                                                                                     |                                                                                                                                                         Purpose                                                                                                                                                          |
|---------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `list_projects`     | none                                                                                                                                                                                                              | List projects this token can access.                                                                                                                                                                                                                                                                                     |
| `list_workspaces`   | `projectId`                                                                                                                                                                                                       | List project workspaces.                                                                                                                                                                                                                                                                                                 |
| `list_sessions`     | optional `projectId`, `workspaceId`                                                                                                                                                                               | List primary sessions for accessible projects, optionally narrowed to a project or workspace.                                                                                                                                                                                                                            |
| `list_agents`       | `projectId`                                                                                                                                                                                                       | List available primary agents.                                                                                                                                                                                                                                                                                           |
| `create_workspace`  | `projectId`, `branchName`, `createBranch`                                                                                                                                                                         | Create a workspace and its initial session.                                                                                                                                                                                                                                                                              |
| `post_message`      | `message` and exactly one target: `sessionId`; `workspaceId`; or `projectId` + `branchName` + `createBranch`. Optional `agentId`, `modelId`, `thinkingLevel`; `sessionName` is supported only with `workspaceId`. | Submit a message to a session, or create a session/workspace and submit there. Creating a workspace requires this credential to allow `create_workspace` too. Returns promptly with `accepted`, `status`, `sessionId`, `workspaceId`, `projectId`, and `assistantId`; it does not return the full streamed conversation. |
| `summarize_session` | `sessionId`                                                                                                                                                                                                       | Summarize an accessible session.                                                                                                                                                                                                                                                                                         |

For example, list projects with `{}`; list workspaces with `{"projectId": 12}`; post to an existing session with
`{"sessionId": 34, "message": "Inspect the failing tests"}`. To create a workspace and post in one call, use
`{"projectId": 12, "branchName": "fix-tests", "createBranch": true, "message": "Inspect the failing tests"}`.

Session listings include a `busy` flag. A busy session cannot accept another message. Accepted background work can
continue after its token is revoked; revocation blocks subsequent authenticated requests, not jobs already accepted.
