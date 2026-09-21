# Getting Started

Here’s the shortest path from a new install to a useful Jupiter session. Docker is the recommended way to run Jupiter.

## 1. Choose how to run it

For the recommended Docker setup, you only need Docker plus somewhere persistent for Jupiter’s state and access to the
repositories you want it to manage. No Jupiter source checkout is required:

```bash
docker pull judepereira/jupiter:latest
```

For a native install, you’ll need Java 25, Git, and ripgrep, and you’ll need the Jupiter source checkout:

```bash
git clone https://github.com/judepereira/jupiter.git
cd jupiter
```

See [Running with Docker](Running-with-Docker) or [Running Natively](Running-Natively).

## 2. Create an encryption key

```bash
openssl rand -base64 32
```

Save it somewhere sensible. Jupiter expects the **same** key on every later startup; generating a fresh one for an
existing database will not work.

## 3. Start Jupiter

Follow [Running with Docker](Running-with-Docker) for the recommended setup. The default port is `7272`.

## 4. Connect a model provider

Either set `OPENAI_API_KEY`, use the OpenAI device authorisation flow, or connect Claude through the hosted OAuth
copy/paste-code flow in **Settings → Model Providers**.

See [Models and Providers](Models-and-Providers).

## 5. Add a project

Open **New project**, browse to an existing source directory, and add it. With the Docker example, mounted repositories
appear under `/workspace`.

![Opening a project in Jupiter](images/projects.png)

## 6. Create a workspace

Choose a new branch or an existing one. Jupiter creates a Git worktree for it.

That’s it. Create a session, give the agent some work, inspect the changes, and use the terminal when you need it.

Next, read [Interface Tour](Interface-Tour) and [Workspaces and Git Worktrees](Workspaces-and-Git-Worktrees).

## Updating Jupiter

Before updating, make sure you still have the encryption key and back up `~/.jupiter` (or the persistent Docker state
directory).

For Docker, pull the latest published image and restart Jupiter with the same state mount, source mounts, and encryption
key:

```bash
docker pull judepereira/jupiter:latest
```

For a native installation, update the checkout, rebuild, and follow [Running Natively](Running-Natively) again:

```bash
git pull --ff-only
./mvnw package
```

