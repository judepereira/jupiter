These settings are mainly useful when operating or tuning a Jupiter instance beyond the normal UI. Set
`ANTHROPIC_API_KEY` (or `anthropic.api-key`) to enable Claude; account or subscription login is unsupported. Restart
Jupiter after changing provider configuration. When upgrading, preserve the key in the new process environment or
configuration.

## Environment Variables

|           Variable           |                Purpose                 |     Default     |
|------------------------------|----------------------------------------|-----------------|
| `PORT`                       | Docker entrypoint HTTP port            | `7272`          |
| `JUPITER_ENCRYPTION_KEY`     | Required Docker startup encryption key | none            |
| `JUPITER_HTTP_AUTH_PASSWORD` | Enables HTTP Basic auth when nonblank  | blank           |
| `JUPITER_HTTP_AUTH_USERNAME` | Basic auth username                    | `jupiter`       |
| `OPENAI_API_KEY`             | OpenAI API credential                  | blank           |
| `ANTHROPIC_API_KEY`          | Anthropic API credential for Claude    | blank           |
| `USERNAME`                   | Container app user                     | `jupiter`       |
| `WITH_UID`                   | Container app UID                      | `1000`          |
| `WITH_GID`                   | Container app GID                      | `1000`          |
| `INIT_SCRIPT`                | Root container initialisation script   | `/init.sh`      |
| `INIT_USER_SCRIPT`           | User container initialisation script   | `/init-user.sh` |

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

