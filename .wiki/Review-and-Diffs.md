# Review and Diffs

There are two useful questions when reviewing agent work: “what did this session change?” and “what is actually different in Git?” Jupiter keeps both views.

![Reviewing session changes](images/review-and-diffs.png)

## Session changes

The **Session** source lists files Jupiter recorded as changed by agent write or patch tools.

If a subagent made the change, its files are propagated to the parent session as well, so delegation doesn’t make the parent review incomplete.

## Git changes

The **Git** source reads the active workspace’s current Git changes.

This is the broader truth and will also catch edits made outside the agent trace — for example, something you changed from the terminal.

## Remembering where you were

The selected review source, selected file, and panel visibility are persisted with the session.

The panel is deliberately just a diff inspection surface. Git history and external code-review tooling still do what they do best.
