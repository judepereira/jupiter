# Tool Calls and Images

I don’t want an agent saying “done” while all of the interesting work is hidden somewhere else. Jupiter keeps tool execution visible during the turn and persists it afterwards.

> TODO: add screenshot

## Live tool state

Tool start, progress, and completion events stream alongside assistant text.

Persisted traces include identifiers, tool names, success state, input/output previews, and selected machine-readable details. Large groups can be loaded lazily in the chat UI.

## Subagent calls

A `task` tool call links directly to its persisted subagent session, so you can inspect delegated work rather than trusting a one-line summary.

## Images

`display_image` can show workspace images directly in chat.

Supported formats are PNG, JPEG, GIF, and WebP.

Jupiter validates the media type before serving the file and sends displayed images with `X-Content-Type-Options: nosniff` and `Cache-Control: no-store`.
