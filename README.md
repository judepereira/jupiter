# Jupiter
Jupiter coding agent harness in the spirit of Claude Code, Codex, and opencode. It is model-agnostic / BYOM where API keys or a Codex subscription apply, remote-first for any browser, self-hostable on a server, and suited to private VPN access like Tailscale. There is no desktop app or CLI here — it is 100% focused on web and mobile browsers.

| Jupiter | Claude Code | Codex | opencode |
|---|---|---|---|
| 🌐 Browser-first | 🧠 Agentic | 🪄 Strong coding flow | 🛠️ Hackable |
| 🔐 Security-minded | 🤝 Familiar | 🔑 Subscription/API | ⚙️ Flexible |
| 🏠 Self-hostable | 💻 Local-first | ☁️ Service-backed | 📦 DIY-friendly |

## Running locally
```bash
./mvnw spring-boot:run
```

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
docker run --rm -p 7272:7272 jupiter
```

For persistence inside a container, mount the app user's `/home/<app-user>/.jupiter` directory:

```bash
docker run --rm -p 7272:7272 -v "$(pwd)/.jupiter:/home/jupiter/.jupiter" jupiter
```

## Defaults
- `PORT` defaults to `7272`, so the app is available at http://localhost:7272

## Contributing
Contributions are welcome. For major changes, please open an issue first so we can discuss the direction.
