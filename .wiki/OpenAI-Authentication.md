# OpenAI Authentication

Jupiter has two ways to authenticate with OpenAI: a normal API key, or the browser/device authorisation flow.

## API key

Set:

```bash
OPENAI_API_KEY=...
```

The equivalent Spring property is `openai.api-key`.

## Browser authorisation

Open **Settings** and start the OpenAI connection flow.

![OpenAI device authorisation](images/openai-authentication.png)

Jupiter shows a user code and verification URL, then polls until the authorisation finishes.

The resulting access, refresh, and ID tokens are stored in encrypted database fields. **Disconnect** clears that persisted OAuth state.

## Which credential wins?

If a connected device-flow access token is present, Jupiter uses it with the ChatGPT Codex backend.

If not, it falls back to `openai.api-key` / `OPENAI_API_KEY` and the OpenAI API endpoint.

## Treat both as secrets

An API key is obviously a secret; the device-flow tokens are too. Protect the Jupiter host, database, and encryption key accordingly.
