# Jupiter

Jupiter is a coding agent designed to run on a server and remain available from any browser — laptop, phone, whatever
happens to be nearby.

It combines coding agents, Git worktrees, persistent sessions, a real terminal, diff review, MCP tools, and a web UI.
There’s no desktop app or CLI to keep in sync. The browser is the client.

![Jupiter interface](.wiki/images/interface-desktop.png)

## What makes Jupiter useful?

- **It’s remote-first.** Run it where your code lives, and connect from a browser.
- **Workspaces are Git worktrees.** Each branch gets its own working tree, so parallel work doesn’t turn into a checkout
  juggling act.
- **Sessions survive the browser.** Chat history, drafts, tool traces, review state, and settings live on the server.
- **Delegation is inspectable.** Subagents have their own persisted sessions; you can open them and see what actually
  happened.
- **Credentials get special treatment.** Sensitive persisted values are encrypted, and Jupiter’s own secrets are kept
  out of untrusted managed child processes.
- **It’s extendable.** MCP servers and Markdown-based slash commands plug into the harness without changing the core
  application.

Jupiter discovers OpenAI GPT-5.6-series and Anthropic Claude models from models.dev. Connect a provider in **Settings**,
then choose its favourited models in the chat picker. Bundled agents use OpenAI first with Anthropic fallbacks: Plan and
Engineer use Claude Opus 5, while Explore, Apprentice, and Test use Claude Sonnet 5. Agent-default and subagent runs use
the first available provider before the request, while an explicit model choice is strict and never silently falls back.
An implicit browser model choice is resolved again when the turn executes, and any fallback is attributed in the
message/session; usage is recorded against the actual model. See
[Models and Thinking](https://github.com/judepereira/jupiter/wiki/Models-and-Thinking),
[OpenAI Authentication](https://github.com/judepereira/jupiter/wiki/OpenAI-Authentication), and
[Anthropic Authentication](https://github.com/judepereira/jupiter/wiki/Anthropic-Authentication).

## Docker: the quickest way to run it

Build the image:

```bash
docker build -t jupiter .
```

Generate an encryption key and keep it safe:

```bash
export JUPITER_ENCRYPTION_KEY="$(openssl rand -base64 32)"
```

Then start Jupiter with persistent state:

```bash
mkdir -p .jupiter

docker run --rm \
  -p 7272:7272 \
  -e JUPITER_ENCRYPTION_KEY="$JUPITER_ENCRYPTION_KEY" \
  -v "$(pwd)/.jupiter:/home/jupiter/.jupiter" \
  jupiter
```

Open `http://localhost:7272`.

One important bit: **do not generate a new encryption key on every restart.** The database is tied to that key; lose it
and the encrypted data is gone.

## Running it natively

You’ll need Java 25, Git, and ripgrep.

```bash
./mvnw package
entered_key="$(openssl rand -base64 32)"
bootstrap_envelope() {
  printf 'JUPITER_BOOTSTRAP_V1\0'
  printf 'JUPITER_ENCRYPTION_KEY\0%s\0' "$entered_key"
  # Add desired runtime variables as NAME\0VALUE\0 records here.
  # printf 'NAME\0%s\0' "$VALUE"
}
exec {bootstrap_fd}< <(bootstrap_envelope)
unset entered_key

env -u JUPITER_ENCRYPTION_KEY java \
  -XX:+DisableAttachMechanism \
  --enable-native-access=ALL-UNNAMED \
  -Dserver.port=7272 \
  -jar target/jupiter-0.0.1-SNAPSHOT.jar <&${bootstrap_fd}-
```

The key must be standard Base64 encoding of exactly 32 bytes. The bootstrap stream is a versioned NUL-delimited
`JUPITER_BOOTSTRAP_V1\0NAME\0VALUE\0` envelope sent over an anonymous file descriptor; it is not a raw key or a
newline-delimited stream. Add any runtime variables the launcher wants Spring to receive as additional records. Do not
put the key in Java arguments, environment variables, or temporary files. Docker, Compose, and Kubernetes launchers keep
their existing configuration paths.

The slightly unusual file-descriptor dance is intentional: the normal native startup path keeps the key out of the JVM
environment, arguments, and system properties.

## Connecting model providers

Open **Settings → Model Providers** to connect an OpenAI subscription or Claude Code. OpenAI can also use
`OPENAI_API_KEY`; Claude uses the hosted OAuth copy/paste-code flow described in
[Anthropic Authentication](https://github.com/judepereira/jupiter/wiki/Anthropic-Authentication). Provider credentials
and OAuth state are stored in encrypted database fields.

## Running Jupiter remotely

This is what Jupiter is built for, but don’t casually throw port `7272` onto the public internet.

A private network such as Tailscale is the recommended setup. If you do expose Jupiter publicly, set
`JUPITER_HTTP_AUTH_PASSWORD`, put it behind HTTPS, and make sure your reverse proxy supports both SSE and WebSockets.

Jupiter warns when Basic authentication is enabled but the public request still looks like plain HTTP.

## Documentation

The full documentation lives in the [GitHub Wiki](https://github.com/judepereira/jupiter/wiki). A good place to begin is
[Getting Started](https://github.com/judepereira/jupiter/wiki/Getting-Started), followed by
[Workspaces and Git Worktrees](https://github.com/judepereira/jupiter/wiki/Workspaces-and-Git-Worktrees) and
[Security Model](https://github.com/judepereira/jupiter/wiki/Security-Model).

The wiki source itself is checked into `.wiki/` and published by `.github/workflows/publish-wiki.yml`.

## License

Jupiter is licensed under the [MIT License](LICENSE).

## Storage and backups

Jupiter keeps its state under `~/.jupiter`, including `jupiter.sqlite`.

Back up the database and encryption key separately. You need both to recover encrypted state.

## Hacking on Jupiter

Run the test suite with:

```bash
./mvnw test
```

There are unit, integration, template-rendering, and Playwright browser tests. Provider tests use fixtures and mocks;
they do not use live OpenAI or Anthropic credentials. Normal Maven test and package runs also generate disposable
documentation screenshots under `target/documentation-screenshots`; see
[Development and Testing](https://github.com/judepereira/jupiter/wiki/Development-and-Testing) for how to refresh the
tracked wiki image catalog.
