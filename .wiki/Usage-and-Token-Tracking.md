Jupiter keeps local token-usage history so you can answer a fairly basic question: which projects and models are
actually consuming tokens?

![Token usage dashboard](images/usage-and-token-tracking.png)

## What gets recorded?

When a model provider returns data, Jupiter stores input, output, total, cached-input, cache-write, and reasoning token
counts.

It also keeps the operation type and model information. If an agent preference falls back before a request, usage is
recorded against the model that actually handled the request.

Context-compaction usage is tracked separately so its overhead remains visible.

## Retention

Local usage history is retained for 60 days.
