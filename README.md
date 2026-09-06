# Jupiter
Jupiter coding agent harness in the spirit of Claude Code, Codex, and opencode. It is model-agnostic / BYOM where API keys or a Codex subscription apply, remote-first for any browser, self-hostable on a server, and suited to private VPN access like Tailscale. There is no desktop app or CLI here — it is 100% focused on web and mobile browsers.

| Jupiter | Claude Code | Codex | opencode |
|---|---|---|---|
| 🌐 Browser-first | 🧠 Agentic | 🪄 Strong coding flow | 🛠️ Hackable |
| 🔐 Security-minded | 🤝 Familiar | 🔑 Subscription/API | ⚙️ Flexible |
| 🏠 Self-hostable | 💻 Local-first | ☁️ Service-backed | 📦 DIY-friendly |

## Running locally
Set the mandatory, stable database encryption key before starting:
```bash
export JUPITER_ENCRYPTION_KEY="$(openssl rand -base64 32)"
./mvnw spring-boot:run
```
The key must be standard Base64 encoding of exactly 32 bytes. Keep it unchanged across restarts and back it up separately from the database: losing it makes encrypted data unrecoverable, and changing it causes startup failure.

On the first startup after upgrading, back up the database and key first. Existing plaintext data is migrated automatically before readiness; migration is one-way for older app versions. Database and key backups must be managed separately. App-level encryption protects values going forward, but old WAL/backups, deleted pages, and other external copies may still contain pre-migration plaintext unless securely handled.

Or build and run the packaged jar:
```bash
./mvnw package
java -jar target/jupiter-0.0.1-SNAPSHOT.jar
```

## Storage
`~/.jupiter` is used to store persistent state. No state is stored in the browser :)

## Docker image
Build a Docker image as usual:

```bash
docker build -t jupiter .
docker run --rm -p 7272:7272 \
  -e JUPITER_ENCRYPTION_KEY="$(openssl rand -base64 32)" jupiter
```
Keep the same key for every restart; do not bake it into image layers.

For persistence inside a container, mount the app user's `/home/<app-user>/.jupiter` directory:

```bash
docker run --rm -p 7272:7272 \
  -e JUPITER_ENCRYPTION_KEY="$JUPITER_ENCRYPTION_KEY" \
  -v "$(pwd)/.jupiter:/home/jupiter/.jupiter" jupiter
```

## Defaults
- `PORT` defaults to `7272`, so the app is available at http://localhost:7272

## Public deployment
Set `JUPITER_HTTP_AUTH_PASSWORD` to a nonblank value to enable HTTP Basic authentication. The username defaults to `jupiter` and can be changed with `JUPITER_HTTP_AUTH_USERNAME`. Only the implemented `GET /health` route is exempt; every other request, including `/error`, static files, SSE, and WebSocket handshakes, requires credentials. Passwords are read from the environment and are not passed as Java arguments or logged.

Use HTTPS for every public deployment. If TLS terminates at a reverse proxy, configure it to pass the public scheme in `Forwarded` or `X-Forwarded-Proto`; the warning detection accepts common comma-separated proxy values. The reverse proxy must support long-lived SSE connections and WebSocket upgrades.

## Contributing
Contributions are welcome. For major changes, please open an issue first so we can discuss the direction.
