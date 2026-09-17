# Project Settings

Project settings define how a repository should behave once a workspace is created.

![Project settings](images/project-settings.png)

## Workspace init commands

Use these for setup that should happen on every fresh worktree, such as dependency warm-up or generated files.
Jupiter runs them in a visible terminal named **Workspace Init**.

## Project environment variables

Add environment names and values that should be available to project processes. Sensitive persisted values are encrypted.

Project variables are available to terminals, agent commands, MCP configuration, and lifecycle hooks where applicable.

## Host variables available to agents

Agent `run_command` does not inherit the entire Jupiter host environment.

List the host variables an agent may receive, such as `PATH`. Jupiter copies only those variables before overlaying the
project variables.

The integrated terminal intentionally receives the broader Jupiter process environment before project variables are
overlaid. Jupiter's encryption key is never restored into the terminal.

## Lifecycle hooks

Hooks receive project variables plus `JUPITER_PROJECT_NAME`, `JUPITER_WORKSPACE_NAME`, and `JUPITER_SESSION_NAME`.
They do not automatically inherit Jupiter's HTTP-authentication credentials or encryption key.

## MCP placeholders

MCP URLs and headers can use `${env.NAME}` placeholders. Project variables take precedence over host/runtime values.
The encryption key is never available as an MCP placeholder.

## Other settings

The Settings UI also contains MCP servers, provider connections, lifecycle hooks, automatic Git updates, and usage views.
