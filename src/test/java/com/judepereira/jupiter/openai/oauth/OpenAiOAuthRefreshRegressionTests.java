package com.judepereira.jupiter.openai.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.judepereira.jupiter.agent.config.OpenAiOAuthProperties;
import com.judepereira.jupiter.persistence.AppStateRepository;
import com.judepereira.jupiter.persistence.AppStateRepository.OpenAiOAuthStateRow;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

class OpenAiOAuthRefreshRegressionTests {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void expiredStateRefreshesAndPersistsRotatedTokensAndAccount() throws Exception {
        try (Server server = new Server(200,
                "{\"access_token\":\"new-access\",\"refresh_token\":\"new-refresh\",\"id_token\":\"header.eyJjaGF0Z3B0X2FjY291bnRfaWQiOiJhY2N0LW5ldyJ9.sig\",\"expires_in\":3600}")) {
            AppStateRepository repo = mock(AppStateRepository.class);
            var props = properties(server);
            Mockito.when(repo.loadOpenAiOAuthState())
                    .thenReturn(Optional.of(row("old-access", "old-refresh", Instant.EPOCH)));
            OpenAiOAuthService service = new OpenAiOAuthService(props, JSON, HttpClient.newHttpClient(), repo, null);
            assertThat(service.currentAccessToken()).contains("new-access");
            assertThat(service.currentAccountId()).contains("acct-new");
            assertThat(server.calls.get()).isEqualTo(1);
            assertThat(server.body).contains("grant_type=refresh_token", "refresh_token=old-refresh",
                    "client_id=client-123");
            ArgumentCaptor<Instant> expiry = ArgumentCaptor.forClass(Instant.class);
            Mockito.verify(repo).updateOpenAiOAuthState(ArgumentMatchers.eq("new-access"),
                    ArgumentMatchers.eq("new-refresh"),
                    ArgumentMatchers.eq("header.eyJjaGF0Z3B0X2FjY291bnRfaWQiOiJhY2N0LW5ldyJ9.sig"),
                    ArgumentMatchers.eq("acct-new"), expiry.capture());
            assertThat(expiry.getValue()).isAfter(Instant.now().plusSeconds(3500));
        }
    }

    @Test
    void validTokenDoesNotRefreshAndMissingExpiryRefreshes() throws Exception {
        try (Server server = new Server(200, "{\"access_token\":\"new-access\"}")) {
            AppStateRepository repo = mock(AppStateRepository.class);
            var props = properties(server);
            Mockito.when(repo.loadOpenAiOAuthState())
                    .thenReturn(Optional.of(row("valid", "refresh", Instant.now().plusSeconds(600))));
            OpenAiOAuthService service = new OpenAiOAuthService(props, JSON, HttpClient.newHttpClient(), repo, null);
            assertThat(service.currentAccessToken()).contains("valid");
            assertThat(server.calls.get()).isZero();
            Mockito.when(repo.loadOpenAiOAuthState()).thenReturn(Optional.of(row("legacy", "refresh", null)));
            service = new OpenAiOAuthService(props, JSON, HttpClient.newHttpClient(), repo, null);
            assertThat(service.currentAccessToken()).contains("new-access");
            assertThat(server.calls.get()).isEqualTo(1);
        }
    }

    @Test
    void rejectedRefreshClearsStateAndRequiresReconnect() throws Exception {
        try (Server server = new Server(400, "{\"error\":\"invalid_grant\"}")) {
            AppStateRepository repo = mock(AppStateRepository.class);
            Mockito.when(repo.loadOpenAiOAuthState())
                    .thenReturn(Optional.of(row("old-access", "refresh-secret", Instant.EPOCH)));
            OpenAiOAuthService service = new OpenAiOAuthService(properties(server), JSON, HttpClient.newHttpClient(),
                    repo, null);
            assertThatThrownBy(service::currentAccessToken).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Reconnect")
                    .satisfies(error -> assertThat(error.toString()).doesNotContain("refresh-secret"));
            Mockito.verify(repo).clearOpenAiOAuthState();
            assertThat(service.currentView().connected()).isFalse();
            assertThat(server.calls.get()).isEqualTo(1);
        }
    }

