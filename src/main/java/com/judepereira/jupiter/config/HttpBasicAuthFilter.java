package com.judepereira.jupiter.config;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

public class HttpBasicAuthFilter implements Filter {

    private static final String AUTHORIZATION = "Authorization";
    private static final String CHALLENGE = "Basic realm=\"Jupiter\"";

    private final HttpAuthProperties properties;

    public HttpBasicAuthFilter(HttpAuthProperties properties) {
        this.properties = properties;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (!properties.enabled() || isHealthGet(request)) {
            chain.doFilter(request, response);
            return;
        }

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;
        String authorization = httpRequest.getHeader(AUTHORIZATION);
        if (isValid(authorization)) {
            chain.doFilter(request, response);
        } else {
            httpResponse.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            httpResponse.setHeader("WWW-Authenticate", CHALLENGE);
        }
    }

    private boolean isValid(String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, "Basic ", 0, 6)) {
            return false;
        }
        String encoded = authorization.substring(6).trim();
        if (encoded.isEmpty()) {
            return false;
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(encoded);
            String credentials = new String(decoded, StandardCharsets.UTF_8);
            int separator = credentials.indexOf(':');
            if (separator < 0) {
                return false;
            }
            byte[] expected = (properties.getUsername() + ":" + properties.getPassword()).getBytes(StandardCharsets.UTF_8);
            return MessageDigest.isEqual(expected, credentials.getBytes(StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean isHealthGet(ServletRequest request) {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        return "GET".equals(httpRequest.getMethod()) && "/health".equals(httpRequest.getRequestURI());
    }
}
