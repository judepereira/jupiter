package com.judepereira.jupiter.openai.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OpenAiOAuthPayloadsTests {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void ignoresUnknownFieldsAndAcceptsDeviceAliasesAndNumericStrings() throws Exception {
        var device = JSON.readValue("""
                {"device_auth_id":"device","usercode":"USER","interval":"5","expires":600,"extra":true}
                """, OpenAiOAuthPayloads.DeviceAuthorizationPayload.class);
        assertThat(device.deviceAuthId()).isEqualTo("device");
        assertThat(device.userCode()).isEqualTo("USER");
        assertThat(device.interval()).isEqualTo(5L);
        assertThat(device.expires()).isEqualTo(600L);
        assertThat(JSON.readValue("{\"authorization_code\":\"code\",\"extra\":1}",
                OpenAiOAuthPayloads.AuthorizationPayload.class).authorizationCode()).isEqualTo("code");
        assertThat(JSON.readValue("{\"access_token\":\"access\",\"extra\":1}", OpenAiOAuthPayloads.TokenPayload.class)
                .accessToken()).isEqualTo("access");
        assertThat(
                JSON.readValue("{\"error\":\"invalid_grant\",\"extra\":1}", OpenAiOAuthPayloads.TokenErrorPayload.class)
                        .error().code())
                .isEqualTo("invalid_grant");
    }

    @Test
    void serializesTypedRequestsWithWireFieldNames() throws Exception {
        assertThat(JSON.writeValueAsString(new OpenAiOAuthPayloads.ClientIdRequest("client")))
                .isEqualTo("{\"client_id\":\"client\"}");
        assertThat(JSON.writeValueAsString(new OpenAiOAuthPayloads.DevicePollRequest("device", "USER")))
                .isEqualTo("{\"device_auth_id\":\"device\",\"user_code\":\"USER\"}");
    }

    @Test
    void deserializesFlatAndNestedOAuthErrors() throws Exception {
        assertThat(JSON.readValue("{\"error\":\"invalid_grant\"}", OpenAiOAuthPayloads.TokenErrorPayload.class).error()
                .code()).isEqualTo("invalid_grant");
        assertThat(JSON.readValue("{\"error\":{\"code\":\"invalid_grant\",\"extra\":1}}",
                OpenAiOAuthPayloads.TokenErrorPayload.class).error().code()).isEqualTo("invalid_grant");
    }

    @ParameterizedTest
    @ValueSource(strings = {"true", "1", "{}"})
    void rejectsNonStringTokenFields(String invalid) {
        assertThatThrownBy(
                () -> JSON.readValue("{\"access_token\":" + invalid + "}", OpenAiOAuthPayloads.TokenPayload.class))
                .isInstanceOf(Exception.class);
    }

    @Test
    void acceptsNullableExpiryAndRejectsFractionalOverflowingNumbers() throws Exception {
        assertThat(JSON.readValue("{\"expires_in\":null}", OpenAiOAuthPayloads.TokenPayload.class).expiresIn())
                .isNull();
        for (String number : new String[]{"1.5", "9223372036854775808"}) {
            assertThatThrownBy(
                    () -> JSON.readValue("{\"expires_in\":" + number + "}", OpenAiOAuthPayloads.TokenPayload.class))
                    .isInstanceOf(Exception.class);
        }
    }
}
