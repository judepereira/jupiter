package com.judepereira.jupiter.openai.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OpenAiOAuthPayloadsTests {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectReader READER = OpenAiOAuthPayloads.reader(JSON);

    @Test
    void ignoresUnknownFieldsAndAcceptsDeviceAliasesAndNumericStrings() throws Exception {
        var device = READER.readValue("""
                {"device_auth_id":"device","usercode":"USER","interval":"5","expires":600,"extra":true}
                """, OpenAiOAuthPayloads.DeviceAuthorizationPayload.class);
        assertThat(device.deviceAuthId()).isEqualTo("device");
        assertThat(device.userCode()).isEqualTo("USER");
        assertThat(device.interval()).isEqualTo(5L);
        assertThat(device.expires()).isEqualTo(600L);
        assertThat(READER.readValue("{\"authorization_code\":\"code\",\"extra\":1}",
                OpenAiOAuthPayloads.AuthorizationPayload.class).authorizationCode()).isEqualTo("code");
        assertThat(READER.readValue("{\"access_token\":\"access\",\"extra\":1}", OpenAiOAuthPayloads.TokenPayload.class)
                .accessToken()).isEqualTo("access");
        assertThat(READER
                .readValue("{\"error\":\"invalid_grant\",\"extra\":1}", OpenAiOAuthPayloads.TokenErrorPayload.class)
                .error().code()).isEqualTo("invalid_grant");
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
        assertThat(READER.readValue("{\"error\":\"invalid_grant\"}", OpenAiOAuthPayloads.TokenErrorPayload.class)
                .error().code()).isEqualTo("invalid_grant");
        assertThat(READER.readValue("{\"error\":{\"code\":\"invalid_grant\",\"extra\":1}}",
                OpenAiOAuthPayloads.TokenErrorPayload.class).error().code()).isEqualTo("invalid_grant");
        assertThatThrownBy(() -> READER.readValue("{\"error\":1}", OpenAiOAuthPayloads.TokenErrorPayload.class))
                .isInstanceOf(Exception.class);
        assertThatThrownBy(
                () -> READER.readValue("{\"error\":{\"code\":true}}", OpenAiOAuthPayloads.TokenErrorPayload.class))
                .isInstanceOf(Exception.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "1.5", "true", "[]", "{}"})
    void rejectsNonStringTokenFields(String invalid) {
        assertThatThrownBy(
                () -> READER.readValue("{\"access_token\":" + invalid + "}", OpenAiOAuthPayloads.TokenPayload.class))
                .isInstanceOf(Exception.class);
    }

    @Test
    void acceptsNullableAndMissingStringsAndRejectsScalarContainers() throws Exception {
        assertThat(READER.readValue("{}", OpenAiOAuthPayloads.TokenPayload.class).accessToken()).isNull();
        assertThat(READER.readValue("{\"access_token\":null}", OpenAiOAuthPayloads.TokenPayload.class).accessToken())
                .isNull();
        assertThat(READER.readValue("{\"access_token\":\"\"}", OpenAiOAuthPayloads.TokenPayload.class).accessToken())
                .isEmpty();
        for (String invalid : new String[]{"1", "1.5", "true", "[]", "{}"}) {
            assertThatThrownBy(() -> READER.readValue("{\"access_token\":" + invalid + "}",
                    OpenAiOAuthPayloads.TokenPayload.class)).isInstanceOf(Exception.class);
        }
    }

    @Test
    void acceptsLongBoundsAndSignedNumericStrings() throws Exception {
        assertExpiry("9223372036854775807", Long.MAX_VALUE);
        assertExpiry("-9223372036854775808", Long.MIN_VALUE);
        assertExpiry("\"+7\"", 7L);
        assertExpiry("\"-7\"", -7L);
        assertExpiry("\" 7 \"", 7L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.0", "1.5", "-1.5", "\"1.0\"", "\"1.5\"", "\"-1.5\"", "9223372036854775808",
            "-9223372036854775809", "\"9223372036854775808\"", "\"-9223372036854775809\"", "true", "\"\"", "\"   \"",
            "[]", "{}"})
    void rejectsInvalidExpiryValues(String invalid) {
        assertThatThrownBy(
                () -> READER.readValue("{\"expires_in\":" + invalid + "}", OpenAiOAuthPayloads.TokenPayload.class))
                .isInstanceOf(Exception.class);
    }

    @Test
    void readerDoesNotChangeSharedMapperAndIsSafeForConcurrentReads() throws Exception {
        var before = JSON.readValue("{\"access_token\":1}", OpenAiOAuthPayloads.TokenPayload.class);
        assertThat(before.accessToken()).isEqualTo("1");
        OpenAiOAuthPayloads.reader(JSON).readValue("{\"expires_in\":\"12\"}", OpenAiOAuthPayloads.TokenPayload.class);
        assertThat(JSON.readValue("{\"access_token\":1}", OpenAiOAuthPayloads.TokenPayload.class).accessToken())
                .isEqualTo("1");
        assertThat(JSON.readValue("{\"expires_in\":1.5}", OpenAiOAuthPayloads.TokenPayload.class).expiresIn())
                .isEqualTo(1L);
        try (var executor = Executors.newFixedThreadPool(4)) {
            var tasks = IntStream.range(0, 32)
                    .<Callable<Long>>mapToObj(i -> () -> READER
                            .readValue("{\"expires_in\":\"" + i + "\"}", OpenAiOAuthPayloads.TokenPayload.class)
                            .expiresIn())
                    .toList();
            for (var result : executor.invokeAll(tasks)) {
                assertThat(result.get()).isBetween(0L, 31L);
            }
        }
    }

    private static void assertExpiry(String jsonValue, long expected) throws Exception {
        assertThat(READER.readValue("{\"expires_in\":" + jsonValue + "}", OpenAiOAuthPayloads.TokenPayload.class)
                .expiresIn()).isEqualTo(expected);
    }
}
