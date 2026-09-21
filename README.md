# Jupiter

Jupiter is a remote coding environment designed to run on a server and remain available from any browser — laptop,
phone, whatever happens to be nearby.

It combines coding agents, Git worktrees, persistent sessions, a real terminal, diff review, MCP tools, and a web UI.
There’s no desktop app or CLI to keep in sync. The browser is the client.

![Jupiter interface](.wiki/images/interface-desktop.png)

## What makes Jupiter useful?

- **It’s remote-first.** Run it where your code lives, and connect from a browser.
- **Workspaces are Git worktrees.** Each branch gets its own working tree, so parallel work doesn’t turn into a
  branch-switching juggling act.
- **Sessions survive the browser.** Chat history, drafts, tool traces, review state, and settings live on the server.
- **Delegation is inspectable.** Subagents have their own persisted sessions; you can open them and see what actually
  happened.
- **Credentials get special treatment.** Sensitive persisted values are encrypted, and Jupiter’s own secrets are kept
  out of untrusted managed child processes.
- **It’s extendable.** MCP servers and Markdown-based slash commands plug into Jupiter without changing the core
  application.

Jupiter supports OpenAI and Anthropic Claude and loads eligible model metadata from models.dev. Connected providers let
you choose additional models in **Settings → Model Providers**, while agents can define their own ordered model
defaults. Agent-default turns use the first configured preference whose provider is available; an explicit model choice
is strict and never silently switches providers. Catalogue compatibility does not guarantee that an account is entitled
to use a particular model. See [Models and Providers](https://github.com/judepereira/jupiter/wiki/Models-and-Providers).

## Docker: the recommended way to run Jupiter

Pull the latest published image:

```bash
docker pull judepereira/jupiter:latest
```

Generate an encryption key, choose the directory containing your source repositories, and keep the key safe:

```bash
export JUPITER_ENCRYPTION_KEY="$(openssl rand -base64 32)"
export JUPITER_REPOS="$HOME/src"
mkdir -p "$HOME/.jupiter"
```

Then start Jupiter with persistent state and your source tree mounted:

```bash
docker run --rm \
  -p 7272:7272 \
  -e JUPITER_ENCRYPTION_KEY="$JUPITER_ENCRYPTION_KEY" \
  -v "$HOME/.jupiter:/home/jupiter/.jupiter" \
  -v "$JUPITER_REPOS:/workspace" \
  judepereira/jupiter:latest
```

Open `http://localhost:7272`, choose **New project**, and select a repository under `/workspace`.

One important bit: **do not generate a new encryption key on every restart.** The database is tied to that key; lose it
and the encrypted data is gone.

To update Jupiter, pull the latest image and restart the container with the same encryption key, state mount, and source
mount:

```bash
docker pull judepereira/jupiter:latest
```

## Connecting model providers

Open **Settings → Model Providers** to connect an OpenAI subscription or Claude. OpenAI can also use `OPENAI_API_KEY`;
Claude uses the hosted OAuth copy/paste-code flow. See
[Models and Providers](https://github.com/judepereira/jupiter/wiki/Models-and-Providers) for connection and
model-selection details. Provider credentials and OAuth state are stored in encrypted database fields.

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

The wiki source lives in `.wiki/` and is published by `.github/workflows/publish-wiki.yml`.

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
documentation screenshots under `target/documentation-screenshots`.

Contributor architecture, testing, and documentation-maintenance conventions live in `AGENTS.md` rather than the
user-facing wiki.
