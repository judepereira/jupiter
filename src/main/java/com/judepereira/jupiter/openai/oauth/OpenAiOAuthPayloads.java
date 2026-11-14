package com.judepereira.jupiter.openai.oauth;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import java.io.IOException;

final class OpenAiOAuthPayloads {
    private OpenAiOAuthPayloads() {
    }

    static ObjectReader reader(ObjectMapper mapper) {
        ObjectMapper oauthMapper = mapper.copy();
        var config = oauthMapper.coercionConfigFor(LogicalType.Textual);
        config.setCoercion(CoercionInputShape.Integer, CoercionAction.Fail);
        config.setCoercion(CoercionInputShape.Float, CoercionAction.Fail);
        config.setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
        var integers = oauthMapper.coercionConfigFor(LogicalType.Integer);
        integers.setCoercion(CoercionInputShape.Float, CoercionAction.Fail);
        integers.setCoercion(CoercionInputShape.EmptyString, CoercionAction.Fail);
        integers.setCoercion(CoercionInputShape.String, CoercionAction.TryConvert);
        oauthMapper.configure(DeserializationFeature.ACCEPT_FLOAT_AS_INT, false);
        return oauthMapper.reader();
    }

    record ClientIdRequest(@JsonProperty("client_id") String clientId) {
    }

    record DevicePollRequest(@JsonProperty("device_auth_id") String deviceAuthId,
            @JsonProperty("user_code") String userCode) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record DeviceAuthorizationPayload(@JsonProperty("device_auth_id") String deviceAuthId,
            @JsonProperty("user_code") @JsonAlias("usercode") String userCode, @JsonProperty("interval") Long interval,
            @JsonProperty("expires") Long expires) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AuthorizationPayload(@JsonProperty("authorization_code") String authorizationCode,
            @JsonProperty("code_challenge") String codeChallenge, @JsonProperty("code_verifier") String codeVerifier) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TokenPayload(@JsonProperty("access_token") String accessToken,
            @JsonProperty("refresh_token") String refreshToken, @JsonProperty("id_token") String idToken,
            @JsonProperty("expires_in") Long expiresIn) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TokenErrorPayload(@JsonDeserialize(using = OAuthErrorDeserializer.class) OAuthError error) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record OAuthError(@JsonProperty("code") String code) {
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
