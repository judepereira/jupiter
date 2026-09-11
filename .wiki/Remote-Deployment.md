# Remote Deployment

Remote access is the main reason I built Jupiter, but there’s an important distinction between “remote” and “publicly exposed to the internet”.

## My preferred setup: a private network

Run Jupiter on the machine containing your repositories, then expose port `7272` only over a trusted network or VPN.

Tailscale is an obvious example, but Jupiter doesn’t depend on it.

## If it must be public

Do all of these:

1. Enable [HTTP Authentication](HTTP-Authentication).
2. Put Jupiter behind a trusted TLS-terminating reverse proxy.
3. Forward the original HTTPS scheme correctly.
4. Keep Server-Sent Events alive.
5. Support WebSocket upgrades for the terminal.
6. Secure the host as if it were a development workstation — because effectively, it is.

See [Reverse Proxy and HTTPS](Reverse-Proxy-and-HTTPS).

## The trust model

A user who can successfully access Jupiter can run coding agents and use an interactive shell against your source environment.

Basic auth is an access gate. It is **not** per-user authorisation, repository ACLs, or sandboxing.

So, don’t share one Jupiter instance between mutually untrusted users.

## Backups

Back up `~/.jupiter/jupiter.sqlite` and the matching encryption key separately. See [Storage and Backups](Storage-and-Backups).
