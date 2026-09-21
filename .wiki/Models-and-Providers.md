# Models and Providers

Jupiter separates three choices: which providers are connected, which models are available in the picker, and which
model defaults an agent prefers.

![Agent, model, and thinking controls](images/agents-models-thinking.png)

## Connect OpenAI

OpenAI can be connected in either of two ways:

- set `OPENAI_API_KEY` (or the equivalent `openai.api-key` Spring property)
- open **Settings → Model Providers** and use the OpenAI browser/device authorisation flow

The device flow shows a user code and verification URL while Jupiter waits for authorisation to complete. OAuth state is
stored encrypted and restored across restarts. **Disconnect** clears that OAuth connection; a configured API key remains
available independently.

## Connect Claude

Open **Settings → Model Providers** and choose **Connect Claude**. Jupiter opens Anthropic's hosted authorisation page.
Complete sign-in there, copy the authentication code, paste it back into Jupiter, and choose **Complete
authentication**.

Claude OAuth state is stored encrypted and restored across restarts. **Disconnect** removes the saved connection.
Jupiter does not require an Anthropic API key for this flow.

## Choose models

Jupiter loads supported model metadata from the configured models.dev catalogue for connected providers. Settings lets
you choose which models appear as additional chat choices, and those selections persist across disconnect and reconnect.

Models referenced by configured agents remain available while their provider is connected, even when they are not part
of the provider's additional selection. Catalogue compatibility does not guarantee that your provider account is
entitled to use a particular model.

## Agent defaults and fallback

An agent can define one default model or an ordered preference list.

For an agent-default turn, Jupiter chooses the first preference whose provider is currently available. If the resulting
model request fails, Jupiter does not retry the turn with the next preference.

An explicit model selection is strict: if that provider or model is unavailable, the turn fails instead of silently
switching models.

## Thinking level

The composer lets you choose a thinking level for a primary turn. Supported models receive that level when the request
is sent. Without an explicit override, the selected agent's configured defaults apply.

## Treat provider credentials as secrets

Provider credentials and OAuth state are sensitive even though Jupiter stores persisted provider state encrypted.
Protect the Jupiter host, database, and encryption key accordingly.
