# Architecture

Jupiter is a Java 25 / Spring Boot 4.1.1 application. The architecture is intentionally fairly conventional; most of the interesting complexity is in the agent runtime and persistent UI state, not in inventing a frontend framework.

## Server

The server uses Spring MVC, Thymeleaf, HTMX integration, JDBC, Flyway, SQLite, WebSockets, and scheduled services.

LangChain4j is used for the OpenAI model client and MCP connectivity.

## Browser

Most UI is server-rendered HTML with HTMX plus small JavaScript modules where browser state or richer interaction is actually useful.

Bootstrap handles layout primitives, Marked + DOMPurify render assistant Markdown, Xterm.js renders terminals, and Chart.js handles usage charts.

## Persistence

`AppStateService` owns higher-level state behaviour. `AppStateRepository` is the SQLite boundary and also handles encrypted persisted fields.

## Agent runtime

`CodingAgentHarness` composes prompts, picks the model, exposes allowed native/MCP tools, streams runtime events, and feeds the resulting state back through the application services.

## Real-time bits

- chat: SSE
- workspace rail: SSE
- system balloons: SSE
- terminal: WebSocket

See [Real-Time Transport](Real-Time-Transport).

## Git

Projects point to repositories, workspaces are Git worktrees, and sessions operate against one workspace root.
