# Agents

Jupiter agents are just Markdown files with YAML frontmatter. This keeps the prompt, model, and tool permissions
readable in one place instead of hiding agent behaviour in Java configuration.

![Agent, model, and thinking controls](images/agents-models-thinking.png)

## Primary agents

v1 ships with two primary agents:

- **Plan** — exposes read/search/image tools plus `task`; prefers GPT-5.6 Sol, then Claude Opus 5, with high reasoning.
- **Engineer** — the coding agent; gets wildcard native/MCP access and delegation; prefers GPT-5.6 Terra, then Claude
  Opus 5, with medium reasoning.

The model list is ordered. Agent-default runs choose the first model whose provider is available before sending a
request; an explicit user model remains strict. See [Models and Thinking](Models-and-Thinking) for fallback and
attribution details.

There are also Explore, Apprentice, and Test subagents. See [Subagents](Subagents).

## Agent file format

An agent definition can contain:

- `id` and `name`
- `description`
- `mode`: `agent` or `subagent`
- default `model` (a single model ID or a comma-separated, ordered preference list)
- `reasoningEffort`
- optional `textVerbosity`
- tool permissions
- a Markdown prompt body

## Tool permissions

The tool map decides what an agent can call directly. `*` expands to native tools plus `mcp:*`; primary agents also get
the `task` tool.

One subtle point: Plan has no direct write or command tools, but it **can** delegate to configured subagents. Its
“planning only” behaviour is therefore partly prompt-directed, not a transitive read-only sandbox.

Subagents never receive `task`, so native delegation cannot recurse forever.

The composer lets you pick agent, model, and thinking level per turn. The selected agent's frontmatter is authoritative
for the initial and reset model and Thinking defaults; historical per-turn metadata does not control composer defaults.
A model named in connected agent frontmatter remains available even when it is absent from that provider's Settings
selection, while provider selection still filters additional chat choices.
