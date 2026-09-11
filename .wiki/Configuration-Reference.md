# Configuration Reference

This is the operator-facing configuration I’d expect to reach for most often in v1.

## Environment variables

| Variable | Purpose | Default |
|---|---|---|
| `PORT` | Docker entrypoint HTTP port | `7272` |
| `JUPITER_ENCRYPTION_KEY` | Required Docker bootstrap encryption key | none |
| `JUPITER_HTTP_AUTH_PASSWORD` | Enables HTTP Basic auth when nonblank | blank |
| `JUPITER_HTTP_AUTH_USERNAME` | Basic auth username | `jupiter` |
| `OPENAI_API_KEY` | OpenAI API credential | blank |
| `USERNAME` | Container app user | `jupiter` |
| `WITH_UID` | Container app UID | `1000` |
| `WITH_GID` | Container app GID | `1000` |
| `INIT_SCRIPT` | Root container initialisation script | `/init.sh` |
| `INIT_USER_SCRIPT` | User container initialisation script | `/init-user.sh` |

## Spring properties

Common ones include:

```properties
server.port=7272
openai.api-key=...
openai.retry.max-retries=10
openai.retry.initial-backoff=1s
openai.retry.max-backoff=120s
agent.command-timeout-seconds=600
agent.max-iterations=1000
```

The model catalogue defaults to `https://models.dev/catalog.json` and can be changed with `models.dev.catalog-url`.

OpenAI OAuth issuer and endpoint settings are Spring-configurable too.

## State paths

The default database is:

```text
~/.jupiter/jupiter.sqlite
```

User slash commands live under:

```text
~/.jupiter/commands/*.md
```

## Encryption key note

For normal native startup, don’t put the encryption key into the Java environment. Use the stdin bootstrap in [Running Natively](Running-Natively).
