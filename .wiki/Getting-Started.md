# Getting Started

Here’s the shortest path from a new install to a useful Jupiter session. Docker is the recommended way to run Jupiter.

## 1. Get Jupiter

```bash
git clone https://github.com/judepereira/jupiter.git
cd jupiter
```

## 2. Choose how to run it

For the recommended Docker setup, you only need Docker plus somewhere persistent for Jupiter’s state and access to the
repositories you want it to manage.

For a native install, you’ll need Java 25, Git, and ripgrep.

See [Running with Docker](Running-with-Docker) or [Running Natively](Running-Natively).

## 3. Create an encryption key

```bash
openssl rand -base64 32
```

Save it somewhere sensible. Jupiter expects the **same** key on every later startup; generating a fresh one for an
existing database will not work.

## 4. Start Jupiter

Follow [Running with Docker](Running-with-Docker) for the recommended setup. The default port is `7272`.

## 5. Connect a model provider

Either set `OPENAI_API_KEY`, use the OpenAI device authorisation flow, or connect Claude through the hosted OAuth
copy/paste-code flow in **Settings → Model Providers**.

See [Models and Providers](Models-and-Providers).

## 6. Add a project

Open **New project**, browse to an existing source directory, and add it. With the Docker example, mounted repositories
appear under `/workspace`.

![Opening a project in Jupiter](images/projects.png)

## 7. Create a workspace

Choose a new branch or an existing one. Jupiter creates a Git worktree for it.

That’s it. Create a session, give the agent some work, inspect the changes, and use the terminal when you need it.

Next, read [Interface Tour](Interface-Tour) and [Workspaces and Git Worktrees](Workspaces-and-Git-Worktrees).

## Updating Jupiter

Before updating, make sure you still have the encryption key and back up `~/.jupiter` (or the persistent Docker state
directory).

Then update the source:

```bash
git pull --ff-only
```

For Docker, rebuild the image and restart Jupiter with the same state mount, source mounts, and encryption key:

```bash
docker build -t jupiter .
```

For a native installation, rebuild and follow [Running Natively](Running-Natively) again:

```bash
./mvnw package
```
