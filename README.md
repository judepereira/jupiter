# Jupiter v2

Jupiter v2 is a security-minded coding agent harness in the spirit of Claude Code, Codex, and opencode. It is model-agnostic / BYOM where API keys or a Codex subscription apply, remote-first for any browser, self-hostable on a server, and suited to private VPN access like Tailscale. There is no desktop app or CLI here — it is 100% focused on web and mobile browsers.

| Jupiter v2 | Claude Code | Codex | opencode |
|---|---|---|---|
| 🌐 Browser-first | 🧠 Agentic | 🪄 Strong coding flow | 🛠️ Hackable |
| 🔐 Security-minded | 🤝 Familiar | 🔑 Subscription/API | ⚙️ Flexible |
| 🏠 Self-hostable | 💻 Local-first | ☁️ Service-backed | 📦 DIY-friendly |

## Docker Compose

Run the app with the standalone profile:

```bash
docker compose --profile standalone up --build
```

Compose reads `.env` automatically if it exists. Start by copying `.env.example` to `.env` and adjusting values as needed.

## Defaults

- `PORT` defaults to `7272`, so the app is available at http://localhost:7272
- `USERNAME` defaults to `$USER` from your shell; changing it requires rebuilding the app image
- `POSTGRES_DATA_DIR` defaults to `./.data/postgres` for persistent database storage

## Mounts

- `HOST_DEVELOPER_DIR` and `HOST_SSH_DIR` overrides in `.env` must be absolute paths
- If you omit them, Compose uses `${HOME}/developer` and `${HOME}/.ssh`
- Your host developer directory is mounted to `/workspace` and also to the same host path inside the container, so host/container paths match
- `~/.ssh` is mounted read-only into the app container

## Running Jupiter

```bash
# Start
docker compose --profile standalone up --build

# Stop
docker compose --profile standalone down

# Rebuild the app image
docker compose --profile standalone build app

# View logs
docker compose --profile standalone logs -f app postgres

# Remove the persistent database intentionally
rm -rf ./.data/postgres
```

## Contributing

Contributions are welcome. For major changes, please open an issue first so we can discuss the direction.
