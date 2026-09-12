# Feature Inventory

This is the boring-but-useful v1 checklist. When a user-visible feature lands, represent it here and on the closest task-oriented page — otherwise documentation drift is almost guaranteed.

## Development workflow

- existing-directory projects
- server-side directory browser
- Git-worktree workspaces
- new-branch and existing-branch workspace modes
- workspace initialization commands
- safe workspace close checks
- manual Git pull
- scheduled fast-forward-only Git updates
- multiple persistent sessions per workspace
- session draft autosave
- conversation forking
- session and Git diff review sources
- multiple PTY terminal tabs
- browser reconnect output replay

## Agent harness

- declarative Markdown agents
- primary Plan and Engineer agents
- Explore, Apprentice, and Test subagents
- per-turn agent, model, and thinking controls
- dynamically loaded OpenAI GPT-5-family model catalogue
- native tool allowlists
- `list_files`, `read_file`, `search_code`, `write_file`, `apply_patch`, `display_image`, `run_command`, and `task`
- persisted subagent child sessions
- live and persisted tool traces
- automatic context compaction
- image display for PNG, JPEG, GIF, and WebP
- MCP server catalogue and per-project exposure
- MCP headers and `${env.NAME}` templates
- dynamic MCP tool refresh and collision detection

## Extensibility and automation

- bundled slash commands
- user commands under `~/.jupiter/commands`
- prompt and script command types
- project environment variables
- agent-command host environment allowlist
- assistant-completed lifecycle hook
- assistant-errored lifecycle hook
- subagent-completed lifecycle hook
- project/model token usage charts
- 60-day usage retention

## Web interface

- server-rendered Thymeleaf and HTMX UI
- SSE chat streaming
- SSE rail refreshes
- SSE system balloons
- WebSocket terminals
- Markdown rendering with DOMPurify sanitisation
- desktop and mobile layouts
- connection-loss overlay
- resizable panels
- global keyboard shortcuts

## Security and deployment

- mandatory 32-byte Base64 database encryption key
- stdin-based normal key bootstrap
- AES-256-GCM persisted sensitive values
- HMAC blind indexes
- encrypted OAuth state
- Linux `PR_SET_DUMPABLE=0`
- JVM attach disabled in supported launch paths
- sensitive Jupiter environment stripping for child processes
- restricted host environment for agent commands
- optional HTTP Basic authentication
- public HTTPS detection warning
- unauthenticated `/health` endpoint
- Docker deployment with configurable UID/GID
- root and user initialization scripts
- reverse-proxy support for SSE and WebSockets

## Persistence and quality

- SQLite with WAL and foreign keys
- Flyway schema migrations
- encrypted persistence migration
- unit and integration tests
- template-rendering tests
- Playwright browser E2E tests
