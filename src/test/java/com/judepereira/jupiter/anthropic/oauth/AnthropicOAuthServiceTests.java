package com.judepereira.jupiter.anthropic.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.judepereira.jupiter.agent.config.AnthropicOAuthProperties;
import com.judepereira.jupiter.persistence.AppStateRepository;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AnthropicOAuthServiceTests {
    @Test
    void authorizationUrlHasExpectedPkceAndDefaults() throws Exception {
        AnthropicOAuthProperties p = properties();
        AppStateRepository repo = mock(AppStateRepository.class);
        try (Server server = new Server()) {
            p.setAuthorizationUrl(server.url("/authorize"));
            AnthropicOAuthService s =
                    new AnthropicOAuthService(
                            p, new ObjectMapper(), HttpClient.newHttpClient(), repo, null);
            var view = s.startAuthorization();
            Map<String, String> q = query(view.authorizationUrl());
            assertThat(q)
                    .containsEntry("response_type", "code")
                    .containsEntry("client_id", "client")
                    .containsEntry(
                            "redirect_uri", "https://platform.claude.com/oauth/code/callback")
                    .containsEntry("code_challenge_method", "S256")
                    .containsEntry("code", "true");
            assertThat(q.get("scope")).isEqualTo(p.getScopes());
            assertThat(q).doesNotContainKey("org:create_api_key");
            byte[] challenge = Base64.getUrlDecoder().decode(q.get("code_challenge"));
            assertThat(challenge).hasSize(32);
            assertThat(q.get("state")).matches("[A-Za-z0-9_-]{43}");
            assertThat(q.get("code_challenge")).matches("[A-Za-z0-9_-]{43}");
        }
    }

    @Test
    void bareAuthorizationCodeUsesPendingState() throws Exception {
        AnthropicOAuthProperties p = properties();
        AppStateRepository repo = mock(AppStateRepository.class);
        try (Server server = new Server()) {
            p.setTokenUrl(server.url("/token"));
            AnthropicOAuthService s =
                    new AnthropicOAuthService(
                            p, new ObjectMapper(), HttpClient.newHttpClient(), repo, null);
            s.startAuthorization();
            s.completeAuthorization("bare-code");
            assertThat(server.lastJson).containsEntry("code", "bare-code").containsKey("state");
            assertThat(s.currentAccessToken()).contains("access");
        }
    }

    @Test
    void codeAndHashStateAreExchangedAndPersisted() throws Exception {
        AnthropicOAuthProperties p = properties();
        AppStateRepository repo = mock(AppStateRepository.class);
        try (Server server = new Server()) {
            p.setTokenUrl(server.url("/token"));
            AnthropicOAuthService s =
                    new AnthropicOAuthService(
                            p, new ObjectMapper(), HttpClient.newHttpClient(), repo, null);
            Map<String, String> authorization = query(s.startAuthorization().authorizationUrl());
            String state = authorization.get("state");
            s.completeAuthorization("bare-code#" + state);
            assertThat(s.currentAccessToken()).contains("access");
            verify(repo)
                    .updateAnthropicOAuthState(
                            eq("access"),
                            eq("new-refresh"),
                            any(),
                            eq("user:inference"),
                            eq("{\"id\":\"a\"}"));
            assertThat(server.lastJson)
                    .containsEntry("grant_type", "authorization_code")
                    .containsEntry("code", "bare-code")
                    .containsEntry("state", state)
                    .containsEntry("redirect_uri", p.getRedirectUri())
                    .containsEntry("client_id", "client")
                    .containsKey("code_verifier");
            assertThat(
                            Base64.getUrlEncoder()
                                    .withoutPadding()
                                    .encodeToString(
                                            MessageDigest.getInstance("SHA-256")
                                                    .digest(
                                                            server.lastJson
                                                                    .get("code_verifier")
                                                                    .getBytes(
                                                                            StandardCharsets
                                                                                    .US_ASCII))))
                    .isEqualTo(authorization.get("code_challenge"));
            assertThat(server.lastContentType).isEqualTo("application/json");
        }
    }

    @Test
    void mismatchedStateIsAlwaysRejectedWithoutNetwork() throws Exception {
        AnthropicOAuthProperties p = properties();
        try (Server server = new Server()) {
            p.setTokenUrl(server.url("/token"));
            AnthropicOAuthService s =
                    new AnthropicOAuthService(
                            p,
                            new ObjectMapper(),
                            HttpClient.newHttpClient(),
                            mock(AppStateRepository.class),
                            null);
            s.startAuthorization();
            assertThatThrownBy(() -> s.completeAuthorization("code#wrong"))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(server.calls.get()).isZero();
        }
    }

    @Test
    void forcedRefreshWithoutRefreshTokenReturnsEmptyInsteadOfRejectedToken() {
        AnthropicOAuthProperties p = properties();
        AppStateRepository repo = mock(AppStateRepository.class);
        when(repo.loadAnthropicOAuthState())
                .thenReturn(
                        Optional.of(
                                new AppStateRepository.AnthropicOAuthStateRow(
                                        "old",
                                        null,
                                        Instant.now().plusSeconds(3600),
                                        "scope",
                                        null)));

        AnthropicOAuthService service =
                new AnthropicOAuthService(
                        p, new ObjectMapper(), HttpClient.newHttpClient(), repo, null);

        assertThat(service.forceRefresh("old")).isEmpty();
        verify(repo, never()).updateAnthropicOAuthState(any(), any(), any(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(ints = {408, 429, 500, 503})
    void transientRefreshFailureRetainsCredentials(int status) throws Exception {
        AnthropicOAuthProperties p = properties();
        AppStateRepository repo = mock(AppStateRepository.class);
        when(repo.loadAnthropicOAuthState())
                .thenReturn(
                        Optional.of(
                                new AppStateRepository.AnthropicOAuthStateRow(
                                        "old",
                                        "refresh",
                                        Instant.now().minusSeconds(1),
                                        "scope",
                                        null)));
        try (Server server = new Server()) {
            p.setTokenUrl(server.url("/token"));
            server.responseStatus = status;
            AnthropicOAuthService s =
                    new AnthropicOAuthService(
                            p, new ObjectMapper(), HttpClient.newHttpClient(), repo, null);
            assertThat(s.currentAccessToken()).isEmpty();
            assertThat(s.isConnected()).isTrue();
            verify(repo, never()).clearAnthropicOAuthState();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403})
    void permanentRefreshFailureClearsCredentials(int status) throws Exception {
        AnthropicOAuthProperties p = properties();
        AppStateRepository repo = mock(AppStateRepository.class);
        when(repo.loadAnthropicOAuthState())
                .thenReturn(
                        Optional.of(
                                new AppStateRepository.AnthropicOAuthStateRow(
                                        "old",
                                        "refresh",
                                        Instant.now().minusSeconds(1),
                                        "scope",
                                        null)));
        try (Server server = new Server()) {
            p.setTokenUrl(server.url("/token"));
            server.responseStatus = status;
            AnthropicOAuthService s =
                    new AnthropicOAuthService(
                            p, new ObjectMapper(), HttpClient.newHttpClient(), repo, null);
            assertThat(s.currentAccessToken()).isEmpty();
            assertThat(s.isConnected()).isFalse();
            assertThat(s.currentView().status())
                    .isEqualTo(AnthropicOAuthService.Status.REFRESH_FAILED);
            verify(repo).clearAnthropicOAuthState();
        }
    }

    @Test
    void malformedRefreshResponseClearsCredentials() throws Exception {
        AnthropicOAuthProperties p = properties();
        AppStateRepository repo = mock(AppStateRepository.class);
        when(repo.loadAnthropicOAuthState())
                .thenReturn(
                        Optional.of(
                                new AppStateRepository.AnthropicOAuthStateRow(
                                        "old",
                                        "refresh",
                                        Instant.now().minusSeconds(1),
                                        "scope",
                                        null)));
        try (Server server = new Server()) {
            p.setTokenUrl(server.url("/token"));
            server.malformed = true;
            AnthropicOAuthService s =
                    new AnthropicOAuthService(
                            p, new ObjectMapper(), HttpClient.newHttpClient(), repo, null);
            assertThat(s.currentAccessToken()).isEmpty();
            verify(repo).clearAnthropicOAuthState();
        }
    }

    @Test
    void refreshRotatesCredentialsAndTransientFailureRetainsState() throws Exception {
        AnthropicOAuthProperties p = properties();
        AppStateRepository repo = mock(AppStateRepository.class);
        when(repo.loadAnthropicOAuthState())
                .thenReturn(
                        Optional.of(
                                new AppStateRepository.AnthropicOAuthStateRow(
                                        "old",
                                        "refresh",
                                        Instant.now().minusSeconds(1),
                                        "scope",
                                        null)));
        try (Server server = new Server()) {
            p.setTokenUrl(server.url("/token"));
            AnthropicOAuthService s =
                    new AnthropicOAuthService(
                            p, new ObjectMapper(), HttpClient.newHttpClient(), repo, null);
            assertThat(s.currentAccessToken()).contains("rotated");
            verify(repo)
                    .updateAnthropicOAuthState(
                            eq("rotated"), eq("new-refresh"), any(), any(), any());
            assertThat(server.lastJson)
                    .containsEntry("grant_type", "refresh_token")
                    .containsEntry("refresh_token", "refresh")
                    .containsEntry("client_id", "client")
                    .doesNotContainKey("redirect_uri")
                    .doesNotContainKey("state");
            assertThat(server.lastContentType).isEqualTo("application/json");
            server.fail = true;
            assertThat(s.currentAccessToken()).isEmpty();
            verify(repo, never()).clearAnthropicOAuthState();
            assertThat(s.isConnected()).isTrue();
        }
    }

    private static AnthropicOAuthProperties properties() {
        AnthropicOAuthProperties p = new AnthropicOAuthProperties();
        p.setClientId("client");
        return p;
    }

    private static Map<String, String> query(String url) {
        Map<String, String> r = new HashMap<>();
        for (String part : URI.create(url).getRawQuery().split("&")) {
            String[] x = part.split("=", 2);
            r.put(
                    URLDecoder.decode(x[0], StandardCharsets.UTF_8),
                    URLDecoder.decode(x[1], StandardCharsets.UTF_8));
        }
        return r;
    }

    private static final class Server implements AutoCloseable {
        final HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        final AtomicInteger calls = new AtomicInteger();
        volatile boolean fail;
        volatile boolean malformed;
        volatile int responseStatus = 200;
        volatile Map<String, String> lastJson = Map.of();
        volatile String lastContentType;

        Server() throws IOException {
            server.createContext("/token", this::token);
            server.start();
        }

        String url(String path) {
            return "http://localhost:" + server.getAddress().getPort() + path;
        }

        void token(HttpExchange e) throws IOException {
            calls.incrementAndGet();
            String body = new String(e.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            lastContentType = e.getRequestHeaders().getFirst("Content-Type");
            try {
                lastJson = new ObjectMapper().readValue(body, Map.class);
            } catch (Exception ex) {
                lastJson = Map.of();
            }
            boolean refresh = "refresh_token".equals(lastJson.get("grant_type"));
            byte[] out =
                    (malformed
                                    ? "not-json"
                                    : (fail
                                            ? "{}"
                                            : "{\"access_token\":\""
                                                    + (refresh ? "rotated" : "access")
                                                    + "\",\"refresh_token\":\"new-refresh\",\"expires_in\":"
                                                    + (refresh ? "30" : "3600")
                                                    + ",\"scope\":\"user:inference\",\"account\":{\"id\":\"a\"}} "))
                            .getBytes(StandardCharsets.UTF_8);
            e.sendResponseHeaders(fail ? 500 : responseStatus, out.length);
            e.getResponseBody().write(out);
            e.close();
        }

        public void close() {
            server.stop(0);
        }
    }
}
