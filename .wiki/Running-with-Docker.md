Docker is the preferred way to run Jupiter. The runtime image provides hooks to preinstall software, and permits init
scripts to run as root or as the user.

Pull the latest published image:

```bash
docker pull judepereira/jupiter:latest
```

Generate an encryption key:

```bash
export JUPITER_ENCRYPTION_KEY="$(openssl rand -base64 32)"
```

**Important:** Don't lose or regenerate your encryption key! All data is encrypted with this key, to prevent a rogue
agent from reading the database file and searching for credentials.

Then, run the docker container:

```bash
mkdir -p "$HOME/.jupiter"
docker run --rm \
  -p 7272:7272 \
  -e JUPITER_ENCRYPTION_KEY="$JUPITER_ENCRYPTION_KEY" \
  -v "$HOME/.jupiter:/home/jupiter/.jupiter" \
  -v "$HOME/developer:/home/jupiter/developer" \ # Replace developer with the dir where all your projects are checked out
  judepereira/jupiter:latest
```

Open `http://localhost:7272`, choose [**Open Project**](Projects), and select a repository under
`/home/jupiter/developer` to get started.

To update Jupiter, pull the latest image and restart the container with the same encryption key, state mount, and source
mount:

```bash
docker pull judepereira/jupiter:latest
```

**Note:** Versioning will soon be introduced.

**Important:** Use your own network sandbox for now. A built-in one will be shipped soon!

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
