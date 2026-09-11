# Context Compaction

Long-running coding sessions eventually hit model context limits. Jupiter deals with that by summarising older turns before the next request gets too large.

## When does it run?

Jupiter estimates the size of the prompt, conversation, new user text, tool schemas, and expected model output against the selected model’s context window.

The compaction threshold is capped at 250,000 estimated input tokens and also leaves output/headroom for smaller-context models.

## What gets kept?

At least the two most recent completed turns stay verbatim.

Older eligible turns are summarised with the selected model. The summary prompt asks the model to preserve decisions, paths, tool results, constraints, and open work rather than producing a pretty narrative.

The old turns are then excluded from future model context, and the summary is appended as a visible system message.

## Accounting

Compaction requests are recorded in token usage with the `compaction` operation, so they don’t get mixed invisibly into normal turns.

## What if it still doesn’t fit?

Jupiter fails explicitly instead of sending an oversized request and hoping the provider sorts it out.
