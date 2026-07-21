package com.judepereira.jupiter2.e2e;

import com.judepereira.jupiter2.agent.harness.AgentTurnRequest;
import com.judepereira.jupiter2.agent.harness.AgentTurnResult;
import com.judepereira.jupiter2.agent.harness.CodingAgentHarness;
import com.judepereira.jupiter2.agent.llm.AgentStreamListener;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.AriaRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiOAuthE2ETest extends E2ETestSupport {

    @Test
    void openaiSubscriptionDeviceFlowShowsDeviceCodeAndPollsUntilConnected(@TempDir Path tempDir) throws Exception {
        Path fakeHome = Files.createDirectories(tempDir.resolve("fake-home"));
        Path projectDir = Files.createDirectories(fakeHome.resolve("child-project"));
        Path dbFile = tempDir.resolve("h2db/jupiter");
        Files.createDirectories(dbFile.getParent());

        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", fakeHome.toString());

        try (TestServer server = TestServer.start();
             Playwright playwright = Playwright.create();
             Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
             RunningApp app = startApp(fakeHome, dbFile, Map.of(
                      "openai.oauth.issuer", server.baseUrl(),
                      "openai.oauth.client-id", "e2e-client"
               ), TestAppConfig.class);
             BrowserContext context = browser.newContext()) {

            Page page = context.newPage();

            assertThat(app.context().getEnvironment().getProperty("openai.oauth.client-id")).isEqualTo("e2e-client");
            assertThat(app.context().getEnvironment().getProperty("openai.oauth.issuer")).isEqualTo(server.baseUrl());

            page.navigate(app.baseUrl());
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("New tab")).waitFor();

            openProject(page, "Alpha", projectDir);

            page.waitForResponse(
                    response -> response.url().contains("/ui/settings") && response.status() == 200,
                    () -> page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Settings")).click());
            com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator("#settings-modal")).isVisible();

            page.waitForResponse(
                    response -> response.url().contains("/ui/settings/openai/start") && response.status() == 200,
                    () -> page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Connect ChatGPT/OpenAI subscription")).click());

            com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator(".settings-openai-user-code")).hasText("ABCD-EFGH");
            com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator(".settings-openai-device-flow")).containsText(server.url("/codex/device"));

            com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator("#openai-oauth-section")).containsText("Status: Connected");
            assertThat(server.deviceCalls.get()).isEqualTo(1);
            assertThat(server.deviceRequestMethod).isEqualTo("POST");
            assertThat(server.deviceRequestBody).isNotNull();
            assertThat(server.deviceRequestBody).contains("\"client_id\":\"e2e-client\"");
            assertThat(server.authorizationCalls.get()).isGreaterThanOrEqualTo(2);
            assertThat(server.tokenCalls.get()).isEqualTo(1);
            assertThat(server.authorizationRequestBodies).hasSizeGreaterThanOrEqualTo(2);
            assertThat(server.tokenRequestMethods).allMatch("POST"::equals);
            assertThat(server.tokenRequestBodies.get(0)).isEqualTo(encodedFormBody(
                    "grant_type", "authorization_code",
                    "code", "auth-123",
                    "redirect_uri", server.baseUrl() + "/deviceauth/callback",
                    "client_id", "e2e-client",
                    "code_verifier", "verifier-456"
            ));
            assertThat(server.tokenRequestBodies).hasSize(1);

            page.locator("#settings-modal .btn-close").click();
            page.waitForResponse(
                    response -> response.url().contains("/ui/settings") && response.status() == 200,
                    () -> page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Settings")).click());

            com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator("#openai-oauth-section")).containsText("Status: Connected");
            com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Disconnect ChatGPT/OpenAI subscription"))).isVisible();

            page.waitForResponse(
                    response -> response.url().contains("/ui/settings/openai/logout") && response.status() == 200,
                    () -> page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Disconnect ChatGPT/OpenAI subscription")).click());

            com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator("#openai-oauth-section")).containsText("OpenAI is not connected.");
            com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Connect ChatGPT/OpenAI subscription"))).isVisible();
        } finally {
            if (previousHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", previousHome);
            }
        }
    }

    @Test
    void openaiSubscriptionPersistedStateIsRestoredInUiAfterRestart(@TempDir Path tempDir) throws Exception {
        Path fakeHome = Files.createDirectories(tempDir.resolve("fake-home"));
        Path projectDir = Files.createDirectories(fakeHome.resolve("child-project"));
        Path dbFile = tempDir.resolve("h2db/jupiter");
        Files.createDirectories(dbFile.getParent());

        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", fakeHome.toString());

        try (TestServer server = TestServer.start();
             Playwright playwright = Playwright.create();
             Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true))) {

            try (RunningApp first = startApp(fakeHome, dbFile, Map.of(
                    "openai.oauth.issuer", server.baseUrl(),
                    "openai.oauth.client-id", "e2e-client"
            ), TestAppConfig.class);
                 BrowserContext context = browser.newContext()) {

                Page page = context.newPage();
                page.navigate(first.baseUrl());
                page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("New tab")).waitFor();

                openProject(page, "Alpha", projectDir);

                page.waitForResponse(
                        response -> response.url().contains("/ui/settings") && response.status() == 200,
                        () -> page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Settings")).click());
                page.waitForResponse(
                        response -> response.url().contains("/ui/settings/openai/start") && response.status() == 200,
                        () -> page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Connect ChatGPT/OpenAI subscription")).click());

                com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator("#openai-oauth-section")).containsText("Status: Connected");
                assertThat(server.deviceCalls.get()).isEqualTo(1);
                assertThat(server.tokenCalls.get()).isEqualTo(1);
            }

            try (RunningApp second = startApp(fakeHome, dbFile, Map.of(
                    "openai.oauth.issuer", server.baseUrl(),
                    "openai.oauth.client-id", "e2e-client"
            ), TestAppConfig.class);
                 BrowserContext context = browser.newContext()) {

                Page page = context.newPage();
                page.navigate(second.baseUrl());
                page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("New tab")).waitFor();

                com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator(".project-tab-group.active .project-tab-label")).hasText("Alpha");

                page.waitForResponse(
                        response -> response.url().contains("/ui/settings") && response.status() == 200,
                        () -> page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Settings")).click());

                com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator("#openai-oauth-section")).containsText("Status: Connected");
                com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Disconnect ChatGPT/OpenAI subscription"))).isVisible();
                assertThat(server.deviceCalls.get()).isEqualTo(1);

                page.waitForResponse(
                        response -> response.url().contains("/ui/settings/openai/logout") && response.status() == 200,
                        () -> page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Disconnect ChatGPT/OpenAI subscription")).click());

                com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator("#openai-oauth-section")).containsText("OpenAI is not connected.");
                com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Connect ChatGPT/OpenAI subscription"))).isVisible();
            }
        } finally {
            if (previousHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", previousHome);
            }
        }
    }

    private static String formValue(String body, String key) {
        for (String pair : body.split("&")) {
            String[] parts = pair.split("=", 2);
            if (parts.length == 2 && key.equals(java.net.URLDecoder.decode(parts[0], StandardCharsets.UTF_8))) {
                return java.net.URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static String encodedFormBody(String... nameValuePairs) {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < nameValuePairs.length; i += 2) {
            if (body.length() > 0) {
                body.append('&');
            }
            body.append(java.net.URLEncoder.encode(nameValuePairs[i], StandardCharsets.UTF_8))
                    .append('=')
                    .append(java.net.URLEncoder.encode(nameValuePairs[i + 1], StandardCharsets.UTF_8));
        }
        return body.toString();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestAppConfig {

        @Bean
        @Primary
        CodingAgentHarness codingAgentHarness() {
            return new CodingAgentHarness(null, null, null) {
                @Override
                public AgentTurnResult runTurnStreaming(AgentTurnRequest request, AgentStreamListener listener) {
                    AgentTurnResult result = new AgentTurnResult("done", java.util.List.of());
                    listener.onComplete(result);
                    return result;
                }
            };
        }
    }

    private static final class TestServer implements AutoCloseable {
        private final HttpServer server;
        private final AtomicInteger deviceCalls = new AtomicInteger();
        private final AtomicInteger authorizationCalls = new AtomicInteger();
        private final AtomicInteger tokenCalls = new AtomicInteger();
        private final CopyOnWriteArrayList<String> authorizationRequestBodies = new CopyOnWriteArrayList<>();
        private final CopyOnWriteArrayList<String> tokenRequestMethods = new CopyOnWriteArrayList<>();
        private final CopyOnWriteArrayList<String> tokenRequestBodies = new CopyOnWriteArrayList<>();
        private volatile String deviceRequestMethod;
        private volatile String deviceRequestBody;

        private TestServer(HttpServer server) {
            this.server = server;
        }

        static TestServer start() throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
            TestServer testServer = new TestServer(server);
            server.createContext("/api/accounts/deviceauth/usercode", testServer.deviceHandler());
            server.createContext("/api/accounts/deviceauth/token", testServer.authorizationHandler());
            server.createContext("/oauth/token", testServer.tokenHandler());
            server.createContext("/codex/device", exchange -> respond(exchange, 200, "ok"));
            server.start();
            return testServer;
        }

        String baseUrl() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort()).toString();
        }

        String url(String path) {
            return URI.create(baseUrl() + path).toString();
        }

        private HttpHandler deviceHandler() {
            return exchange -> {
                deviceCalls.incrementAndGet();
                deviceRequestMethod = exchange.getRequestMethod();
                deviceRequestBody = readBody(exchange);
                respond(exchange, 200, """
                        {
                          "device_auth_id": "device-123",
                          "user_code": "ABCD-EFGH",
                          "interval": "1",
                          "expires": 600
                        }
                        """);
            };
        }

        private HttpHandler authorizationHandler() {
            return exchange -> {
                authorizationCalls.incrementAndGet();
                authorizationRequestBodies.add(readBody(exchange));
                if (authorizationCalls.get() == 1) {
                    exchange.sendResponseHeaders(403, -1);
                    exchange.close();
                } else {
                    respond(exchange, 200, """
                            {
                              "authorization_code": "auth-123",
                              "code_challenge": "challenge-123",
                              "code_verifier": "verifier-456"
                            }
                            """);
                }
            };
        }

        private HttpHandler tokenHandler() {
            return exchange -> {
                int call = tokenCalls.incrementAndGet();
                tokenRequestMethods.add(exchange.getRequestMethod());
                tokenRequestBodies.add(readBody(exchange));
                if (call == 1) {
                    respond(exchange, 200, """
                            {
                              "access_token": "access-123",
                              "refresh_token": "refresh-456",
                              "id_token": "id-789"
                            }
                            """);
                } else {
                    respond(exchange, 500, "{\"error\":\"unexpected token exchange\"}");
                }
            };
        }

        private String readBody(HttpExchange exchange) throws IOException {
            try (InputStream input = exchange.getRequestBody()) {
                return new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
        }

        private static void respond(HttpExchange exchange, int status, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
