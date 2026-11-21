# Inbound MCP server

Jupiter can expose project-scoped tools to an MCP client. This is separate from [MCP Servers](MCP-Servers), which
configures outbound connections from Jupiter to other MCP servers.

## Set up a credential

Open **Settings → Inbound MCP** and choose **create token** to reveal the credential form. Give the credential a name,
choose either **All projects, including future projects** or selected projects, and select allowed tools. New
credentials allow all seven tools by default. Create separate named credentials for different clients or access scopes;
each credential can have its own project and tool permissions. Credentials are not created automatically.

Copy the bearer token securely when it is displayed after creation. The token is shown only once, but remains usable
until revoked; it cannot be revealed or rotated later. If you lose a token, create a replacement credential, update the
clients to use its token, then revoke the old credential. If a token may be compromised, revoke it immediately and then
create a replacement credential for the clients. Revocation removes it from the credential list and prevents it from
authenticating; there is no revoked-token history. You can edit a credential's project and tool permissions without
changing its token, but its name cannot be changed. There are no MCP tools for managing credentials: use Jupiter's
settings UI.

Configure an MCP client with the instance's `/mcp` URL and an `Authorization: Bearer <token>` header. When behind a
TLS-terminating reverse proxy, configure trusted proxy header handling as described in
[Remote Deployment](Remote-Deployment); arbitrary forwarded headers are not trusted. The endpoint uses Streamable HTTP.
Use HTTPS when connecting over an untrusted network, and only connect to a Jupiter instance whose identity you trust.
The token grants access to Jupiter tools and is not a sandbox for the connected agent or host operating system;
configure a separate network/OS sandbox appropriate to your deployment.

When Jupiter itself connects to its own endpoint as an outbound MCP server, configure a server entry in **Settings → MCP
Servers** with the `/mcp` URL and an `Authorization` header containing the bearer token. If replacing the credential,
update that header with the new token. Outbound server settings and their project access remain separate from inbound
token permissions.

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

Session listings include a `busy` flag. A busy session cannot accept another message. MCP activity creates work in the
background and never changes the workspace or session selected in Jupiter's UI. Rail activity and unread indicators
remain visible; select a workspace or session explicitly to open it. Accepted background work can continue after its
token is revoked; revocation blocks subsequent authenticated requests, not jobs already accepted.
