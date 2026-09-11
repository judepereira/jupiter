# Health Checks

Jupiter has one deliberately boring health endpoint:

```http
GET /health
```

A healthy response is:

```json
{"status":"UP"}
```

## Caching

The response disables caching with `Cache-Control: no-store` and related headers.

## Authentication

`GET /health` is the only route exempt from Jupiter’s optional Basic-auth filter.

That makes it suitable for reverse proxies and orchestration probes without teaching those systems your Jupiter password.

## What does “UP” mean?

Only that the web application handled the health request.

It is not a deep diagnostic for Git, OpenAI, every repository, or every configured MCP server.
