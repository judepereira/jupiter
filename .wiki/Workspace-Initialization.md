# Workspace Initialization

Some repositories need a little setup before they’re useful. Workspace initialization lets you automate that setup every time Jupiter creates a new worktree.

## Configure it

Open project settings and enter the commands you want, for example:

```bash
./mvnw -q dependency:go-offline
npm install
```

Jupiter doesn’t try to understand Maven, npm, or any other build system here. It simply sends the commands to a shell.

## What happens after workspace creation?

Jupiter creates a real terminal called **Workspace Init**, opens the bottom panel, and writes the configured commands into it.

The terminal starts inside the new workspace directory.

Project environment variables are available. Jupiter’s encryption and HTTP-authentication credentials are removed before the terminal process starts.

## Why make it visible?

Because setup scripts fail, and hiding their output in a background job makes that unnecessarily annoying to debug.

The output stays in the terminal tab for as long as that terminal remains alive.
