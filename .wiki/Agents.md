# Agents

Jupiter agents are Markdown files with YAML frontmatter. This keeps the prompt, model preferences, and tool permissions
readable in one place instead of hiding agent behaviour in Java configuration.

![Agent, model, and thinking controls](images/agents-models-thinking.png)

## Primary agents

Jupiter ships with two primary agents:

- **Plan** - focused on planning and exploration, with read/search/image tools plus delegation through `task`.
- **Engineer** - the coding agent, with native/MCP tool access and delegation.

Their current model preferences live in the bundled agent definitions and may change independently of this
documentation. Agent-default runs use the first configured preference whose provider is available. See
[Models and Providers](Models-and-Providers) for model selection and fallback behaviour.

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

The composer lets you pick the agent, model, and thinking level for a turn. Without an explicit model or thinking
override, the selected agent's configured defaults apply.