    @Test
    void temporaryRefreshFailurePreservesStateAndAllowsSuccessfulRetry() throws Exception {
        try (Server server = new Server(503, "{\"error\":\"refresh-secret\"}")) {
            AppStateRepository repo = mock(AppStateRepository.class);
            Mockito.when(repo.loadOpenAiOAuthState())
                    .thenReturn(Optional.of(row("old-access", "refresh-secret", Instant.EPOCH)));
            OpenAiOAuthService service = new OpenAiOAuthService(properties(server), JSON, HttpClient.newHttpClient(),
                    repo, null);
            assertThatThrownBy(service::currentAccessToken).hasMessageContaining("503")
                    .satisfies(error -> assertThat(error.toString()).doesNotContain("refresh-secret"));
            Mockito.verify(repo, Mockito.never()).clearOpenAiOAuthState();
            assertThat(service.currentView().connected()).isTrue();
            server.status = 200;
            server.response = "{\"access_token\":\"recovered\",\"expires_in\":3600}";
            assertThat(service.currentAccessToken()).contains("recovered");
            assertThat(server.calls.get()).isEqualTo(2);
            Mockito.verify(repo).updateOpenAiOAuthState(ArgumentMatchers.eq("recovered"),
                    ArgumentMatchers.eq("refresh-secret"), ArgumentMatchers.eq("id-token"),
                    ArgumentMatchers.eq("acct-old"), ArgumentMatchers.any());
        }
    }

    @Test
    void concurrentExpiredTokenCallsPerformOneRefresh() throws Exception {
        try (Server server = new Server(200,
                "{\"access_token\":\"new-access\",\"refresh_token\":\"rotated\",\"expires_in\":3600}")) {
            server.delayMillis = 150;
            AppStateRepository repo = mock(AppStateRepository.class);
            Mockito.when(repo.loadOpenAiOAuthState()).thenReturn(Optional.of(row("old", "refresh", Instant.EPOCH)));
            OpenAiOAuthService service = new OpenAiOAuthService(properties(server), JSON, HttpClient.newHttpClient(),
                    repo, null);
            ExecutorService workers = Executors.newFixedThreadPool(8);
            try {
                CountDownLatch start = new CountDownLatch(1);
                var calls = IntStream.range(0, 8).mapToObj(i -> workers.submit(() -> {
                    start.await();
                    return service.currentAccessToken().orElseThrow();
                })).toList();
                start.countDown();
                for (Future<String> call : calls) {
                    assertThat(call.get(5, TimeUnit.SECONDS)).isEqualTo("new-access");
                }
            } finally {
                workers.shutdownNow();
            }
            assertThat(server.calls.get()).isEqualTo(1);
            Mockito.verify(repo).updateOpenAiOAuthState(ArgumentMatchers.eq("new-access"),
                    ArgumentMatchers.eq("rotated"), ArgumentMatchers.eq("id-token"), ArgumentMatchers.eq("acct-old"),
                    ArgumentMatchers.any());
        }
    }

    @Test
    void refreshRequiresAccessTokenAndDoesNotOverwriteStateOnMalformedResponse() throws Exception {
        try (Server server = new Server(200, "{\"refresh_token\":\"rotated\"}")) {
            AppStateRepository repo = mock(AppStateRepository.class);
            Mockito.when(repo.loadOpenAiOAuthState())
                    .thenReturn(Optional.of(row("old", "refresh-secret", Instant.EPOCH)));
            OpenAiOAuthService service = new OpenAiOAuthService(properties(server), JSON, HttpClient.newHttpClient(),
                    repo, null);
            assertThatThrownBy(service::currentAccessToken).hasMessageContaining("access_token");
            Mockito.verify(repo, Mockito.never()).updateOpenAiOAuthState(ArgumentMatchers.any(), ArgumentMatchers.any(),
                    ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any());
            assertThat(service.currentView().connected()).isTrue();
        }
    }

