# Agents

Jupiter agents are just Markdown files with YAML frontmatter. I prefer this over hiding agent behaviour in Java configuration because it keeps the prompt, model, and tool permissions readable in one place.

> TODO: add screenshot

## Primary agents

v1 ships with two:

- **Plan** — exposes read/search/image tools plus `task`; defaults to GPT-5.6 Sol with high reasoning.
- **Engineer** — the coding agent; gets wildcard native/MCP access and delegation, and defaults to GPT-5.6 Terra with medium reasoning.

There are also Explore, Apprentice, and Test subagents. See [Subagents](Subagents).

## Agent file format

An agent definition can contain:

- `id` and `name`
- `description`
- `mode`: `agent` or `subagent`
- default `model`
- `reasoningEffort`
- optional `textVerbosity`
- tool permissions
- a Markdown prompt body

## Tool permissions

The tool map decides what an agent can call directly. `*` expands to native tools plus `mcp:*`; primary agents also get the `task` tool.

One subtle point: Plan has no direct write or command tools, but it **can** delegate to configured subagents. Its “planning only” behaviour is therefore partly prompt-directed, not a transitive read-only sandbox.

Subagents never receive `task`, so native delegation cannot recurse forever.

The composer lets you pick agent, model, and thinking level per turn, and Jupiter persists those choices with the assistant message.
