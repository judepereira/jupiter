# Models and Thinking

Agent and model are separate choices in Jupiter. An agent defines behaviour and tools; the model runs the turn.

![Agent, model, and thinking controls](images/agents-models-thinking.png)

## Model catalogue

Jupiter loads supported model metadata from the configured models.dev catalogue for connected providers.
The catalogue supplies names and capabilities used by the model picker.

## Provider selections

Settings lets you choose which models from each connected provider appear as additional chat choices.

On first connection, Jupiter selects a small set of recent eligible models automatically. Your selections persist when
a provider disconnects and reappear when it reconnects.

Models referenced by configured agents remain available when their provider is connected, even if they are not part of
the provider's additional selection.

## Agent defaults and fallback

An agent can define one default model or an ordered preference list.

For an agent-default turn, Jupiter uses the first preference whose provider is currently available. It does not retry a
failed model request using the next preference.

An explicit model selection is strict: if its provider is unavailable, the turn fails instead of silently switching
models.

## Thinking level

The composer lets you choose the thinking level for a primary turn. Supported models receive the selected level when the
request is sent.

Returning to a session uses the selected agent's configured model and thinking defaults. An explicit composer override
applies to that turn.
