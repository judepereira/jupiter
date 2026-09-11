# Sessions and Chat

A session is a persisted coding conversation inside one workspace.

![Jupiter sessions and chat](images/sessions-and-chat.png)

## More than one session

You can create several sessions for the same workspace and switch between them from the rail. Messages, tool traces, drafts, and review state all live on the server.

## Streaming and reconnects

Assistant text and tool activity stream over Server-Sent Events.

Jupiter persists partial assistant output while a turn is running. If the browser connection drops, the UI can attach again to the active server-side stream instead of pretending the turn never happened.

## Stopping a turn

The stop button cancels the active assistant turn. Managed command execution observes the same cancellation path.

## Drafts

Unsent composer text is saved per session, which means you can navigate elsewhere without losing half-written prompts.

## Message details

Completed assistant messages record the agent, model, thinking level, duration, and completion time. Markdown is rendered with Marked and sanitized with DOMPurify.

## Forking

A completed primary assistant message can be forked into a new primary session from that point in the conversation.

Subagent conversations are inspectable too, but they don’t expose the primary-session fork action.

Long session? Jupiter can compact older context automatically; see [Context Compaction](Context-Compaction).
