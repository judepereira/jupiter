There are two useful questions when reviewing agent work: “what did this session change?” and “what is actually
different in Git?” Jupiter keeps both views.

![Reviewing session changes](images/review-and-diffs.png)

## Session Changes

The **Session** source lists files Jupiter recorded as changed by agent write or patch tools.

If a subagent made the change, its files are propagated to the parent session as well, so delegation doesn’t make the
parent review incomplete.

## Git Changes

The **Git** source reads the active workspace’s current Git changes.

This is the broader truth and will also catch edits made outside the agent trace - for example, something you changed
from the terminal.

## Remembering Where You Were

The selected review source, selected file, and panel visibility are persisted with the session.

The review panel is intentionally focused on inspecting diffs. Git history and external code-review tooling remain
separate concerns.
