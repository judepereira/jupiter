# Getting Started

Here’s the shortest path from a fresh checkout to a useful Jupiter session.

## 1. Get the prerequisites

For a native install you’ll need Java 25, Git, and ripgrep. For Docker, you just need Docker plus somewhere persistent
to keep Jupiter’s state.

## 2. Create an encryption key

```bash
openssl rand -base64 32
```

Save it somewhere sensible. Jupiter expects the **same** key on every later startup; generating a fresh one for an
existing database will not work.

## 3. Start Jupiter

Pick either [Running with Docker](Running-with-Docker) or [Running Natively](Running-Natively).

The default port is `7272`.

## 4. Connect a model provider

Either set `OPENAI_API_KEY`, use the OpenAI device authorisation flow, or connect Claude through the hosted OAuth
copy/paste-code flow in **Settings → Model Providers**.

See [OpenAI Authentication](OpenAI-Authentication) and [Anthropic Authentication](Anthropic-Authentication).

## 5. Add a project

Open **New project**, browse to an existing source directory, and add it.

![Opening a project in Jupiter](images/projects.png)

## 6. Create a workspace

Choose a new branch or an existing one. Jupiter creates a Git worktree for it.

That’s it. Create a session, give the agent some work, inspect the changes, and use the terminal when you need it.

Next, read [Interface Tour](Interface-Tour) and [Workspaces and Git Worktrees](Workspaces-and-Git-Worktrees).
