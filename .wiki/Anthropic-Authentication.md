# Anthropic Authentication

Jupiter connects to Claude through the Claude Code hosted OAuth flow. No Anthropic API key is entered in Jupiter.

## Connect Claude

Open **Settings → Model Providers** and choose **Connect Claude**. Jupiter opens Anthropic's hosted authorisation page. Complete the sign-in there, copy the authentication code shown by the page, paste it into Jupiter, and choose **Complete authentication**.

The OAuth protocol is configurable through `anthropic.oauth.*` properties. The defaults target Claude Code's hosted flow; changing the protocol endpoints, client details, redirect URI, scopes, or code-challenge settings can make the flow incompatible with that service.

## Persistence and refresh

Access and refresh tokens, expiry, scopes, and account data are stored in encrypted database fields. Jupiter reloads the connection after a restart. When an access token is near expiry, it attempts a refresh; a failed refresh marks the connection as failed and the provider is unavailable until authentication succeeds again.

**Disconnect** clears the persisted Anthropic OAuth state and removes the connection. It does not delete model catalogue entries or favourites.

## Models and routing

Claude models are loaded alongside OpenAI GPT-5.6-series models from models.dev. Favourites are one global ordered list. The chat picker shows only favourited models whose provider is currently connected, in that order. If none qualify, it shows an empty picker and cannot send a chat turn.

A model's catalogue ID determines its provider and therefore the model client used for the turn. A model retained in historical session metadata can remain unavailable after it disappears from the catalogue or its provider is disconnected; it is not silently made available.

Tests use fixtures and mocked HTTP; they do not use live Anthropic credentials.
