This file is to be read and processed by AI agents. It contains coding guidelines, 
and other instructions for working with this project.

## Project Info
1. We run on Java 25 and Java 25 only - no need for backwards compatiblity
2. Java 25 is available under ~/.jdks
3. The project is a Spring boot project, using htmx for the entire frontend. 
   This is on purpose to simplify coding and building.
4. The build process for this project is Maven. Always use the bundled Maven wrapper (./mvnw)

## Project Structure & Architecture
This section is intentionally stable: document the repository layout and long-lived design decisions, not short-lived implementation details.

### Runtime and build
- Spring Boot application entry point: `src/main/java/com/judepereira/jupiter/Jupiter.java`.
- Maven is the only supported build tool; use `./mvnw` for all builds and tests.
- The app targets Java 25 and uses Lombok plus Java records heavily for DTOs and view models.
- Main runtime dependencies are Spring WebMVC, Thymeleaf, WebSocket, JDBC/Flyway, SQLite, Pty4J, and LangChain4j/OpenAI integrations.
- Frontend libraries are brought in via WebJars, not CDN URLs.

### Backend package layout
- `com.judepereira.jupiter.ui`: HTMX-facing MVC controllers plus UI-facing services/listeners.
- `com.judepereira.jupiter.ui.command`: thin command endpoint layer used by the browser UI.
- `com.judepereira.jupiter.ui.balloon` and `com.judepereira.jupiter.ui.rail`: SSE-backed UI side channels for system balloons and workspace rail refreshes.
- `com.judepereira.jupiter.terminal`: terminal session state, terminal lifecycle management, and the WebSocket handler for terminal I/O.
- `com.judepereira.jupiter.command`: command catalog, execution, and command streaming infrastructure.
- `com.judepereira.jupiter.agent`: agent orchestration, model selection, tool registration/execution, harnessing, and subagent task support.
- `com.judepereira.jupiter.persistence`: SQLite-backed application state, repositories, view projections, and persistence services.
- `com.judepereira.jupiter.config`: infrastructure wiring such as SQLite and WebSocket configuration.
- `com.judepereira.jupiter.openai.oauth`: OpenAI OAuth/device-flow support.

### Frontend and HTMX organization
- Server-rendered HTML lives in `src/main/resources/templates`.
- Reusable HTMX partials live in `src/main/resources/templates/fragments`.
- Static CSS lives in `src/main/resources/static/css`; static browser scripts live in `src/main/resources/static/js`.
- `index.html` is the main shell; most UI updates swap fragments rather than rendering a SPA.
- UI behavior is intentionally split between Thymeleaf fragments, HTMX requests, and small browser-side scripts for resize/keyboard/terminal behavior.
- Durable UI state should be persisted and rendered through the existing server-rendered Thymeleaf/HTMX path. Do not introduce feature-specific JavaScript rendering when the standard fragment rendering flow can represent the state.

### Agent and tooling architecture
- Agent prompt/persona definitions live under `src/main/resources/agents`.
- Command templates invoked by the UI live under `src/main/resources/commands`.
- `agent/harness` coordinates a single agent turn, tool call tracing, and system prompt composition.
- `agent/llm` abstracts model clients and request/response/tool-call mapping.
- `agent/tools` contains the tool contract plus concrete implementations for file, shell, search, patch, task, and image operations.
- `agent/catalog` provides the durable catalog of agent and model definitions used by the UI.

### Persistence and migrations
- SQLite is the application datastore.
- Flyway migrations live in `src/main/resources/db/migration` and are the source of truth for schema changes.
- Persistence is centered in `AppStateRepository`, `AppStateService`, and related view/record types in `Persistence.java`.
- Configuration files with the `.sql.conf` suffix are companion metadata for specific migration steps; keep them aligned with the matching SQL migration.
- Prefer schema evolution through migrations rather than ad hoc initialization code.

### Real-time interaction model
- Chat turns stream over SSE from the UI layer.
- Workspace rail refreshes and system balloons also use SSE channels.
- Terminal interaction uses WebSockets (`TerminalWebSocketHandler`) with state managed separately from the main chat stream.
- The UI may hold multiple live emitters per active stream; streaming state is coordinated through controller/service classes rather than the browser.
- Use SSE and WebSockets for genuinely live or transient behavior. Do not add a dedicated real-time channel solely to render durable state that will naturally appear through the normal server-rendered flow.
- Scheduling, concurrency, lifecycle, deduplication, and recovery semantics belong in backend services. Browser timing or browser-held state must not be required for correctness.
- Failures are surfaced loudly to the frontend instead of being silently retried or hidden.

### Testing conventions
- Unit and integration-style tests live under `src/test/java` and generally mirror production package layout.
- End-to-end browser tests live in `src/test/java/com/judepereira/jupiter/e2e` and use Playwright.
- Template rendering tests validate Thymeleaf fragments and pages without a browser.
- Shared test helpers live in `src/test/java/com/judepereira/jupiter/testsupport`.
- Tests should avoid reflection; expose production code with the narrowest sensible visibility instead.
- Isolate external processes and environment-dependent integrations behind small injectable boundaries. Test their policies, no-op cases, failures, deduplication, and recovery transitions without requiring the external environment.
- When a targeted test passes, run the full test suite afterward.

## Principles
1. Prefer the simplest existing end-to-end path (YAGNI, DRY, and KISS). Avoid parallel mechanisms for the same state or behavior, and remove superseded plumbing rather than preserving it.

## Coding guidelines
1. Keep business rules and state transitions in backend services. Treat frontend code as a thin rendering and interaction layer; do not duplicate server-owned state or add frontend-only fallbacks for backend behavior.
2. Fail loudly and observably: log errors and communicate them through the canonical user-visible path. Do not silently retry, swallow errors, or add fallback paths that obscure failures.
3. Use lombok and Java records wherever possible 
4. When adding browser/frontend library assets (for example xterm, marked, DOMPurify), use WebJars instead of external CDN URLs
5. Never use reflection in tests; open production visibility appropriately, preferably package-private, when tests need access.
6. Always run all tests after targeted tests pass
7. Prefer one constructor per production class. Do not add constructor overloads for defaults, optional dependencies, compatibility, test setup, or convenience. Use a record's canonical constructor; keep test defaults in test fixtures; use a named static factory for genuinely distinct construction semantics; use a parameter object or builder when direct construction becomes unclear. An overload is allowed only when required by a framework or external compatibility contract, and its exception must be documented.
