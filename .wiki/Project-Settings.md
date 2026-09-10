# Project Settings

Project settings are where you tell Jupiter how a repository should behave once a workspace is created.

> TODO: add screenshot

## Workspace init commands

Use these for setup that should happen on every fresh worktree — dependency warm-up, generated files, whatever your project needs.

Jupiter runs them in a visible terminal named **Workspace Init**.

## Project environment variables

Add environment names and values that should be available to project processes.

Sensitive persisted values are encrypted at the repository boundary.

## Host variables available to agents

`run_command` does **not** inherit the entire Jupiter host environment.

Instead, list the host variables an agent is allowed to receive — `PATH`, for example — and Jupiter copies only those before adding the project variables.

That is separate from the interactive terminal, which intentionally gets a broader environment. See [Environment Variables](Environment-Variables).

## MCP and the rest

MCP servers are configured globally, then exposed to selected projects.

The Settings UI also contains the OpenAI connection, lifecycle hooks, automatic Git updates, and usage views.
