package com.judepereira.jupiter.openai.oauth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.judepereira.jupiter.agent.config.OpenAiOAuthProperties;
import com.judepereira.jupiter.persistence.AppStateRepository;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Base64;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

@Log4j2
@Service
public class OpenAiOAuthService {
    private static final int DEFAULT_INTERVAL_SECONDS = 5;
    private static final int DEFAULT_EXPIRES_SECONDS = 15 * 60;

    private final OpenAiOAuthProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final AppStateRepository appStateRepository;

    private State state = State.empty();

    public OpenAiOAuthService(OpenAiOAuthProperties properties, ObjectMapper objectMapper, HttpClient httpClient,
                              AppStateRepository appStateRepository) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.appStateRepository = appStateRepository;
        loadPersistedState();
    }

    public synchronized OpenAiOAuthView currentView() {
        return toView(state);
    }

    public synchronized OpenAiOAuthView resetConnectionState() {
        clearPersistedState();
        state = State.empty();
        return toView(state);
    }

    public synchronized Optional<String> currentAccessToken() {
        return state.tokens() == null ? Optional.empty() : Optional.of(state.tokens().accessToken());
    }

    public synchronized Optional<String> currentAccountId() {
        return state.tokens() == null ? Optional.empty() : state.tokens().accountId();
    }

    /**
     * @deprecated Codex browser-flow does not exchange for an OpenAI API key.
     */
    @Deprecated
    public synchronized Optional<String> currentApiCredential() {
        return Optional.empty();
    }

    public synchronized OpenAiOAuthView startDeviceAuthorization() {
        String clientId = requiredClientId();
        HttpResponse<String> response = postJson(URI.create(requiredDeviceUserCodeUrl()), objectMapper.createObjectNode().put("client_id", clientId));
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("OpenAI device authorization failed with status " + response.statusCode());
        }
        JsonNode payload = readJson(response.body(), "device authorization response");

        DeviceAuthorizationResponse authorization = new DeviceAuthorizationResponse(
                requiredText(payload, "device_auth_id"),
                requiredText(payload, "user_code", "usercode"),
                resolvedVerificationUrl(),
                optionalSeconds(payload, "interval", DEFAULT_INTERVAL_SECONDS),
                optionalSeconds(payload, "expires", DEFAULT_EXPIRES_SECONDS)
        );

        state = new State(new DeviceFlow(authorization.deviceAuthId(), authorization.userCode(), authorization.verificationUri(),
                authorization.intervalSeconds(), Instant.now().plusSeconds(authorization.expiresInSeconds()), null),
                null,
                "Complete the authorization in your browser.");
        return toView(state);
    }

    public synchronized OpenAiOAuthView pollCurrentDeviceAuthorization() {
        if (state.flow() == null) {
            return toView(state);
        }

        if (Instant.now().isAfter(state.flow().expiresAt())) {
            state = new State(null, null, "OpenAI device authorization expired.");
            return toView(state);
        }

        HttpResponse<String> response = postJson(URI.create(requiredDevicePollUrl()), objectMapper.createObjectNode()
                .put("device_auth_id", state.flow().deviceAuthId())
                .put("user_code", state.flow().userCode()));

        if (response.statusCode() == 403 || response.statusCode() == 404) {
            return toView(state);
        }

        if (response.statusCode() == 429) {
            int nextIntervalSeconds = state.flow().intervalSeconds() + 5;
            state = new State(new DeviceFlow(state.flow().deviceAuthId(), state.flow().userCode(), state.flow().verificationUri(),
                    nextIntervalSeconds, state.flow().expiresAt(), state.flow().codeChallenge()),
                    null,
                    "OpenAI rate limited the poll. Retrying in " + nextIntervalSeconds + " seconds.");
            return toView(state);
        }

        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("OpenAI token polling failed with status " + response.statusCode());
        }

        JsonNode payload = readJson(response.body(), "authorization response");
        AuthorizationResponse authorization = new AuthorizationResponse(
                requiredText(payload, "authorization_code"),
                requiredText(payload, "code_challenge"),
                requiredText(payload, "code_verifier")
        );

        HttpResponse<String> tokenResponse = postForm(URI.create(requiredTokenUrl()), authorizationCodeExchangeBody(requiredClientId(), authorization.authorizationCode(), authorization.codeVerifier()));
        if (tokenResponse.statusCode() / 100 != 2) {
            throw new IllegalStateException("OpenAI token exchange failed with status " + tokenResponse.statusCode());
        }

        JsonNode tokenPayload = readJson(tokenResponse.body(), "token response");
        TokenResponse token = new TokenResponse(
                requiredText(tokenPayload, "access_token"),
                requiredText(tokenPayload, "refresh_token"),
                requiredText(tokenPayload, "id_token")
        );

        Optional<String> accountId = extractAccountId(token.idToken());
        Instant expiresAt = Instant.now().plusSeconds(DEFAULT_EXPIRES_SECONDS);

        persistConnectedState(token.accessToken(), token.refreshToken(), token.idToken(), accountId.orElse(null), expiresAt);
        state = new State(null, new Tokens(token.accessToken(), token.refreshToken(), token.idToken(), accountId, expiresAt),
                "OpenAI connected.");
        return toView(state);
    }

    private void loadPersistedState() {
        if (appStateRepository == null) {
            return;
        }

        appStateRepository.loadOpenAiOAuthState().ifPresent(row -> {
            if (row.accessToken() == null || row.accessToken().isBlank()) {
                state = State.empty();
                return;
            }
            state = new State(null, new Tokens(row.accessToken(), row.refreshToken(), row.idToken(), Optional.ofNullable(row.accountId()).filter(accountId -> !accountId.isBlank()), row.expiresAt()),
                    "OpenAI connected.");
        });
    }

    private void persistConnectedState(String accessToken, String refreshToken, String idToken, String accountId, Instant expiresAt) {
        if (appStateRepository == null) {
            return;
        }
        appStateRepository.updateOpenAiOAuthState(accessToken, refreshToken, idToken, accountId, expiresAt);
    }

    private void clearPersistedState() {
        if (appStateRepository == null) {
            return;
        }
        appStateRepository.clearOpenAiOAuthState();
    }

    private HttpResponse<String> postJson(URI uri, JsonNode body) {
        return sendRequest(uri, "application/json", body.toString());
    }

    private HttpResponse<String> postForm(URI uri, String body) {
        return sendRequest(uri, "application/x-www-form-urlencoded", body);
    }

    private HttpResponse<String> sendRequest(URI uri, String contentType, String body) {
        try {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", contentType)
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("OpenAI OAuth request failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("OpenAI OAuth request interrupted", e);
        }
    }

    private JsonNode readJson(String body, String description) {
        try {
            JsonNode node = objectMapper.readTree(body);
            if (node == null || !node.isObject()) {
                throw new IllegalStateException("Unexpected " + description + " payload");
            }
            return node;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse OpenAI OAuth " + description, e);
        }
    }

    private String requiredClientId() {
        String clientId = properties.getClientId();
        if (clientId == null || clientId.isBlank()) {
            throw new IllegalStateException("OpenAI OAuth client ID (openai.oauth.client-id) is required");
        }
        return clientId;
    }

    private String requiredDeviceUserCodeUrl() {
        return requiredUrl(resolveUrl(properties.getDeviceUserCodeUrl(), properties.getIssuer(), "/api/accounts/deviceauth/usercode"), "openai.oauth.device-user-code-url");
    }

    private String requiredTokenUrl() {
        return requiredUrl(resolveUrl(properties.getTokenUrl(), properties.getIssuer(), "/oauth/token"), "openai.oauth.token-url");
    }

    private String requiredDevicePollUrl() {
        return requiredUrl(resolveUrl(properties.getDeviceTokenUrl(), properties.getIssuer(), "/api/accounts/deviceauth/token"), "openai.oauth.device-token-url");
    }

    private String resolvedVerificationUrl() {
        return requiredUrl(resolveUrl(properties.getVerificationUrl(), properties.getIssuer(), "/codex/device"), "openai.oauth.verification-url");
    }

    private String resolveUrl(String explicitUrl, String issuer, String path) {
        if (explicitUrl != null && !explicitUrl.isBlank()) {
            return explicitUrl;
        }
        if (issuer == null || issuer.isBlank()) {
            return null;
        }
        return issuer.endsWith("/") ? issuer.substring(0, issuer.length() - 1) + path : issuer + path;
    }

    private String requiredUrl(String url, String propertyName) {
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("OpenAI OAuth URL (" + propertyName + ") is required");
        }
        return url;
    }

    private String authorizationCodeExchangeBody(String clientId, String authorizationCode, String codeVerifier) {
        StringBuilder body = new StringBuilder();
        appendForm(body, "grant_type", "authorization_code");
        appendForm(body, "code", authorizationCode);
        appendForm(body, "redirect_uri", resolveUrl(null, properties.getIssuer(), "/deviceauth/callback"));
        appendForm(body, "client_id", clientId);
        appendForm(body, "code_verifier", codeVerifier);
        return body.toString();
    }

    private void appendForm(StringBuilder body, String name, String value) {
        if (body.length() > 0) {
            body.append('&');
        }
        body.append(encode(name)).append('=').append(encode(Objects.requireNonNull(value, name + " is required")));
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String requiredText(JsonNode payload, String field) {
        JsonNode node = payload.get(field);
        if (node == null || node.isNull() || node.asText().isBlank()) {
            throw new IllegalStateException("Missing OpenAI OAuth field: " + field);
        }
        return node.asText();
    }

    private String requiredText(JsonNode payload, String firstField, String aliasField) {
        JsonNode node = payload.get(firstField);
        if (node == null || node.isNull() || node.asText().isBlank()) {
            node = payload.get(aliasField);
        }
        if (node == null || node.isNull() || node.asText().isBlank()) {
            throw new IllegalStateException("Missing OpenAI OAuth field: " + firstField);
        }
        return node.asText();
    }

    private int optionalSeconds(JsonNode payload, String field, int defaultValue) {
        JsonNode node = payload.get(field);
        if (node == null || node.isNull() || node.asText().isBlank()) {
            return defaultValue;
        }
        try {
            return node.isNumber() ? node.asInt() : Integer.parseInt(node.asText());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Missing OpenAI OAuth field: " + field, e);
        }
    }

    private Optional<String> extractAccountId(String idToken) {
        try {
            String[] parts = idToken.split("\\.");
            if (parts.length < 2) {
                return Optional.empty();
            }

            byte[] payloadBytes = Base64.getUrlDecoder().decode(parts[1]);
            JsonNode payload = objectMapper.readTree(payloadBytes);
            JsonNode accountId = payload.get("chatgpt_account_id");
            if (accountId == null || accountId.isNull() || accountId.asText().isBlank()) {
                return Optional.empty();
            }
            return Optional.of(accountId.asText());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to decode OpenAI id_token", e);
        }
    }

    private OpenAiOAuthView toView(State state) {
        return new OpenAiOAuthView(
                state.tokens() != null,
                state.flow() != null,
                state.message(),
                state.flow() == null ? null : state.flow().userCode(),
                state.flow() == null ? null : state.flow().verificationUri(),
                null,
                state.flow() == null ? null : state.flow().intervalSeconds()
        );
    }

    private record State(DeviceFlow flow, Tokens tokens, String message) {
        static State empty() {
            return new State(null, null, "OpenAI is not connected.");
        }
    }

    private record DeviceFlow(String deviceAuthId, String userCode, String verificationUri,
                              int intervalSeconds, Instant expiresAt, String codeChallenge) {
    }

    private record Tokens(String accessToken, String refreshToken, String idToken, Optional<String> accountId, Instant expiresAt) {
    }

    private record DeviceAuthorizationResponse(String deviceAuthId, String userCode, String verificationUri,
                                               int intervalSeconds, int expiresInSeconds) {
    }

    private record AuthorizationResponse(String authorizationCode, String codeChallenge, String codeVerifier) {
    }

    private record TokenResponse(String accessToken, String refreshToken, String idToken) {
    }

    public record OpenAiOAuthView(boolean connected, boolean pending, String message, String userCode,
                                  String verificationUri, String verificationUriComplete, Integer intervalSeconds) {
        public String pollTrigger() {
            return pending ? "every " + intervalSeconds + "s" : "load";
        }
    }
}
