package com.judepereira.jupiter.e2e;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Browser coverage for provider availability and the selected-model picker.
 */
class AnthropicModelsE2ETest extends E2ETestSupport {

    @Test
    void providersModelsAndFavouritesUseMockedOAuthEndpoints(@TempDir Path tempDir) throws Exception {
        Path home = Files.createDirectories(tempDir.resolve("home"));
        Path project = Files.createDirectories(home.resolve("child-project"));
        Path db = tempDir.resolve("db/jupiter.db");
        Files.createDirectories(db.getParent());
        try (FixtureServer fixture = FixtureServer.start();
                RunningApp app = startApp(home, db,
                        Map.of("models.dev.catalog-url", fixture.url("/catalog.json"), "openai.api-key", "",
                                "openai.oauth.issuer", fixture.baseUrl(), "openai.oauth.client-id", "e2e-openai",
                                "anthropic.oauth.authorization-url", fixture.url("/claude/authorize"),
                                "anthropic.oauth.token-url", fixture.url("/claude/token"), "anthropic.oauth.client-id",
                                "e2e-anthropic", "anthropic.oauth.redirect-uri", fixture.url("/claude/callback")));
                BrowserContext context = newBrowserContext()) {
            Page page = context.newPage();
            page.navigate(app.baseUrl());
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("New tab")).waitFor();
            openProject(page, "Alpha", project);
            page.navigate(app.baseUrl());
            openSettings(page);

            // OpenAI is connected through the existing mocked device flow.
            page.waitForResponse(
                    response -> response.url().contains("/ui/settings/openai/start") && response.status() == 200,
                    () -> page
                            .getByRole(AriaRole.BUTTON,
                                    new Page.GetByRoleOptions().setName("Connect ChatGPT/OpenAI subscription"))
                            .click());
            assertThat(page.locator("#openai-oauth-section")).containsText("Status: Connected");

            // Starting Claude exposes the exact local authorization URL without visiting
            // it.
            assertThat(page.locator("#settings-model-providers")).isVisible();
            page.waitForResponse(
                    response -> response.url().contains("/ui/settings/anthropic/start") && response.status() == 200,
                    () -> page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Connect Claude"))
                            .click());
            Locator authorizationLink = page.locator("#anthropic-oauth-section a.settings-anthropic-authorization");
            assertThat(authorizationLink).hasAttribute("href", authorizationLink.getAttribute("href"));
            String authorizationUrl = authorizationLink.getAttribute("href");
            assertThat(authorizationUrl).startsWith(fixture.url("/claude/authorize"));
            String state = URI.create(authorizationUrl).getQuery().replaceFirst(".*(?:^|&)state=([^&]+).*", "$1");
            page.locator("#anthropic-oauth-section input[name='code']").fill("local-code#" + state);
            page.waitForResponse(
                    response -> response.url().contains("/ui/settings/anthropic/complete") && response.status() == 200,
                    () -> page
                            .getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Complete authentication"))
                            .click());
            assertThat(page.locator("#anthropic-oauth-section")).containsText("Connected");
            assertThat(fixture.anthropicTokenCalls.get()).isEqualTo(1);

            page.locator("#settings-modal .btn-close").click();
            page.waitForResponse(
                    response -> response.url().contains("/ui/projects/") && response.url().contains("/activate")
                            && response.status() == 200,
                    () -> page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Alpha")).click());
            assertPicker(page, "openai/gpt-5.6-sol|OpenAI Test", "openai/gpt-5.6-terra|Terra",
                    "openai/gpt-5.6-luna|Luna", "anthropic/claude-sonnet-5|Claude Sonnet Test",
                    "anthropic/claude-opus-5|Claude Opus Test");

            openSettings(page);
            Locator anthropicSelections = page.locator("form.settings-model-selections").filter(
                    new Locator.FilterOptions().setHas(page.locator("input[name='provider'][value='anthropic']")));
            anthropicSelections.locator("select[name='modelId']").selectOption("anthropic/claude-sonnet-5");
            page.waitForResponse(response -> response.url().contains("/ui/settings/models") && response.status() == 200,
                    () -> anthropicSelections.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Save"))
                            .click());
            assertThat(page.locator("#settings-models")).hasCount(1);
            assertThat(page.locator("#settings-models form.settings-model-selections")
                    .filter(new Locator.FilterOptions()
                            .setHas(page.locator("input[name='provider'][value='anthropic']")))
                    .locator("select[name='modelId']")).hasValues(new String[]{"anthropic/claude-sonnet-5"});

            // The first OOB replacement replaced the form, so reacquire it before the
            // second save.
            Locator secondAnthropicSelections = page.locator("form.settings-model-selections").filter(
                    new Locator.FilterOptions().setHas(page.locator("input[name='provider'][value='anthropic']")));
            secondAnthropicSelections.locator("select[name='modelId']").selectOption("anthropic/claude-opus-5");
            page.waitForResponse(response -> response.url().contains("/ui/settings/models") && response.status() == 200,
                    () -> secondAnthropicSelections
                            .getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Save")).click());
            assertThat(page.locator("#settings-models")).hasCount(1);
            assertThat(page.locator("#settings-models form.settings-model-selections")
                    .filter(new Locator.FilterOptions()
                            .setHas(page.locator("input[name='provider'][value='anthropic']")))
                    .locator("select[name='modelId']")).hasValues(new String[]{"anthropic/claude-opus-5"});
            page.locator("#settings-modal .btn-close").click();
            assertPicker(page, "openai/gpt-5.6-sol", "openai/gpt-5.6-terra", "openai/gpt-5.6-luna",
                    "anthropic/claude-sonnet-5", "anthropic/claude-opus-5");

            // Disconnecting removes only that provider's model; reconnecting preserves the
            // selection.
            openSettings(page);
            page.waitForResponse(
                    response -> response.url().contains("/ui/settings/openai/logout") && response.status() == 200,
                    () -> page
                            .getByRole(AriaRole.BUTTON,
                                    new Page.GetByRoleOptions().setName("Disconnect ChatGPT/OpenAI subscription"))
                            .click());
            assertThat(page.locator("#openai-oauth-section")).containsText("not connected");
            page.locator("#settings-modal .btn-close").click();
            assertPicker(page, "anthropic/claude-sonnet-5|Claude Sonnet Test",
                    "anthropic/claude-opus-5|Claude Opus Test");

            openSettings(page);
            page.waitForResponse(
                    response -> response.url().contains("/ui/settings/anthropic/disconnect")
                            && response.status() == 200,
                    () -> page.getByRole(AriaRole.BUTTON,
                            new Page.GetByRoleOptions().setName("Disconnect").setExact(true)).click());
            assertThat(page.locator("#anthropic-oauth-section")).containsText("not connected");
            page.locator("#settings-modal .btn-close").click();
            assertPicker(page);
            openSettings(page);
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Connect Claude")).click();
            String reconnectUrl = page.locator("#anthropic-oauth-section a.settings-anthropic-authorization")
                    .getAttribute("href");
            String reconnectState = URI.create(reconnectUrl).getQuery().replaceFirst(".*(?:^|&)state=([^&]+).*", "$1");
            page.locator("#anthropic-oauth-section input[name='code']").fill("local-code#" + reconnectState);
            page.waitForResponse(
                    response -> response.url().contains("/ui/settings/anthropic/complete") && response.status() == 200,
                    () -> page
                            .getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Complete authentication"))
                            .click());
            assertThat(page.locator("#anthropic-oauth-section")).containsText("Connected");
            page.locator("#settings-modal .btn-close").click();
            assertPicker(page, "anthropic/claude-sonnet-5|Claude Sonnet Test",
                    "anthropic/claude-opus-5|Claude Opus Test");

            openSettings(page);
            Locator reconnectedAnthropicSelections = page.locator("form.settings-model-selections").filter(
                    new Locator.FilterOptions().setHas(page.locator("input[name='provider'][value='anthropic']")));
            assertThat(reconnectedAnthropicSelections.locator("select[name='modelId']"))
                    .hasValue("anthropic/claude-opus-5");
        }
    }

    private static void openSettings(Page page) {
        page.waitForResponse(response -> response.url().contains("/ui/settings") && response.status() == 200,
                () -> page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Settings")).click());
        assertThat(page.locator("#settings-modal")).isVisible();
        page.locator("#settings-model-providers-tab").click();
        page.locator("#settings-model-providers.show.active").waitFor();
    }

    private static void assertPicker(Page page, String... expected) {
        var options = page.locator("#chat-model-select option");
        if (expected.length == 0) {
            assertThat(options).hasCount(1);
            assertThat(options).hasText("No models available");
        } else {
            assertThat(options).hasCount(expected.length);
            for (String expectedOption : expected) {
                String[] parts = expectedOption.split("\\|", 2);
                assertThat(options.evaluateAll("options => options.map(o => o.value)").toString()).contains(parts[0]);
                if (parts.length == 2)
                    assertThat(options.allTextContents().toString()).contains(parts[1]);
            }
        }
    }

    private static final class FixtureServer implements AutoCloseable {
        private final HttpServer server;
        private final AtomicInteger openAiPollCalls = new AtomicInteger();
        private final AtomicInteger anthropicTokenCalls = new AtomicInteger();

        private FixtureServer(HttpServer server) {
            this.server = server;
        }

        static FixtureServer start() throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            FixtureServer fixture = new FixtureServer(server);
            server.createContext("/catalog.json", e -> respond(e, 200, CATALOG));
            server.createContext("/api/accounts/deviceauth/usercode", e -> respond(e, 200,
                    "{\"device_auth_id\":\"device\",\"user_code\":\"CODE\",\"interval\":1,\"expires\":600}"));
            server.createContext("/api/accounts/deviceauth/token", e -> {
                if (fixture.openAiPollCalls.incrementAndGet() == 1) {
                    respond(e, 403, "");
                } else {
                    respond(e, 200,
                            "{\"authorization_code\":\"auth\",\"code_challenge\":\"challenge\",\"code_verifier\":\"verifier\"}");
                }
            });
            server.createContext("/oauth/token", e -> respond(e, 200,
                    "{\"access_token\":\"openai-access\",\"refresh_token\":\"openai-refresh\",\"id_token\":\"id\"}"));
            server.createContext("/claude/token", e -> {
                fixture.anthropicTokenCalls.incrementAndGet();
                respond(e, 200,
                        "{\"access_token\":\"claude-access\",\"refresh_token\":\"claude-refresh\",\"expires_in\":3600}");
            });
            server.start();
            return fixture;
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }
        String url(String path) {
            return baseUrl() + path;
        }
        @Override
        public void close() {
            server.stop(0);
        }

        private static void respond(HttpExchange exchange, int status, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        }
    }

    private static final String CATALOG = """
            {"models": {
              "openai/gpt-5.6-sol": {"id":"openai/gpt-5.6-sol","name":"OpenAI Test","reasoning":false,"tool_call":true,"limit":{"context":1000,"output":100}},
              "openai/gpt-5.6-terra": {"id":"openai/gpt-5.6-terra","name":"Terra","reasoning":true,"tool_call":true,"limit":{"context":1000,"output":100}},
              "openai/gpt-5.6-luna": {"id":"openai/gpt-5.6-luna","name":"Luna","reasoning":true,"tool_call":true,"limit":{"context":1000,"output":100}},
              "anthropic/claude-sonnet-5": {"id":"anthropic/claude-sonnet-5","name":"Claude Sonnet Test","reasoning":true,"tool_call":true,"release_date":"2026-01-01","limit":{"context":1000,"output":100}},
              "anthropic/claude-opus-5": {"id":"anthropic/claude-opus-5","name":"Claude Opus Test","reasoning":true,"tool_call":true,"limit":{"context":1000,"output":100}}
            }}
            """;
}
