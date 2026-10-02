Jupiter separates three choices: which providers are connected, which models are available in the picker, and which
model defaults an agent prefers.

![Agent, model, and thinking controls](images/agents-models-thinking.png)

## Connect OpenAI

OpenAI can be connected in two ways:

- set `OPENAI_API_KEY`
- open **Settings → Model Providers** and use the OpenAI browser/device authorisation flow

The device flow shows a user code and verification URL while Jupiter waits for authorisation to complete. OAuth state is
stored encrypted and restored across restarts. **Disconnect** clears that OAuth connection; a configured API key remains
available independently.

## Configure Claude

Claude is available only when an Anthropic API key is configured. Set `ANTHROPIC_API_KEY`, then restart Jupiter. Claude
account or subscription login is unsupported.

When upgrading Jupiter, carry the environment variable into the new process. Changing it while Jupiter is running has no
effect until restart.

## Choose Models

Jupiter loads supported model metadata from the configured models.dev catalogue for connected providers. Settings lets
you choose which models appear as additional chat choices.

You can mark models as favourites from Settings, and these will show in the chat composer as model options.

## Agent Defaults and Fallback

An agent can define one default model or an ordered preference list.

For an agent-default turn, Jupiter chooses the first preference whose provider is currently available. If the resulting
model request fails, Jupiter will retry the turn with the next preference. This permits seamless continuity during
outages.

An explicit model selection is strict: if that provider or model is unavailable, the turn fails instead of silently
switching models.

## Thinking Level

The composer lets you choose a thinking level for a primary turn. Supported models receive that level when the request
is sent. Without an explicit override, the selected agent's configured defaults apply.

## Treat Provider Credentials as Secrets

Provider credentials and OAuth state are sensitive even though Jupiter stores persisted provider state encrypted.
Protect the Jupiter host, database, and encryption key accordingly.
