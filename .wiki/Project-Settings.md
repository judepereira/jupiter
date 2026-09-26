Project settings define how a repository should behave once a workspace is created.

![Project settings](images/project-settings.png)

## Workspace Initialization

Use workspace-init commands for setup that should happen on every fresh worktree, such as warming dependencies or
generating files.

For example:

```bash
./mvnw -q dependency:go-offline
npm install
```

After creating a workspace, Jupiter opens a visible terminal named **Workspace Init** and runs the configured commands
inside the new worktree. Project environment variables are available there, and the output remains visible in the
terminal tab for debugging.

## Project Environment Variables

Add environment names and values that should be available to project processes. Project environment variables are
available to `run_command`, terminals, and lifecycle hooks where applicable. They are also available to MCP
configuration placeholders.

For `run_command`, Jupiter first forwards the standard host variables `PATH`, `HOME`, `USER`, `LOGNAME`, and `TMPDIR`
when present. You can add more host variable names in **Additional command environment variables**; only those
explicitly allowlisted names are forwarded. Project variables override host values with the same name. Security
sanitization removes Jupiter credentials and other protected values, and commands always run with the fixed
`LANG=C.utf8` and `LC_ALL=C.utf8` locale.

**Important:** Do not add sensitive values here! Set those values as the Docker environment variables, and use
`${env.VAR_NAME}` here.

The integrated terminal intentionally receives its normal broader process environment, and workspace lifecycle hooks
have their own hook-specific variables. These behaviors are separate from `run_command`.

## Host Variables Available to Agents

Agent `run_command` does not inherit the entire Jupiter host environment. Standard variables listed above are automatic;
the Settings allowlist is for additional host variables only. Jupiter's encryption key is never restored into commands
or the terminal.

## Lifecycle Hooks

Hooks receive project variables plus `JUPITER_PROJECT_NAME`, `JUPITER_WORKSPACE_NAME`, and `JUPITER_SESSION_NAME`. They
do not automatically inherit Jupiter's HTTP-authentication credentials or encryption key.

## MCP Placeholders

MCP URLs and headers can use `${env.NAME}` placeholders. Project variables take precedence over host/runtime values. The
encryption key is never available as an MCP placeholder.

## Other Settings

The Settings UI also contains MCP servers, provider connections, lifecycle hooks, automatic Git updates, and usage
views.
