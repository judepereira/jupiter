# Running with Docker

Docker is the easiest way to run Jupiter without caring about the local Java setup. The runtime image uses Java 25 and already includes Git, Git LFS, and ripgrep.

## Build it

```bash
docker build -t jupiter .
```

## Run it with persistent state

```bash
export JUPITER_ENCRYPTION_KEY="$(openssl rand -base64 32)"
mkdir -p .jupiter

docker run --rm \
  -p 7272:7272 \
  -e JUPITER_ENCRYPTION_KEY="$JUPITER_ENCRYPTION_KEY" \
  -v "$(pwd)/.jupiter:/home/jupiter/.jupiter" \
  jupiter
```

Keep that encryption key. Replacing it on the next run will not unlock the existing database.

The entrypoint accepts the key from the container environment, removes it before init scripts run, and then pipes it once to Java over stdin.

## User and group IDs

The defaults are:

- `USERNAME=jupiter`
- `WITH_UID=1000`
- `WITH_GID=1000`
- `PORT=7272`

Override UID/GID if your mounted source or state directories need host-compatible ownership.

## Init scripts

If `/init.sh` exists, it runs as root. If `/init-user.sh` exists, it runs as the configured Jupiter user.

Neither script receives `JUPITER_ENCRYPTION_KEY` in its environment.

## Source code

Mount or otherwise make the repositories you want Jupiter to manage available inside the container. They need to be writable if agents or Git worktrees are going to change them.
