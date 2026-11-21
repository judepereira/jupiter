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
import java.util.EnumSet;
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
        HttpServletResponse res = (HttpServletResponse) response;
        if (!validOrigin(http)) {
            res.setStatus(HttpServletResponse.SC_FORBIDDEN);
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
        String session = http.getHeader("Mcp-Session-Id");
        if (session != null) {
            InboundMcpTokenService.SessionStatus status = tokens.sessionStatus(session, credential.id());
            if (status == InboundMcpTokenService.SessionStatus.UNKNOWN) {
                res.setStatus(HttpServletResponse.SC_NOT_FOUND);
                return;
            }
            if (status == InboundMcpTokenService.SessionStatus.MISMATCH) {
                res.setStatus(HttpServletResponse.SC_FORBIDDEN);
                return;
            }
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
                    tokens.bindSession(value, credential.id());
                }
            }
        });
        if ("DELETE".equalsIgnoreCase(http.getMethod()) && session != null) {
            tokens.invalidateSession(session);
        }
    }

    private static void unauthorized(HttpServletResponse response) throws IOException {
        response.setHeader("WWW-Authenticate", "Bearer");
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    }

    private static boolean validOrigin(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        if (origin == null) {
            return true;
        }
        try {
            URI supplied = URI.create(origin);
            return RequestOrigin.matches(supplied, request) && supplied.getRawUserInfo() == null
                    && supplied.getRawPath().isEmpty() && supplied.getRawQuery() == null
                    && supplied.getRawFragment() == null;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

}
