# Jupiter

Jupiter is secure, agentic IDE (model agnostic) designed to run on a server and remain available from any browser -
laptop, phone, whatever happens to be nearby. A perfect handoff enables seamless continuity of work.

![Jupiter interface](.wiki/images/interface-desktop.png)

PS - the mobile interface looks like [this](https://github.com/judepereira/jupiter/wiki/Interface-Tour).

## Highlights

- **Self-host friendly:** Self host it, and connect from a browser from anywhere, using a VPN like Tailscale.
- **Model Agnostic:** Not being tied to a specific model provider allows you to mix and match models, including falling
  back seamlessly when a model provider experiences outages.
- **Secure:** A rogue agent cannot access your env, and any other credentials from your env (network sandbox coming
  soon!).

## Quick Start

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

Open `http://localhost:7272`, choose **Open Project**, and select a repository under `/home/jupiter/developer`.

To update Jupiter, pull the latest image and restart the container with the same encryption key, state mount, and source
mount:

```bash
docker pull judepereira/jupiter:latest
```

**Note:** Versioning will soon be introduced.

**Important:** Use your own network sandbox for now. A built-in one will be shipped soon!

It is possible to change the username from `jupiter` to your own username (makes it easier to jump into worktrees from
an external IDE), and to configure system init and user init scripts (preinstall software required). See
[Running with Docker](https://github.com/judepereira/jupiter/wiki/Running-with-Docker).

## Connecting Model Providers

Open **Settings → Model Providers** to connect an OpenAI subscription or Claude. OpenAI can also use `OPENAI_API_KEY`.
See [Models and Providers](https://github.com/judepereira/jupiter/wiki/Models-and-Providers) for connection and
model-selection details.

## Protection against Rogue Agents

It's strongly recommended to set `JUPITER_HTTP_AUTH_PASSWORD`, in order to prevent a rogue agent from accessing Jupiter
itself. This is also useful when exposing Jupiter to the public facing internet (although this is NOT recommended).
Always use a private VPN such as Tailscale.

## Documentation

The full documentation lives in the [GitHub Wiki](https://github.com/judepereira/jupiter/wiki). A good place to begin is
[Getting Started](https://github.com/judepereira/jupiter/wiki/Getting-Started), followed by
[Workspaces and Git Worktrees](https://github.com/judepereira/jupiter/wiki/Workspaces-and-Git-Worktrees) and
[Security Model](https://github.com/judepereira/jupiter/wiki/Security-Model).

## License

Jupiter is licensed under the [MIT License](LICENSE).

## Storage and Backups

Jupiter keeps its state under `~/.jupiter`, including `jupiter.sqlite`.

Back up the database and encryption key separately. You need both to recover encrypted state.

## Contributing

Found a bug? Usability issue? Create an [issue](https://github.com/judepereira/jupiter/issues). If you'd like to add a
new feature, please create an issue first, outlining your feature, and it's proposed implementation plan.

Made with ❤️ in Amsterdam
