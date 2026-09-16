# Environment Variables

There are a few different environment boundaries in Jupiter, and mixing them up can either break tools or leak far more
than you intended.

## Bootstrap runtime variables

Native launchers may send runtime variables in the versioned `JUPITER_BOOTSTRAP_V1\0NAME\0VALUE\0` envelope.
Image-defined environment remains in the JVM environment; runtime-added variables are captured before Java starts,
removed from the JVM `/proc` environment, and exposed to Spring and trusted terminals (but never the encryption key).
Both container init scripts are trusted and receive the original environment, including the key. Agent `run_command`
does not inherit bootstrap variables.

## Project variables

Project environment variables are configured in project settings and persisted by Jupiter.

They’re used by terminals, agent commands, MCP template resolution, and lifecycle hooks where applicable.

## Agent `run_command`

This is the restrictive path.

An agent command starts with an **empty** environment. Jupiter copies only host variables named in the project’s
command-environment allowlist, overlays project variables, then removes Jupiter’s own sensitive credentials.

So if an agent suddenly can’t find a tool that works in your normal shell, check whether it needs `PATH` or another host
variable added to the allowlist.

## Integrated terminal

The interactive terminal starts from the broader Jupiter process environment, restores bootstrap runtime variables, then
overlays project variables. The encryption key is never restored.

Jupiter still removes its encryption and HTTP-authentication credentials first. `OPENAI_API_KEY` is not one of the
variables removed by that sanitizer, so it remains visible here if it exists in the Jupiter process environment.

This difference is deliberate: the terminal is directly controlled by the authenticated user; `run_command` is
model-generated execution.

## Lifecycle hooks

Hooks receive project variables plus:

```text
JUPITER_PROJECT_NAME
JUPITER_WORKSPACE_NAME
JUPITER_SESSION_NAME
```

Jupiter credentials are stripped here as well.

## MCP placeholders

MCP URLs and headers can use `${env.NAME}`. Resolution order is project variables, then JVM environment variables, then
bootstrap runtime variables. The encryption key is not a source for placeholders.
