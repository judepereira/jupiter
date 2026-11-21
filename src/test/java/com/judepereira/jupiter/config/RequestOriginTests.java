package com.judepereira.jupiter.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class RequestOriginTests {

    @Test
    void requiresSchemeAndHost() {
        var request = request();

        assertThat(RequestOrigin.matches(URI.create("/path"), request)).isFalse();
        assertThat(RequestOrigin.matches(URI.create("http:///path"), request)).isFalse();
    }

    @Test
    void matchesCaseInsensitiveHostAndSchemeAndImplicitDefaultPort() {
        var request = request();

        assertThat(RequestOrigin.matches(URI.create("HTTP://LOCALHOST"), request)).isTrue();
        assertThat(RequestOrigin.matches(URI.create("http://localhost:80"), request)).isTrue();
        assertThat(RequestOrigin.matches(URI.create("http://localhost:81"), request)).isFalse();
        assertThat(RequestOrigin.matches(URI.create("http://foreign.example"), request)).isFalse();
        assertThat(RequestOrigin.matches(URI.create("https://localhost"), request)).isFalse();
    }

    @Test
    void fallsBackToSchemeDefaultWhenRequestPortIsInvalid() {
        var request = request();
        request.setServerPort(0);

        assertThat(RequestOrigin.matches(URI.create("http://localhost"), request)).isTrue();
        assertThat(RequestOrigin.matches(URI.create("http://localhost:0"), request)).isFalse();

        request.setScheme("https");
        assertThat(RequestOrigin.matches(URI.create("https://localhost"), request)).isTrue();
    }

    private static MockHttpServletRequest request() {
        var request = new MockHttpServletRequest();
        request.setServerName("localhost");
        request.setServerPort(80);
        return request;
    }
}
