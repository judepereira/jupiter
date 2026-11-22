package com.judepereira.jupiter.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class PublicRequestSchemeTests {

    @Test
    void usesContainerNormalizedDirectAndProxySchemes() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        assertThat(PublicRequestScheme.isHttps(request)).isFalse();

        request.setSecure(true);
        assertThat(PublicRequestScheme.isHttps(request)).isTrue();

        request.setSecure(false);
        request.setScheme("https");
        assertThat(PublicRequestScheme.isHttps(request)).isTrue();
    }

    @Test
    void ignoresUntrustedForwardedHeaders() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-Proto", "https");
        request.addHeader("Forwarded", "proto=https");

        assertThat(PublicRequestScheme.isHttps(request)).isFalse();
    }
}
