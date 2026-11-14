package com.judepereira.jupiter.openai.oauth;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.io.IOException;

final class OpenAiOAuthPayloads {
    private OpenAiOAuthPayloads() {
    }

    record ClientIdRequest(@JsonProperty("client_id") String clientId) {
    }
    record DevicePollRequest(@JsonProperty("device_auth_id") String deviceAuthId,
            @JsonProperty("user_code") String userCode) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record DeviceAuthorizationPayload(
            @JsonProperty("device_auth_id") @JsonDeserialize(using = StrictStringDeserializer.class) String deviceAuthId,
            @JsonProperty("user_code") @JsonAlias("usercode") @JsonDeserialize(using = StrictStringDeserializer.class) String userCode,
            @JsonDeserialize(using = StrictLongDeserializer.class) @JsonProperty("interval") Long interval,
            @JsonDeserialize(using = StrictLongDeserializer.class) @JsonProperty("expires") Long expires) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AuthorizationPayload(
            @JsonProperty("authorization_code") @JsonDeserialize(using = StrictStringDeserializer.class) String authorizationCode,
            @JsonProperty("code_challenge") @JsonDeserialize(using = StrictStringDeserializer.class) String codeChallenge,
            @JsonProperty("code_verifier") @JsonDeserialize(using = StrictStringDeserializer.class) String codeVerifier) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TokenPayload(
            @JsonProperty("access_token") @JsonDeserialize(using = StrictStringDeserializer.class) String accessToken,
            @JsonProperty("refresh_token") @JsonDeserialize(using = StrictStringDeserializer.class) String refreshToken,
            @JsonProperty("id_token") @JsonDeserialize(using = StrictStringDeserializer.class) String idToken,
            @JsonDeserialize(using = StrictLongDeserializer.class) @JsonProperty("expires_in") Long expiresIn) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record JwtClaims(
            @JsonProperty("chatgpt_account_id") @JsonDeserialize(using = StrictStringDeserializer.class) String accountId,
            @JsonProperty("https://api.openai.com/auth") AuthClaims auth,
            @JsonDeserialize(using = StrictLongDeserializer.class) @JsonProperty("exp") Long expiresAt) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AuthClaims(
            @JsonProperty("chatgpt_account_id") @JsonDeserialize(using = StrictStringDeserializer.class) String accountId) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TokenErrorPayload(@JsonDeserialize(using = OAuthErrorDeserializer.class) OAuthError error) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record OAuthError(@JsonProperty("code") @JsonDeserialize(using = StrictStringDeserializer.class) String code) {
    }

    static final class StrictStringDeserializer extends JsonDeserializer<String> {
        @Override
        public String deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (parser.currentToken() == JsonToken.VALUE_STRING) {
                return parser.getText();
            }
            return (String) context.handleUnexpectedToken(String.class, parser);
        }
    }

    static final class StrictLongDeserializer extends JsonDeserializer<Long> {
        @Override
        public Long deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            JsonToken token = parser.currentToken();
            if (token == JsonToken.VALUE_NUMBER_INT) {
                return parser.getLongValue();
            }
            if (token == JsonToken.VALUE_STRING) {
                try {
                    return Long.parseLong(parser.getText());
                } catch (NumberFormatException exception) {
                    return (Long) context.handleWeirdStringValue(Long.class, parser.getText(),
                            "expected an integer number or numeric string");
                }
            }
            return (Long) context.handleUnexpectedToken(Long.class, parser);
        }
    }

    static final class OAuthErrorDeserializer extends JsonDeserializer<OAuthError> {
        @Override
        public OAuthError deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (parser.currentToken() == JsonToken.VALUE_STRING) {
                return new OAuthError(parser.getText());
            }
            return parser.readValueAs(OAuthError.class);
        }
    }
}
