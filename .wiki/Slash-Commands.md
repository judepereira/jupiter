# Slash Commands

Slash commands are Markdown files with YAML frontmatter. They’re deliberately simple enough that adding one doesn’t require touching Java.

> TODO: add screenshot

## Where commands come from

Jupiter loads bundled commands from the application and user commands from:

```text
~/.jupiter/commands/*.md
```

## Two command types

**Prompt** commands put their body into the composer. You can review or edit it before sending.

**Script** commands run immediately and stream the result into chat.

## Example

```markdown
---
id: status
name: Status
type: script
description: Show repository status
workingDir: .
timeoutSeconds: 30
---
git status --short
```

`type` is required and must be `prompt` or `script`. `workingDir` and `timeoutSeconds` are script-only options.

## Bundled commands

v1 includes:

- `/status` — runs `git status --short`.
- `/commit-push` — inserts a prompt asking the agent to commit and push the current changes.

Command IDs must be unique across bundled and user commands.
