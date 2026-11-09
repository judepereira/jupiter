package com.judepereira.jupiter.config;

import com.judepereira.jupiter.agent.mcp.InboundMcpTokenService;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.EnumSet;
import java.util.HexFormat;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class InboundMcpAuthFilter implements Filter {
    public static final String CREDENTIAL_ATTRIBUTE = InboundMcpAuthFilter.class.getName() + ".credential";
    private final InboundMcpTokenService tokens;

    public InboundMcpAuthFilter(InboundMcpTokenService tokens) {
        this.tokens = tokens;
    }

    @Bean
    FilterRegistrationBean<InboundMcpAuthFilter> inboundMcpAuthFilterRegistration() {
        FilterRegistrationBean<InboundMcpAuthFilter> registration = new FilterRegistrationBean<>(this);
        registration.addUrlPatterns("/mcp");
        registration.setDispatcherTypes(EnumSet.allOf(DispatcherType.class));
        registration.setAsyncSupported(true);
        registration.setOrder(Integer.MIN_VALUE + 1);
        return registration;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest http = (HttpServletRequest) request;
        if (!"/mcp".equals(http.getRequestURI())) {
            chain.doFilter(request, response);
            return;
        }
        HttpServletResponse res = (HttpServletResponse) response;
        if (!validOrigin(http)) {
            res.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        String authorization = http.getHeader("Authorization");
        if (authorization == null || authorization.length() <= 7
                || !authorization.regionMatches(true, 0, "Bearer ", 0, 7) || authorization.substring(7).isBlank()
                || !authorization.substring(7).equals(authorization.substring(7).trim())) {
            unauthorized(res);
            return;
        }
        String bearer = authorization.substring(7);
        InboundMcpTokenService.Credential credential = tokens.authenticate(bearer);
        if (credential == null) {
            unauthorized(res);
            return;
        }
        String fingerprint = fingerprint(bearer);
        String session = http.getHeader("Mcp-Session-Id");
        if (session != null && !tokens.isSessionBound(session, credential.id(), fingerprint)) {
            res.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        http.setAttribute(CREDENTIAL_ATTRIBUTE, credential);
        chain.doFilter(request, new HttpServletResponseWrapper(res) {
            @Override
            public void setHeader(String name, String value) {
                super.setHeader(name, value);
                bind(name, value);
            }

            @Override
            public void addHeader(String name, String value) {
                super.addHeader(name, value);
                bind(name, value);
            }

            private void bind(String name, String value) {
                if ("Mcp-Session-Id".equalsIgnoreCase(name) && value != null) {
                    tokens.bindSession(value, credential.id(), fingerprint);
                }
            }
        });
        if ("DELETE".equalsIgnoreCase(http.getMethod()) && session != null) {
            tokens.invalidateSession(session);
        }
    }

    private static void unauthorized(HttpServletResponse response) throws IOException {
        response.setHeader("WWW-Authenticate", "Bearer");
        response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
    }

    private static String fingerprint(String bearer) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(bearer.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static boolean validOrigin(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        if (origin == null) {
            return true;
        }
        try {
            URI supplied = URI.create(origin);
            String scheme = PublicRequestScheme.isHttps(request) ? "https" : "http";
            int publicPort = publicPort(request, scheme);
            int expectedPort = effectivePort(scheme, publicPort);
            return supplied.getScheme() != null && supplied.getScheme().equalsIgnoreCase(scheme)
                    && supplied.getHost() != null && supplied.getHost().equalsIgnoreCase(request.getServerName())
                    && effectivePort(supplied.getScheme(), supplied.getPort()) == expectedPort
                    && supplied.getRawUserInfo() == null && supplied.getRawPath().isEmpty()
                    && supplied.getRawQuery() == null && supplied.getRawFragment() == null;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static int publicPort(HttpServletRequest request, String scheme) {
        String forwarded = request.getHeader("Forwarded");
        if (forwarded != null) {
            for (String parameter : forwarded.split(",", 2)[0].split(";")) {
                String[] parts = parameter.trim().split("=", 2);
                if (parts.length == 2 && parts[0].equalsIgnoreCase("host")) {
                    return hostPort(parts[1].replace("\"", ""), scheme, request.getServerPort());
                }
            }
        }
        String host = request.getHeader("X-Forwarded-Host");
        if (host != null && !host.isBlank()) {
            return hostPort(host.split(",", 2)[0].trim(), scheme, request.getServerPort());
        }
        String port = request.getHeader("X-Forwarded-Port");
        if (port != null) {
            try {
                return Integer.parseInt(port.split(",", 2)[0].trim());
            } catch (NumberFormatException ignored) {
                return request.getServerPort();
            }
        }
        return request.getServerPort();
    }

    private static int hostPort(String host, String scheme, int fallback) {
        try {
            URI uri = URI.create("//" + host);
            return uri.getPort() < 0 ? fallback : uri.getPort();
        } catch (IllegalArgumentException exception) {
            return fallback;
        }
    }

    private static int effectivePort(String scheme, int port) {
        return port < 0 ? ("https".equalsIgnoreCase(scheme) ? 443 : 80) : port;
    }
}
