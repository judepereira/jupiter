The shortest path to your first session is by running the Docker container.

## Run the Docker container

See [Running with Docker](Running-with-Docker).

## Connect a model provider

Either set `OPENAI_API_KEY`, connect your ChatGPT/OpenAI subscription, or connect Claude in **Settings → Model
Providers**.

See [Models and Providers](Models-and-Providers).

## Open a project

Click on **Open Project** (the plus sign in the top bar), browse to an existing source directory, and add it.

![Opening a project in Jupiter](images/projects.png)

## Create a workspace

Choose a new branch or an existing one. Jupiter creates a Git worktree for it.

That’s it. Create a session, give the agent some work, inspect the changes, and use the terminal when you need it.

Next, read [Interface Tour](Interface-Tour) and [Workspaces and Git Worktrees](Workspaces-and-Git-Worktrees).

## Updating Jupiter

Before updating, make sure you still have the encryption key and back up `~/.jupiter` (or the persistent Docker state
directory).

Pull the latest published image, and restart Jupiter with the same state mount, source mounts, and encryption key:

```bash
docker pull judepereira/jupiter:latest
```

**Note:** Versioning will soon be introduced.
