# Development and Testing

Jupiter targets Java 25 and uses Maven.

## Build it

```bash
./mvnw package
```

The current Spring Boot version is 4.1.1.

## Tests

There’s a fairly broad mix:

- service and utility unit tests
- SQLite integration tests
- Spring MVC and template-rendering tests
- agent harness and OpenAI-client tests
- MCP tests
- Git and lifecycle integration tests
- Playwright browser E2E tests

The Playwright tests cover the things I particularly don’t want silently breaking: project creation, workspace init, session rails, review sources, slash commands, subagents, OAuth, mobile settings, panels, connection-loss behaviour, and documentation screenshots. Normal `./mvnw test` and `./mvnw package` runs execute the documentation screenshot E2E; routine output is disposable under `target/documentation-screenshots`.

To refresh the tracked wiki image catalog, install Java 25 and run `scripts/generate_screenshots.sh`. The script installs Chromium (or Chromium plus Linux dependencies when `PLAYWRIGHT_INSTALL_DEPS=1`) and intentionally writes `.wiki/images`. Maven itself does not install browsers.

## Docker CI

The repository verification workflow builds the Docker image; the build stage runs Maven packaging and installs the browser dependencies needed by Playwright.

Blue Cave analysis is included when its token exists, but a missing token doesn’t make normal builds impossible.

## Frontend

The UI is mostly Thymeleaf + HTMX. The focused JavaScript modules live under `src/main/resources/static/js`.

Before changing persistence or security-sensitive code, read `AGENTS.md`; it contains the implementation invariants and test conventions that aren’t worth duplicating here.
