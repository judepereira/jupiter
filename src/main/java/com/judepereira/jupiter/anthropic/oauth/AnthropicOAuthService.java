package com.judepereira.jupiter.anthropic.oauth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.judepereira.jupiter.agent.catalog.ProviderConnectedEvent;
import com.judepereira.jupiter.agent.config.AnthropicOAuthProperties;
import com.judepereira.jupiter.persistence.AppStateRepository;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

@Service
public class AnthropicOAuthService {
    private final AnthropicOAuthProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final AppStateRepository repository;
    private final ApplicationEventPublisher eventPublisher;
    private final SecureRandom random = new SecureRandom();
    private State state = State.empty();
    private Pending pending;

    public AnthropicOAuthService(AnthropicOAuthProperties properties, ObjectMapper objectMapper, HttpClient httpClient,
            AppStateRepository repository, ApplicationEventPublisher eventPublisher) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.repository = repository;
        this.eventPublisher = eventPublisher;
        repository.loadAnthropicOAuthState().filter(row -> row.accessToken() != null && !row.accessToken().isBlank())
                .ifPresent(row -> state = State.connected(new Tokens(row.accessToken(), row.refreshToken(),
                        row.expiresAt(), row.scopes(), row.accountJson())));
    }

    public synchronized AnthropicOAuthView startAuthorization() {
        byte[] verifierBytes = new byte[32];
        byte[] stateBytes = new byte[32];
        random.nextBytes(verifierBytes);
        random.nextBytes(stateBytes);
        String verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(verifierBytes);
        String requestState = Base64.getUrlEncoder().withoutPadding().encodeToString(stateBytes);
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(sha256(verifier));
        pending = new Pending(verifier, requestState);
        String url = properties.getAuthorizationUrl() + "?" + form("response_type", "code") + "&"
                + form("client_id", required(properties.getClientId(), "client ID")) + "&"
                + form("redirect_uri", required(properties.getRedirectUri(), "redirect URI")) + "&"
                + form("code_challenge", challenge) + "&"
                + form("code_challenge_method", properties.getCodeChallengeMethod()) + "&"
                + form("scope", properties.getScopes()) + "&" + form("state", requestState) + "&"
                + form("code", Boolean.toString(properties.isCode()));
        state = State.pending();
        return view(url);
    }

    public synchronized AnthropicOAuthView completeAuthorization(String codeInput) {
        if (pending == null)
            throw new IllegalStateException("Anthropic authentication is not pending");
        if (codeInput == null || codeInput.isBlank()) {
            failAuthentication("Anthropic authentication code is required.");
            throw new IllegalArgumentException("Anthropic authentication code is required");
        }

        String trimmed = codeInput.trim();
        String code = trimmed;
        String suppliedState = null;
        int separator = trimmed.indexOf('#');
        if (separator >= 0) {
            code = trimmed.substring(0, separator).trim();
            suppliedState = trimmed.substring(separator + 1).trim();
            if (suppliedState.isEmpty()) {
                failAuthentication("Anthropic authentication state is required.");
                throw new IllegalArgumentException("Anthropic authentication state is required");
            }
        }
        if (code.isEmpty()) {
            failAuthentication("Anthropic authentication code is required.");
            throw new IllegalArgumentException("Anthropic authentication code is required");
        }
        String expectedState = pending.state();
        String stateToExchange = suppliedState == null ? expectedState : suppliedState;
        if (!MessageDigest.isEqual(stateToExchange.getBytes(StandardCharsets.UTF_8),
                expectedState.getBytes(StandardCharsets.UTF_8))) {
            failAuthentication("Anthropic authentication state mismatch.");
            throw new IllegalStateException("Anthropic authentication state mismatch");
        }

        State previousState = state;
        try {
            JsonNode payload = exchangeAuthorization(code, pending.verifier(), stateToExchange);
            Tokens tokens = tokensFromPayload(payload, null);
            repository.updateAnthropicOAuthState(tokens.accessToken(), tokens.refreshToken(), tokens.expiresAt(),
                    tokens.scopes(), tokens.accountJson());
            pending = null;
            state = State.connected(tokens);
        } catch (RuntimeException failure) {
            // A failed authorization attempt must not discard credentials that were already
            // usable.
            state = previousState;
            throw failure;
        }
        if (eventPublisher != null)
            eventPublisher.publishEvent(new ProviderConnectedEvent("anthropic"));
        return view(null);
    }

    public synchronized Optional<String> currentAccessToken() {
        return accessToken(false, null);
    }

    /**
     * Refreshes the credential that was used for a rejected request. If another
     * request refreshed it while this thread was waiting for the lock, the new
     * credential is reused instead of issuing a second refresh request.
     */
    public synchronized Optional<String> forceRefresh(String rejectedAccessToken) {
        return accessToken(true, rejectedAccessToken);
    }

    private Optional<String> accessToken(boolean force, String rejectedAccessToken) {
        if (state.tokens() == null)
            return Optional.empty();
        if (!force && state.tokens().expiresAt() != null
                && Instant.now().plusSeconds(60).isBefore(state.tokens().expiresAt()))
            return Optional.of(state.tokens().accessToken());
        if (force && rejectedAccessToken != null && !rejectedAccessToken.equals(state.tokens().accessToken())) {
            return Optional.of(state.tokens().accessToken());
        }
        if (state.tokens().refreshToken() == null || state.tokens().refreshToken().isBlank()) {
            // A forced refresh must not hand the rejected credential back to the caller.
            // Otherwise the client would issue the same request again with the same token.
            return Optional.empty();
        }
        try {
            Tokens previous = state.tokens();
            Tokens refreshed = tokensFromPayload(exchangeRefresh(previous.refreshToken()), previous);
            repository.updateAnthropicOAuthState(refreshed.accessToken(), refreshed.refreshToken(),
                    refreshed.expiresAt(), refreshed.scopes(), refreshed.accountJson());
            state = State.connected(refreshed);
            return Optional.of(refreshed.accessToken());
        } catch (PermanentRefreshFailure failure) {
            repository.clearAnthropicOAuthState();
            state = State.failed("Anthropic token refresh failed.");
            return Optional.empty();
        } catch (TransientRefreshFailure failure) {
            return Optional.empty();
        }
    }

    public synchronized boolean isConnected() {
        return state.status() == Status.CONNECTED;
    }

    public synchronized AnthropicOAuthView disconnect() {
        repository.clearAnthropicOAuthState();
        pending = null;
        state = State.empty();
        return view(null);
    }

    public synchronized AnthropicOAuthView currentView() {
        return view(null);
    }

    private JsonNode exchangeAuthorization(String code, String verifier, String exchangeState) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("grant_type", "authorization_code");
        body.put("code", code);
        body.put("redirect_uri", required(properties.getRedirectUri(), "redirect URI"));
        body.put("client_id", required(properties.getClientId(), "client ID"));
        body.put("code_verifier", verifier);
        body.put("state", exchangeState);
        return exchange(body, false);
    }

    private JsonNode exchangeRefresh(String refreshToken) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("grant_type", "refresh_token");
        body.put("refresh_token", refreshToken);
        body.put("client_id", required(properties.getClientId(), "client ID"));
        return exchange(body, true);
    }

    private JsonNode exchange(ObjectNode body, boolean refresh) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(required(properties.getTokenUrl(), "token URL")))
                    .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body))).build();
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = response.statusCode();
            if (status / 100 != 2) {
                if (refresh && (status == 408 || status == 429 || status >= 500)) {
                    throw new TransientRefreshFailure("Anthropic token refresh temporarily unavailable");
                }
                if (refresh && (status == 400 || status == 401 || status == 403)) {
                    throw new PermanentRefreshFailure("Anthropic token refresh was rejected");
                }
                throw new IllegalStateException("Anthropic token request failed with status " + status);
            }
            JsonNode json;
            try {
                json = objectMapper.readTree(response.body());
            } catch (IOException e) {
                if (refresh)
                    throw new PermanentRefreshFailure("Malformed Anthropic token response");
                throw new IllegalStateException("Invalid Anthropic token response", e);
            }
            if (json == null || !json.isObject()) {
                if (refresh)
                    throw new PermanentRefreshFailure("Malformed Anthropic token response");
                throw new IllegalStateException("Invalid Anthropic token response");
            }
            return json;
        } catch (IOException e) {
            if (refresh)
                throw new TransientRefreshFailure("Anthropic token refresh request failed", e);
            throw new IllegalStateException("Anthropic token request failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (refresh)
                throw new TransientRefreshFailure("Anthropic token refresh request interrupted", e);
            throw new IllegalStateException("Anthropic token request interrupted", e);
        }
    }

    private Tokens tokensFromPayload(JsonNode payload, Tokens previous) {
        String access;
        try {
            access = text(payload, "access_token");
        } catch (RuntimeException failure) {
            if (previous != null)
                throw new PermanentRefreshFailure("Missing Anthropic access token");
            throw failure;
        }
        String refresh = optional(payload, "refresh_token").orElse(previous == null ? null : previous.refreshToken());
        long seconds = payload.has("expires_in") ? payload.get("expires_in").asLong() : 3600;
        Instant expiry = Instant.now().plusSeconds(seconds);
        String scopes = optional(payload, "scope")
                .orElse(previous == null ? properties.getScopes() : previous.scopes());
        JsonNode account = payload.get("account");
        String accountJson = account == null || account.isNull()
                ? previous == null ? null : previous.accountJson()
                : account.toString();
        return new Tokens(access, refresh, expiry, scopes, accountJson);
    }

    private void failAuthentication(String message) {
        pending = null;
        state = State.failed(message);
    }

    private AnthropicOAuthView view(String url) {
        return new AnthropicOAuthView(state.status(), state.message(), url);
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank())
            throw new IllegalStateException("Anthropic OAuth " + name + " is required");
        return value;
    }

    private static String text(JsonNode node, String field) {
        return optional(node, field)
                .orElseThrow(() -> new IllegalStateException("Missing Anthropic OAuth field: " + field));
    }

    private static Optional<String> optional(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() || value.asText().isBlank()
                ? Optional.empty()
                : Optional.of(value.asText());
    }

    private static String form(String name, String value) {
        return URLEncoder.encode(name, StandardCharsets.UTF_8) + "="
                + URLEncoder.encode(Objects.requireNonNull(value, name), StandardCharsets.UTF_8);
    }

    private static class TransientRefreshFailure extends RuntimeException {
        private TransientRefreshFailure(String message) {
            super(message);
        }
        private TransientRefreshFailure(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static class PermanentRefreshFailure extends RuntimeException {
        private PermanentRefreshFailure(String message) {
            super(message);
        }
    }

    private record Pending(String verifier, String state) {
    }
    private record Tokens(String accessToken, String refreshToken, Instant expiresAt, String scopes,
            String accountJson) {
    }
    private record State(Status status, Tokens tokens, String message) {
        static State empty() {
            return new State(Status.NOT_CONNECTED, null, "Anthropic is not connected.");
        }
        static State pending() {
            return new State(Status.AUTHENTICATION_PENDING, null, "Complete Anthropic authentication.");
        }
        static State connected(Tokens tokens) {
            return new State(Status.CONNECTED, tokens, "Anthropic connected.");
        }
        static State failed(String message) {
            return new State(Status.REFRESH_FAILED, null, message);
        }
    }

    public enum Status {
        NOT_CONNECTED, AUTHENTICATION_PENDING, CONNECTED, REFRESH_FAILED
    }

    public record AnthropicOAuthView(Status status, String message, String authorizationUrl) {
        public boolean connected() {
            return status == Status.CONNECTED;
        }
        public boolean pending() {
            return status == Status.AUTHENTICATION_PENDING;
        }
    }
}
