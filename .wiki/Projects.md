A project points Jupiter at an existing source directory. Jupiter doesn’t clone a repository for you yet.

![Opening a project in Jupiter](images/projects.png)

## Adding One

Use **Open Project**, browse the server filesystem, and select the directory you want.

Jupiter stores the project name and normalized path. Closing a project removes it from the visible project bar; it does
**not** delete the source directory.

If you later add a path Jupiter has seen before, it can reopen the persisted project state.

## Project-Specific Settings

Each project can carry:

- workspace initialization commands
- project environment variables
- the host-environment allowlist used by agent commands
- MCP exposure settings

See [Project Settings](Project-Settings).

## Where the Actual Work Happens

Jupiter does its branch work in [Git worktrees](Workspaces-and-Git-Worktrees). That keeps separate branches in separate
directories instead of constantly switching one checkout back and forth.
