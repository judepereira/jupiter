# Models and Thinking

Agent and model are deliberately separate choices in Jupiter. An agent defines behaviour and tools; the model is what runs the turn.

![Agent, model, and thinking controls](images/agents-models-thinking.png)

## Model catalogue

Jupiter loads model metadata from the configured models.dev catalogue and keeps OpenAI GPT-5.6-series entries plus Anthropic Claude entries. Where available, it keeps the display name, context limit, output limit, reasoning support, tool-call support, and release date.

The hardcoded fallback model ID is `openai/gpt-5.6-sol`.

## Favourites and the chat picker

Model favourites are one global, ordered list across providers. Settings lets you add or remove catalogue models and preserves their order. The chat picker filters that list to models whose provider is currently connected. Thus disconnecting a provider can remove its models from the picker without removing the favourites.

If no favourited model belongs to a connected provider, the picker is empty and chat sending is disabled. A model selected in historical session metadata can also be unavailable if it is no longer in the catalogue or its provider is disconnected; Jupiter does not silently treat it as connected.

## Provider routing

A catalogue model ID includes its provider, such as `openai/...` or `anthropic/...`. Jupiter derives the provider from the selected model and routes the request to that provider's client; the active agent's default does not override this routing.

## Agent defaults

Bundled agents come with a preferred model and reasoning level, but the composer lets you override both for a primary turn.

## Thinking level

The selected thinking level is stored with the assistant message and passed into model requests that support it.

When you return to a session, Jupiter tries to restore the most recent assistant selection. If that model has disappeared from the catalogue, it falls back instead of leaving the UI in a broken state.

Provider tests use fixtures and mocks rather than live credentials.
