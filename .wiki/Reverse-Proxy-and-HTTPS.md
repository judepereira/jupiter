# Reverse Proxy and HTTPS

Jupiter works behind a TLS-terminating reverse proxy, but the proxy needs to understand that not every request is a short-lived HTML response.

## Three things the proxy must support

- normal HTTP requests
- long-lived Server-Sent Events
- WebSocket upgrades for terminals

Avoid aggressive response buffering and short idle timeouts on streaming routes.

## Forward the public scheme

When Basic authentication is enabled, Jupiter warns if the request appears to be plain HTTP.

If TLS ends at your reverse proxy, forward the original scheme using `Forwarded` or `X-Forwarded-Proto`.

Jupiter understands the usual comma-separated proxy-header form when deciding whether the public request was HTTPS.

## Basic auth still needs TLS

Credentials are sent on every protected request, including static resources, SSE connections, and the WebSocket handshake.

Basic auth does not encrypt any of that. Use HTTPS outside a trusted local network.

## Health checks

`GET /health` is deliberately unauthenticated, which makes it suitable for reverse-proxy and orchestration probes.
