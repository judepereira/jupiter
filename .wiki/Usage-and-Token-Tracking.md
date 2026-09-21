# Usage and Token Tracking

Jupiter keeps local token-usage history so you can answer a fairly basic question: which projects and models are
actually consuming the tokens?

![Token usage dashboard](images/usage-and-token-tracking.png)

## Time ranges

The usage view supports:

- 24 hours
- 7 days
- 30 days
- 60 days

Hourly usage is grouped by project and model. The chart stacks input tokens below output tokens in one combined bar for
each hour; each model keeps its own color, while translucent segments identify output tokens.

## What gets recorded?

When the provider returns the data, Jupiter stores input, output, total, cached-input, cache-write, and reasoning token
counts.

It also keeps the operation type and model information. If an agent preference falls back before a request, usage is
recorded against the model that actually handled the request.

Context-compaction usage is tracked separately so its overhead remains visible.

## Retention

Local usage history is retained for 60 days.

## Is this billing data?

No. This is Jupiter’s local accounting based on provider response metadata, not an OpenAI invoice.
