Subagents are how Jupiter delegates focused work without turning that work into a black box.

![Inspecting a subagent session](images/subagent-session.png)

## Bundled Subagents

Jupiter ships with:

- **Explore** - read-only codebase exploration.
- **Apprentice** - implementation work.
- **Test** - testing-focused work.

Their current model preferences live in the bundled agent definitions and may change independently of this
documentation. Model selection follows the same rules as primary agents; see
[Models and Providers](Models-and-Providers).

## Changed Files Bubble Up

If a subagent writes or patches files successfully, Jupiter records those changes in both the child and parent session.

This matters: delegating implementation shouldn’t make the parent review panel mysteriously incomplete.

## No Recursive Delegation

Subagents cannot receive Jupiter’s built-in `task` tool, so one subagent cannot spawn another through the native task
mechanism.

## Hooks

The **subagent completed** lifecycle hook runs after a non-cancelled subagent finishes, including error completion. See
[Lifecycle Hooks](Lifecycle-Hooks).