    @ParameterizedTest
    @ValueSource(longs = {4_102_444_800L, 1_577_836_800L})
    void jwtExpiryIsPersistedExactlyIncludingPastExpiry(long exp) throws Exception {
        String token = jwtWithExpiry(exp);
        try (Server server = new Server(200, "{\"access_token\":\"" + token + "\",\"expires_in\":null}")) {
            AppStateRepository repo = mock(AppStateRepository.class);
            Mockito.when(repo.loadOpenAiOAuthState()).thenReturn(Optional.of(row("old", "refresh", Instant.EPOCH)));
            OpenAiOAuthService service = new OpenAiOAuthService(properties(server), JSON, HttpClient.newHttpClient(),
                    repo, null);
            service.currentAccessToken();
            ArgumentCaptor<Instant> expiry = ArgumentCaptor.forClass(Instant.class);
            Mockito.verify(repo).updateOpenAiOAuthState(ArgumentMatchers.eq(token), ArgumentMatchers.eq("refresh"),
                    ArgumentMatchers.any(), ArgumentMatchers.any(), expiry.capture());
            assertThat(expiry.getValue()).isEqualTo(Instant.ofEpochSecond(exp));
        }
    }

    @Test
    void jwtExpiryWithinSafetyMarginTriggersRefresh() throws Exception {
        long exp = Instant.now().plusSeconds(45).getEpochSecond();
        String token = jwtWithExpiry(exp);
        try (Server server = new Server(200, "{\"access_token\":\"refreshed\",\"expires_in\":3600}")) {
            AppStateRepository repo = mock(AppStateRepository.class);
            Mockito.when(repo.loadOpenAiOAuthState())
                    .thenReturn(Optional.of(row(token, "refresh", Instant.ofEpochSecond(exp))));
            OpenAiOAuthService service = new OpenAiOAuthService(properties(server), JSON, HttpClient.newHttpClient(),
                    repo, null);
            assertThat(service.currentAccessToken()).contains("refreshed");
            assertThat(server.calls.get()).isEqualTo(1);
        }
    }

    @Test
    void missingRefreshTokenClearsStateAndRequiresReconnect() throws Exception {
        try (Server server = new Server(200, "{}")) {
            AppStateRepository repo = mock(AppStateRepository.class);
            Mockito.when(repo.loadOpenAiOAuthState()).thenReturn(Optional.of(row("old", null, Instant.EPOCH)));
            OpenAiOAuthService service = new OpenAiOAuthService(properties(server), JSON, HttpClient.newHttpClient(),
                    repo, null);
            assertThatThrownBy(service::currentAccessToken).hasMessageContaining("Reconnect");
            Mockito.verify(repo).clearOpenAiOAuthState();
            assertThat(service.currentView().connected()).isFalse();
            assertThat(server.calls.get()).isZero();
        }
    }

