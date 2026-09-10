# Subagents

Subagents are how Jupiter delegates focused work without turning that work into a black box.

> TODO: add screenshot

## What ships with v1?

- **Explore** — read-only codebase exploration.
- **Apprentice** — implementation work.
- **Test** — testing-focused work.

All three currently default to GPT-5.6 Luna with medium reasoning.

## A task is a real child session

When a primary agent calls `task`, Jupiter creates a persisted child session linked to the parent session, parent tool call, and selected subagent.

That child session stores its own messages and tool traces. You can open it from the parent task call and inspect what it actually did.

## Changed files bubble up

If a subagent writes or patches files successfully, Jupiter records those changes in both the child and parent session.

This matters: delegating implementation shouldn’t make the parent review panel mysteriously incomplete.

## No recursive delegation

Subagents cannot receive Jupiter’s built-in `task` tool, so one subagent cannot spawn another through the native task mechanism.

## Hooks

The **subagent completed** lifecycle hook runs after a non-cancelled subagent finishes, including error completion. See [Lifecycle Hooks](Lifecycle-Hooks).
