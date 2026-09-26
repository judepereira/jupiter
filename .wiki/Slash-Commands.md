Slash commands are Markdown files. Jupiter's own commands use YAML frontmatter, and Jupiter can also discover
prompt-only commands from Claude Code and Codex. If there's a feature that you'd like to add, please create an issue for
it.

![Slash command picker](images/slash-commands.png)

## Where Commands Come From

Jupiter loads bundled commands and Jupiter's editable commands from:

```text
~/.jupiter/commands/*.md
```

It also discovers prompt-only, read-only commands from the active workspace and your home directory:

```text
<active-workspace>/.claude/commands/**/*.md
<active-workspace>/.codex/prompts/**/*.md
~/.claude/commands/**/*.md
~/.codex/prompts/**/*.md
```

Discovery happens when Jupiter serves the command catalog; there is no file watcher. Changes are picked up on the next
request that reads the catalog. A project command takes precedence over a home command only when its provider and
relative path are the same. Claude and Codex commands remain distinct even when their paths or names collide; their
provider-qualified internal IDs keep them separate.

## Two Command Types

**Prompt** commands put their body into the composer. You can review or edit it before sending.

**Script** commands run immediately and stream the result into chat. External Claude Code and Codex commands are always
prompt-only and read-only in Jupiter; they cannot be run as scripts or edited in Settings.

### Example

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

External files are more permissive: a Markdown file may contain only a body, or optional YAML frontmatter followed by a
body. Jupiter uses `name` and `description` when present and ignores unsupported execution settings. An empty body is
not shown.

## UI

Slash commands may also be added from the Settings. Go to **Settings → Commands** to view, edit, and add new commands.

## Bundled Commands

Bundled commands include:

- `/status` - runs `git status --short`.
- `/commit-push` - inserts a prompt asking the agent to commit and push the current changes.

Command IDs must be unique across bundled and user commands.
