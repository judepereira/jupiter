# Advanced Configuration

These settings are mainly useful when operating or tuning a Jupiter instance beyond the normal UI.

## Environment variables

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


## State paths

The default database is:

```text
~/.jupiter/jupiter.sqlite
```

User slash commands live under:

```text
~/.jupiter/commands/*.md
```