    @Test
    void omittedRefreshFieldsRetainExistingCredentialsAndExpiryFallbacksAreApplied() throws Exception {
        try (Server server = new Server(200, "{\"access_token\":\"opaque-access\"}")) {
            AppStateRepository repo = mock(AppStateRepository.class);
            Mockito.when(repo.loadOpenAiOAuthState())
                    .thenReturn(Optional.of(row("old", "keep-refresh", Instant.EPOCH)));
            OpenAiOAuthService service = new OpenAiOAuthService(properties(server), JSON, HttpClient.newHttpClient(),
                    repo, null);
            service.currentAccessToken();
            ArgumentCaptor<Instant> expiry = ArgumentCaptor.forClass(Instant.class);
            Mockito.verify(repo).updateOpenAiOAuthState(ArgumentMatchers.eq("opaque-access"),
                    ArgumentMatchers.eq("keep-refresh"), ArgumentMatchers.eq("id-token"),
                    ArgumentMatchers.eq("acct-old"), expiry.capture());
            assertThat(expiry.getValue()).isBetween(Instant.now().plusSeconds(800), Instant.now().plusSeconds(900));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"3600", "\"3600\""})
    void acceptsIntegerAndNumericStringExpiry(String expiresIn) throws Exception {
        try (Server server = new Server(200, "{\"access_token\":\"fresh\",\"expires_in\":" + expiresIn + "}")) {
            AppStateRepository repo = mock(AppStateRepository.class);
            Mockito.when(repo.loadOpenAiOAuthState()).thenReturn(Optional.of(row("old", "refresh", Instant.EPOCH)));
            OpenAiOAuthService service = new OpenAiOAuthService(properties(server), JSON, HttpClient.newHttpClient(),
                    repo, null);
            assertThat(service.currentAccessToken()).contains("fresh");
            ArgumentCaptor<Instant> expiry = ArgumentCaptor.forClass(Instant.class);
            Mockito.verify(repo).updateOpenAiOAuthState(ArgumentMatchers.eq("fresh"), ArgumentMatchers.eq("refresh"),
                    ArgumentMatchers.any(), ArgumentMatchers.any(), expiry.capture());
            assertThat(expiry.getValue()).isAfter(Instant.now().plusSeconds(3500));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.5", "-1", "9223372036854775808"})
    void rejectsInvalidExpiryWithoutOverwritingState(String expiresIn) throws Exception {
        try (Server server = new Server(200, "{\"access_token\":\"fresh\",\"expires_in\":" + expiresIn + "}")) {
            AppStateRepository repo = mock(AppStateRepository.class);
            Mockito.when(repo.loadOpenAiOAuthState()).thenReturn(Optional.of(row("old", "refresh", Instant.EPOCH)));
            OpenAiOAuthService service = new OpenAiOAuthService(properties(server), JSON, HttpClient.newHttpClient(),
                    repo, null);
            assertThatThrownBy(service::currentAccessToken).hasMessageContaining("expires_in");
            Mockito.verify(repo, Mockito.never()).updateOpenAiOAuthState(ArgumentMatchers.any(), ArgumentMatchers.any(),
                    ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"refresh_token_reused", "invalid_client"})
    void nestedRefreshRejectionClearsStateOnlyForReusedToken(String error) throws Exception {
        try (Server server = new Server(400, "{\"error\":{\"code\":\"" + error + "\"}}")) {
            AppStateRepository repo = mock(AppStateRepository.class);
            Mockito.when(repo.loadOpenAiOAuthState()).thenReturn(Optional.of(row("old", "refresh", Instant.EPOCH)));
            OpenAiOAuthService service = new OpenAiOAuthService(properties(server), JSON, HttpClient.newHttpClient(),
                    repo, null);
            assertThatThrownBy(service::currentAccessToken).isInstanceOf(IllegalStateException.class);
            if (error.equals("refresh_token_reused")) {
                Mockito.verify(repo).clearOpenAiOAuthState();
                assertThat(service.currentView().connected()).isFalse();
            } else {
                Mockito.verify(repo, Mockito.never()).clearOpenAiOAuthState();
                assertThat(service.currentView().connected()).isTrue();
            }
        }
    }

    @Test
    void malformedBodyErrorDoesNotRetainCause() throws Exception {
        try (Server server = new Server(200, "{secret malformed")) {
            AppStateRepository repo = mock(AppStateRepository.class);
            Mockito.when(repo.loadOpenAiOAuthState())
                    .thenReturn(Optional.of(row("old", "refresh-secret", Instant.EPOCH)));
            OpenAiOAuthService service = new OpenAiOAuthService(properties(server), JSON, HttpClient.newHttpClient(),
                    repo, null);
            assertThatThrownBy(service::currentAccessToken).satisfies(error -> {
                assertThat(error.getMessage()).doesNotContain("secret", "malformed");
                assertThat(error.getCause()).isNull();
            });
        }
    }

    private static String jwtWithExpiry(long exp) throws Exception {
        String claims = JSON.writeValueAsString(Map.of("exp", exp));
        return "header."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(claims.getBytes(StandardCharsets.UTF_8))
                + ".signature";
    }

    private static OpenAiOAuthProperties properties(Server server) {
        var props = new OpenAiOAuthProperties();
        props.setIssuer(server.url());
        props.setClientId("client-123");
        return props;
    }

    private static OpenAiOAuthStateRow row(String access, String refresh, Instant expires) {
        return new OpenAiOAuthStateRow(access, refresh, "id-token", "acct-old", expires);
    }

    private static final class Server implements AutoCloseable {
        final HttpServer server;
        final AtomicInteger calls = new AtomicInteger();
        volatile int status;
        volatile String response;
        volatile String body;
        volatile long delayMillis;

        Server(int status, String response) throws IOException {
            this.status = status;
            this.response = response;
            server = HttpServer.create(new InetSocketAddress(0), 0);
            server.createContext("/oauth/token", this::handle);
            server.start();
        }

        String url() {
            return URI.create("http://localhost:" + server.getAddress().getPort()).toString();
        }
        void handle(HttpExchange exchange) throws IOException {
            calls.incrementAndGet();
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException(e);
                }
            }
            body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }
        public void close() {
            server.stop(0);
        }
    }
}
