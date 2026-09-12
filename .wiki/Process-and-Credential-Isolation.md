# Process and Credential Isolation

A coding harness launches a lot of child processes. Jupiter’s own credentials must not casually appear in every `env`, shell, Git process, or crash dump along the way.

## Linux hardening

On Linux, startup calls:

```text
prctl(PR_SET_DUMPABLE, 0)
```

If Jupiter cannot apply that, startup fails.

The recommended Java launch also uses:

```text
-XX:+DisableAttachMechanism
```

## Variables Jupiter strips

Before managed child processes launch, Jupiter removes:

```text
JUPITER_ENCRYPTION_KEY
JUPITER_HTTP_AUTH_PASSWORD
JUPITER_HTTP_AUTH_USERNAME
```

This applies across agent commands, terminals, Git operations, lifecycle hooks, and the other managed launch paths that use the sanitizer.

## Agent commands are stricter

`run_command` starts from an empty environment. Only explicitly allowlisted host variables are copied before project variables are added.

## The terminal is broader

The user-controlled terminal intentionally inherits the wider host environment, after Jupiter’s own sensitive variables are removed.

## The boundary

This protects those specific Jupiter credentials from inheritance. It does **not** magically discover and remove every unrelated secret you may already have in the host environment. In particular, `OPENAI_API_KEY` is not part of this sanitizer, so a child process that otherwise inherits it can still see it.
