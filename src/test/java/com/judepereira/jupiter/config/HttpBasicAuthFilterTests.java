package com.judepereira.jupiter.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockFilterChain;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class HttpBasicAuthFilterTests {

    @Test
    void rejectsMissingAndMalformedCredentials() throws Exception {
        HttpAuthProperties properties = properties("secret");
        HttpBasicAuthFilter filter = new HttpBasicAuthFilter(properties);

        MockHttpServletResponse response = invoke(filter, request("/"));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("WWW-Authenticate")).isEqualTo("Basic realm=\"Jupiter\"");
    }

    @Test
    void acceptsValidCredentials() throws Exception {
        HttpAuthProperties properties = properties("secret");
        HttpBasicAuthFilter filter = new HttpBasicAuthFilter(properties);
        MockHttpServletRequest request = request("/static/app.js");
        request.addHeader("Authorization", "Basic " + Base64.getEncoder().encodeToString("jupiter:secret".getBytes(StandardCharsets.UTF_8)));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isSameAs(request);
    }

    @Test
    void exemptsOnlyHealthGet() throws Exception {
        HttpBasicAuthFilter filter = new HttpBasicAuthFilter(properties("secret"));

        MockHttpServletResponse getResponse = invoke(filter, request("/health"));
        MockHttpServletRequest post = request("/health");
        post.setMethod("POST");
        MockHttpServletResponse postResponse = invoke(filter, post);

        assertThat(getResponse.getStatus()).isEqualTo(200);
        assertThat(postResponse.getStatus()).isEqualTo(401);
    }

    @Test
    void doesNothingWhenPasswordIsBlank() throws Exception {
        MockHttpServletResponse response = invoke(new HttpBasicAuthFilter(properties(" ")), request("/"));

        assertThat(response.getStatus()).isEqualTo(200);
    }

    private static HttpAuthProperties properties(String password) {
        HttpAuthProperties properties = new HttpAuthProperties();
        properties.setPassword(password);
        return properties;
    }

    private static MockHttpServletRequest request(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod("GET");
        request.setRequestURI(uri);
        request.setServletPath(uri);
        return request;
    }

    private static MockHttpServletResponse invoke(HttpBasicAuthFilter filter, MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }
}
