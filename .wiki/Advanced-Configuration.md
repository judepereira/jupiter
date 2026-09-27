These settings are mainly useful when operating or tuning a Jupiter instance beyond the normal UI.

## Environment Variables

|           Variable           |                Purpose                 |     Default     |
|------------------------------|----------------------------------------|-----------------|
| `PORT`                       | Docker entrypoint HTTP port            | `7272`          |
| `JUPITER_ENCRYPTION_KEY`     | Required Docker startup encryption key | none            |
| `JUPITER_HTTP_AUTH_PASSWORD` | Enables HTTP Basic auth when nonblank  | blank           |
| `JUPITER_HTTP_AUTH_USERNAME` | Basic auth username                    | `jupiter`       |
| `OPENAI_API_KEY`             | OpenAI API credential                  | blank           |
| `USERNAME`                   | Container app user                     | `jupiter`       |
| `WITH_UID`                   | Container app UID                      | `1000`          |
| `WITH_GID`                   | Container app GID                      | `1000`          |
| `INIT_SCRIPT`                | Root container initialisation script   | `/init.sh`      |
| `INIT_USER_SCRIPT`           | User container initialisation script   | `/init-user.sh` |

## OpenAI Request Retries

Ordinary OpenAI request failures are retried with bounded exponential backoff. Streaming requests are retried only
before any response text or tool call has been emitted, so a partial response is never replayed. Cancellation,
interruption, and fatal JVM errors are not retried. If all attempts fail, the failure is still surfaced.

These application properties control the retry policy:

|            Property            | Default |                  Purpose                  |
|--------------------------------|---------|-------------------------------------------|
| `openai.retry.max-retries`     | `10`    | Maximum retries after the initial request |
| `openai.retry.initial-backoff` | `1s`    | Initial delay between retries             |
| `openai.retry.max-backoff`     | `120s`  | Maximum delay between retries             |

## State Paths

The default database is:

```text
~/.jupiter/jupiter.sqlite
```

Jupiter's editable slash commands live under:

```text
~/.jupiter/commands/*.md
```

Jupiter also discovers external prompt files from the active workspace and home directory:

```text
<active-workspace>/.claude/commands/**/*.md
<active-workspace>/.codex/prompts/**/*.md
~/.claude/commands/**/*.md
~/.codex/prompts/**/*.md
```

These external commands are prompt-only and read-only in Settings.

