# Real-Time Transport

Jupiter uses SSE where traffic mostly flows from server to browser, and WebSockets where it genuinely needs two-way traffic.

## Server-Sent Events

SSE carries:

- assistant text deltas
- assistant status
- tool-call lifecycle events
- completion and error events
- workspace-rail refreshes
- system balloons

Active assistant streams live on the server and can have browser emitters attach over their lifetime.

## WebSockets

The terminal needs bidirectional, low-latency traffic, so attach, input, output, and resize operations use WebSockets.

## Reverse proxies

If you put Jupiter behind a proxy, both transports need to survive it.

For SSE, turn off buffering where appropriate and don’t use tiny idle timeouts.

For WebSockets, enable upgrades and make sure authentication survives the handshake.

See [Reverse Proxy and HTTPS](Reverse-Proxy-and-HTTPS).
