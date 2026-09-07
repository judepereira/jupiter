package com.judepereira.jupiter.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class PublicRequestSchemeTests {

    @Test
    void usesDirectAndForwardedSchemes() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        assertThat(PublicRequestScheme.isHttps(request)).isFalse();

        request.setSecure(true);
        assertThat(PublicRequestScheme.isHttps(request)).isTrue();

        request.addHeader("X-Forwarded-Proto", "https, http");
        assertThat(PublicRequestScheme.isHttps(request)).isTrue();
        request.removeHeader("X-Forwarded-Proto");
        request.addHeader("Forwarded", "for=1.2.3.4;proto=https, for=5.6.7.8;proto=http");
        assertThat(PublicRequestScheme.isHttps(request)).isTrue();
    }

    @Test
    void doesNotTreatLaterProxyValueAsPublicScheme() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-Proto", "http, https");

        assertThat(PublicRequestScheme.isHttps(request)).isFalse();
    }
}
