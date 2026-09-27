Jupiter is a secure, model agnostic agentic IDE designed to run on a server and remain available from any browser -
laptop, phone, whatever happens to be nearby. A perfect handoff enables seamless continuity of work.

![Jupiter interface](images/interface-desktop.png)

If this is your first run, go straight to [Getting Started](Getting-Started). After that, these are the pages that
matter most:

- [Interface Tour](Interface-Tour) - what lives where in the UI.
- [Workspaces and Git Worktrees](Workspaces-and-Git-Worktrees) - the Git model Jupiter is built around.
- [Agents](Agents) - primary agents, subagents, tools, and permissions.
- [Remote Deployment](Remote-Deployment) - how to expose Jupiter beyond localhost.
- [Security Model](Security-Model) - what Jupiter protects, and just as importantly, what it doesn’t.

## The Basic Model

Jupiter is easiest to understand in three layers:

- A **project** points at an existing source repository.
- A **workspace** is a Git worktree on a branch inside that project.
- A **session** is a conversation within a workspace.

Agents sit on top of those layers. Primary agents can use tools directly or delegate work to subagents that you can
inspect separately.

The browser is only the client. Jupiter keeps application state on the server, so reloading, disconnecting, or moving
between devices does not wipe chat history, drafts, tool traces, review state, or project settings.

## Where Should Jupiter Run?

Ideally, on the machine that already has your source code: a VM, home server, or an EC2 instance.

For remote access, a private VPN is recommended. If you need public access, use HTTPS and enable Jupiter’s HTTP basic
auth.

Jupiter runs against real source trees and shell processes; it is not an operating-system sandbox. Read
[Security Model](Security-Model) before exposing it remotely.
