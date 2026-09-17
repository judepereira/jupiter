# Running with Docker

Docker is the preferred way to run Jupiter. The runtime image uses Java 25 and already includes Git, Git LFS, and
ripgrep.

## Build it

```bash
docker build -t jupiter .
```

## Run it with persistent state and source code

Choose the parent directory containing the repositories you want Jupiter to access:

```bash
export JUPITER_ENCRYPTION_KEY="$(openssl rand -base64 32)"
export JUPITER_REPOS="$HOME/src"
mkdir -p .jupiter

docker run --rm \
  -p 7272:7272 \
  -e JUPITER_ENCRYPTION_KEY="$JUPITER_ENCRYPTION_KEY" \
  -v "$(pwd)/.jupiter:/home/jupiter/.jupiter" \
  -v "$JUPITER_REPOS:/workspace" \
  jupiter
```

Replace `$HOME/src` with the directory where your repositories live. In Jupiter, use **New project** and select a
repository under `/workspace`.

Keep the encryption key. Replacing it on the next run will not unlock the existing database.

## User and group IDs

The defaults are:

- `USERNAME=jupiter`
- `WITH_UID=1000`
- `WITH_GID=1000`
- `PORT=7272`

Override UID/GID if your mounted source or state directories need host-compatible ownership.

## Init scripts

If `/init.sh` exists, it runs as root. If `/init-user.sh` exists, it runs as the configured Jupiter user.

Treat both as trusted setup scripts; they can access the startup environment, including the encryption key.

## Source code

Repositories must be mounted or otherwise made available inside the container. They need to be writable if agents or Git
worktrees are going to change them.
