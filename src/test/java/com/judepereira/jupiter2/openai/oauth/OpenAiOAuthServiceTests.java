package com.judepereira.jupiter2.openai.oauth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.judepereira.jupiter2.agent.config.OpenAiOAuthProperties;
import com.judepereira.jupiter2.persistence.AppStateRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class OpenAiOAuthServiceTests {

    @Test
    public void startAndPollDeviceAuthorizationFlow() throws Exception {
        try (TestServer server = TestServer.start()) {
            OpenAiOAuthProperties properties = new OpenAiOAuthProperties();
            properties.setIssuer(server.baseUrl());
            properties.setClientId("client-123");

            OpenAiOAuthService service = new OpenAiOAuthService(properties, new ObjectMapper(), HttpClient.newHttpClient());

            OpenAiOAuthService.OpenAiOAuthView started = service.startDeviceAuthorization();
            assertThat(started.pending()).isTrue();
            assertThat(started.connected()).isFalse();
            assertThat(started.pollTrigger()).isEqualTo("every 1s");
            assertThat(started.userCode()).isEqualTo("ABCD-EFGH");
            assertThat(started.verificationUri()).isEqualTo(server.url("/codex/device"));

            OpenAiOAuthService.OpenAiOAuthView pending = service.pollCurrentDeviceAuthorization();
            assertThat(pending.pending()).isTrue();
            assertThat(pending.connected()).isFalse();
            assertThat(pending.pollTrigger()).isEqualTo("every 1s");

            OpenAiOAuthService.OpenAiOAuthView connected = service.pollCurrentDeviceAuthorization();
            assertThat(connected.connected()).isTrue();
            assertThat(connected.pending()).isFalse();
            assertThat(connected.message()).isEqualTo("OpenAI connected.");
            assertThat(service.currentView().connected()).isTrue();
            assertThat(service.currentAccessToken()).contains("access-123");
            assertThat(service.currentAccountId()).contains("acct-123");
            assertThat(server.tokenCalls.get()).isEqualTo(1);
            assertThat(service.currentApiCredential()).isEmpty();

            OpenAiOAuthService.OpenAiOAuthView reset = service.resetConnectionState();
            assertThat(reset.connected()).isFalse();
            assertThat(reset.pending()).isFalse();
            assertThat(reset.message()).isEqualTo("OpenAI is not connected.");
            assertThat(service.currentAccessToken()).isEmpty();
            assertThat(service.currentAccountId()).isEmpty();
        }
    }

    @Test
    public void persistsConnectedStateAndReloadsItInAFreshServiceInstance(@TempDir Path tempDir) throws Exception {
        try (TestServer server = TestServer.start(); TestDatabase database = TestDatabase.open(tempDir)) {
            OpenAiOAuthProperties properties = new OpenAiOAuthProperties();
            properties.setIssuer(server.baseUrl());
            properties.setClientId("client-123");

            OpenAiOAuthService first = new OpenAiOAuthService(properties, new ObjectMapper(), HttpClient.newHttpClient(), database.repository());
            first.startDeviceAuthorization();
            first.pollCurrentDeviceAuthorization();
            first.pollCurrentDeviceAuthorization();

            OpenAiOAuthService fresh = new OpenAiOAuthService(properties, new ObjectMapper(), HttpClient.newHttpClient(), database.repository());
            assertThat(fresh.currentView().connected()).isTrue();
            assertThat(fresh.currentView().pending()).isFalse();
            assertThat(fresh.currentAccessToken()).contains("access-123");
            assertThat(fresh.currentAccountId()).contains("acct-123");

            fresh.resetConnectionState();

            OpenAiOAuthService afterLogout = new OpenAiOAuthService(properties, new ObjectMapper(), HttpClient.newHttpClient(), database.repository());
            assertThat(afterLogout.currentView().connected()).isFalse();
            assertThat(afterLogout.currentView().pending()).isFalse();
            assertThat(afterLogout.currentAccessToken()).isEmpty();
            assertThat(afterLogout.currentAccountId()).isEmpty();
        }
    }

    @Test
    public void poll429KeepsPendingAndBacksOff() throws Exception {
        try (TestServer server = TestServer.startRateLimited()) {
            OpenAiOAuthProperties properties = new OpenAiOAuthProperties();
            properties.setIssuer(server.baseUrl());
            properties.setClientId("client-123");

            OpenAiOAuthService service = new OpenAiOAuthService(properties, new ObjectMapper(), HttpClient.newHttpClient());

            service.startDeviceAuthorization();
            OpenAiOAuthService.OpenAiOAuthView pending = service.pollCurrentDeviceAuthorization();

            assertThat(pending.pending()).isTrue();
            assertThat(pending.connected()).isFalse();
            assertThat(pending.message()).contains("rate limited");
            assertThat(pending.message()).contains("Retrying in 6 seconds");
            assertThat(pending.intervalSeconds()).isEqualTo(6);
            assertThat(pending.pollTrigger()).isEqualTo("every 6s");
        }
    }

    @Test
    public void startFailsWhenClientIdIsMissing() {
        OpenAiOAuthProperties properties = new OpenAiOAuthProperties();
        properties.setClientId(" ");
        OpenAiOAuthService service = new OpenAiOAuthService(properties, new ObjectMapper(), HttpClient.newHttpClient());

        assertThatThrownBy(service::startDeviceAuthorization)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("openai.oauth.client-id");
    }

    private static final class TestServer implements AutoCloseable {
        private final HttpServer server;
        private final AtomicInteger tokenCalls = new AtomicInteger();
        private final AtomicInteger authorizationCalls = new AtomicInteger();
        private final boolean rateLimitFirstPoll;
        private final ObjectMapper objectMapper = new ObjectMapper();

        private TestServer(HttpServer server, boolean rateLimitFirstPoll) {
            this.server = server;
            this.rateLimitFirstPoll = rateLimitFirstPoll;
        }

        static TestServer start() throws IOException {
            return start(false);
        }

        static TestServer startRateLimited() throws IOException {
            return start(true);
        }

        private static TestServer start(boolean rateLimitFirstPoll) throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
            TestServer testServer = new TestServer(server, rateLimitFirstPoll);
            server.createContext("/api/accounts/deviceauth/usercode", testServer.deviceHandler());
            server.createContext("/api/accounts/deviceauth/token", testServer.authorizationHandler());
            server.createContext("/oauth/token", testServer.tokenHandler());
            server.createContext("/codex/device", exchange -> respond(exchange, 200, Map.of("ok", true)));
            server.start();
            return testServer;
        }

        String baseUrl() {
            return URI.create("http://localhost:" + server.getAddress().getPort()).toString();
        }

        String url(String path) {
            return URI.create(baseUrl() + path).toString();
        }

        private HttpHandler deviceHandler() {
            return exchange -> {
                assertThat(exchange.getRequestMethod()).isEqualTo("POST");
                assertThat(exchange.getRequestHeaders().getFirst("Content-Type")).contains("application/json");
                assertThat(exchange.getRequestHeaders().getFirst("Accept")).contains("application/json");
                Map<String, Object> body = readJsonBody(exchange);
                assertThat(body).containsEntry("client_id", "client-123");
                respond(exchange, 200, Map.of(
                        "device_auth_id", "device-123",
                        "user_code", "ABCD-EFGH",
                        "interval", "1",
                        "expires", 600
                ));
            };
        }

        private HttpHandler authorizationHandler() {
            return exchange -> {
                authorizationCalls.incrementAndGet();
                assertThat(exchange.getRequestMethod()).isEqualTo("POST");
                assertThat(exchange.getRequestHeaders().getFirst("Content-Type")).contains("application/json");
                Map<String, Object> body = readJsonBody(exchange);
                assertThat(body).containsEntry("device_auth_id", "device-123");
                assertThat(body).containsEntry("user_code", "ABCD-EFGH");
                if (rateLimitFirstPoll && authorizationCalls.get() == 1) {
                    exchange.sendResponseHeaders(429, -1);
                    exchange.close();
                } else if (authorizationCalls.get() < (rateLimitFirstPoll ? 3 : 2)) {
                    exchange.sendResponseHeaders(404, -1);
                    exchange.close();
                } else {
                    respond(exchange, 200, Map.of(
                            "authorization_code", "auth-123",
                            "code_challenge", "challenge-123",
                            "code_verifier", "verifier-456"
                    ));
                }
            };
        }

        private HttpHandler tokenHandler() {
            return exchange -> {
                int call = tokenCalls.incrementAndGet();
                assertThat(exchange.getRequestMethod()).isEqualTo("POST");
                assertThat(exchange.getRequestHeaders().getFirst("Content-Type")).contains("application/x-www-form-urlencoded");
                assertThat(exchange.getRequestHeaders().getFirst("Accept")).contains("application/json");
                String body = readBody(exchange);
                if (call == 1) {
                    assertThat(body).isEqualTo(encodedFormBody(Map.of(
                            "grant_type", "authorization_code",
                            "code", "auth-123",
                            "redirect_uri", url("/deviceauth/callback"),
                            "client_id", "client-123",
                            "code_verifier", "verifier-456"
                    )));
                    respond(exchange, 200, Map.of(
                            "access_token", "access-123",
                            "refresh_token", "refresh-456",
                            "id_token", jwtWithAccountId("acct-123")
                    ));
                } else {
                    respond(exchange, 500, Map.of("error", "unexpected token exchange"));
                }
            };
        }

        private String jwtWithAccountId(String accountId) throws IOException {
            String header = base64Url("{\"alg\":\"none\",\"typ\":\"JWT\"}");
            String payload = base64Url(objectMapper.writeValueAsString(Map.of("chatgpt_account_id", accountId)));
            return header + "." + payload + ".signature";
        }

        private String base64Url(String value) {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
        }

        private Map<String, Object> readJsonBody(HttpExchange exchange) throws IOException {
            return objectMapper.readValue(readBody(exchange), Map.class);
        }

        private String readBody(HttpExchange exchange) throws IOException {
            try (InputStream input = exchange.getRequestBody()) {
                return new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
        }

        private String formValue(String body, String key) {
            for (String pair : body.split("&")) {
                String[] parts = pair.split("=", 2);
                if (parts.length == 2 && key.equals(urlDecode(parts[0]))) {
                    return urlDecode(parts[1]);
                }
            }
            return null;
        }

        private String encodedFormBody(Map<String, String> fields) {
            StringBuilder body = new StringBuilder();
            appendField(body, "grant_type", fields.get("grant_type"));
            appendField(body, "code", fields.get("code"));
            appendField(body, "redirect_uri", fields.get("redirect_uri"));
            appendField(body, "client_id", fields.get("client_id"));
            appendField(body, "code_verifier", fields.get("code_verifier"));
            appendField(body, "requested_token", fields.get("requested_token"));
            appendField(body, "subject_token", fields.get("subject_token"));
            appendField(body, "subject_token_type", fields.get("subject_token_type"));
            return body.toString();
        }

        private void appendField(StringBuilder body, String name, String value) {
            if (value == null) {
                return;
            }
            if (body.length() > 0) {
                body.append('&');
            }
            body.append(urlEncode(name)).append('=').append(urlEncode(value));
        }

        private String urlEncode(String value) {
            return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
        }

        private String urlDecode(String value) {
            return java.net.URLDecoder.decode(value, StandardCharsets.UTF_8);
        }

        private static void respond(HttpExchange exchange, int status, Map<String, Object> payload) throws IOException {
            byte[] body = new ObjectMapper().writeValueAsBytes(payload);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    private record TestDatabase(AppStateRepository repository) implements AutoCloseable {
        static TestDatabase open(Path tempDir) {
            DriverManagerDataSource dataSource = new DriverManagerDataSource();
            dataSource.setDriverClassName("org.h2.Driver");
            dataSource.setUrl("jdbc:h2:mem:openai_oauth_" + tempDir.getFileName() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
            dataSource.setUsername("sa");
            dataSource.setPassword("");

            Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
            return new TestDatabase(new AppStateRepository(new NamedParameterJdbcTemplate(dataSource)));
        }

        @Override
        public void close() {
        }
    }
}
