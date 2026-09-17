# Sessions and Chat

A session is a persisted coding conversation inside one workspace.

![Jupiter sessions and chat](images/sessions-and-chat.png)

## More than one session

You can create several sessions for the same workspace and switch between them from the rail. Messages, tool traces,
drafts, and review state all live on the server.

## Streaming and reconnects

Assistant text and tool activity stream while a turn is running. If the browser connection drops, reconnecting can
reattach to the active server-side turn instead of losing the work in progress.

## Stopping a turn

The stop button cancels the active assistant turn and any managed command execution attached to it.

## Drafts

Unsent composer text is saved per session, which means you can navigate elsewhere without losing half-written prompts.

## Message details

Completed assistant messages record the agent, model, thinking level, duration, and completion time.

## Forking

A completed primary assistant message can be forked into a new primary session from that point in the conversation.

Subagent conversations are inspectable too, but they don’t expose the primary-session fork action.

## Tool activity and images

Tool starts, progress, and results remain visible in chat and are persisted with the session.

![Tool calls and inline images](images/tool-calls-and-images.png)

A `task` call links to its subagent session so you can inspect delegated work directly. Agents can also display PNG,
JPEG, GIF, and WebP workspace images inline in chat.

## Context compaction

Long-running sessions can eventually approach a model’s context limit. Jupiter automatically summarizes older completed
turns while keeping recent work intact. The summary appears as a visible system message.

If the resulting request still cannot fit the model’s context window, Jupiter fails explicitly instead of sending an
oversized request.
