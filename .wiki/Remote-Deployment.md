Remote access is a primary use case for Jupiter, but remote does not have to mean publicly exposed to the internet.

## Recommended Setup: Private Network

Run Jupiter on the machine containing your repositories, then expose port `7272` only over a trusted network or VPN.
Tailscale is an option, but Jupiter does not depend on it.

## Public Deployment

If Jupiter must be internet-accessible, enable HTTP authentication and put it behind HTTPS.

Set a non-empty password:

```bash
JUPITER_HTTP_AUTH_PASSWORD='use-a-strong-secret'
```

The default username is `jupiter`; change it with `JUPITER_HTTP_AUTH_USERNAME`.

Authentication protects everything except `GET /health`.

## Reverse Proxy Requirements

A TLS-terminating reverse proxy must support:

- normal HTTP requests
- long-lived Server-Sent Events
- WebSocket upgrades for terminals

Avoid aggressive buffering and short idle timeouts on streaming routes.

Forward the original HTTPS scheme using `Forwarded` or `X-Forwarded-Proto`. Jupiter uses that information when checking
whether authenticated traffic is publicly using HTTPS.

Basic authentication does not encrypt traffic, so use HTTPS outside a trusted local network.

## Trust Model

Anyone who can access Jupiter can run agents and use an interactive shell against your source environment. Do not share
one instance between mutually untrusted users.

## Health Checks

Jupiter exposes an unauthenticated probe endpoint:

```http
GET /health
```

A healthy response is:

```json
{"status":"UP"}
```

`UP` means the web application handled the request. It does not verify Git, model providers, repositories, or MCP
servers.

## Backups

Back up `~/.jupiter/jupiter.sqlite` and its matching encryption key separately. See
[Storage and Backups](Storage-and-Backups).
