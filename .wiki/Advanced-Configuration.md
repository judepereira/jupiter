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

These external commands are prompt-only and read-only in Settings. Jupiter checks them when a command catalog request is
made rather than watching the filesystem. For the same provider and relative path, the active workspace file overrides
the home file; Claude and Codex paths are kept distinct.

