package com.judepereira.jupiter.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.judepereira.jupiter.agent.mcp.InboundMcpTokenService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class InboundMcpAuthFilterTests {
    private static final String TOKEN = "valid-token";
    private static final String FINGERPRINT = "";

    @Test
    void missingOriginAndValidBearerAreAccepted() throws Exception {
        InboundMcpTokenService tokens = mock(InboundMcpTokenService.class);
        var credential = credential("own");
        when(tokens.authenticate(TOKEN)).thenReturn(credential);
        var request = request();
        request.addHeader("Authorization", "Bearer " + TOKEN);
        var chain = mock(FilterChain.class);
        var response = invoke(new InboundMcpAuthFilter(tokens), request, chain);
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(request.getAttribute(InboundMcpAuthFilter.CREDENTIAL_ATTRIBUTE)).isEqualTo(credential);
        verify(chain).doFilter(ArgumentMatchers.any(), ArgumentMatchers.any());
    }

    @Test
    void rejectsInvalidAndForeignOrigins() throws Exception {
        for (String origin : new String[]{"not-an-origin", "https://foreign.example"}) {
            var tokens = mock(InboundMcpTokenService.class);
            var request = request();
            request.addHeader("Origin", origin);
            request.addHeader("Authorization", "Bearer " + TOKEN);
            var chain = mock(FilterChain.class);
            var response = invoke(new InboundMcpAuthFilter(tokens), request, chain);
            assertThat(response.getStatus()).isEqualTo(403);
            Mockito.verifyNoInteractions(chain);
            Mockito.verifyNoInteractions(tokens);
        }
    }

    @Test
    void sessionIsBoundToCredentialAndRotatedOrRevokedBearerIsRejected() throws Exception {
        var tokens = mock(InboundMcpTokenService.class);
        var own = credential("own");
        when(tokens.authenticate(TOKEN)).thenReturn(own);
        when(tokens.isSessionBound("session", "own", fingerprint(TOKEN))).thenReturn(true);
        var ownRequest = request();
        ownRequest.addHeader("Authorization", "Bearer " + TOKEN);
        ownRequest.addHeader("Mcp-Session-Id", "session");
        assertThat(invoke(new InboundMcpAuthFilter(tokens), ownRequest, mock(FilterChain.class)).getStatus())
                .isEqualTo(200);

        when(tokens.isSessionBound("session", "other", fingerprint(TOKEN))).thenReturn(false);
        var otherRequest = request();
        otherRequest.addHeader("Authorization", "Bearer " + TOKEN);
        otherRequest.addHeader("Mcp-Session-Id", "session");
        when(tokens.authenticate(TOKEN)).thenReturn(credential("other"));
        assertThat(invoke(new InboundMcpAuthFilter(tokens), otherRequest, mock(FilterChain.class)).getStatus())
                .isEqualTo(403);

        when(tokens.authenticate(TOKEN)).thenReturn(null);
        var staleRequest = request();
        staleRequest.addHeader("Authorization", "Bearer " + TOKEN);
        assertThat(invoke(new InboundMcpAuthFilter(tokens), staleRequest, mock(FilterChain.class)).getStatus())
                .isEqualTo(401);
    }

    @Test
    void policyUsesCurrentAuthenticatedCredential() throws Exception {
        var tokens = mock(InboundMcpTokenService.class);
        var updated = credential("current");
        when(tokens.authenticate(TOKEN)).thenReturn(updated);
        var request = request();
        request.addHeader("Authorization", "Bearer " + TOKEN);
        var chain = mock(FilterChain.class);
        invoke(new InboundMcpAuthFilter(tokens), request, chain);
        assertThat(request.getAttribute(InboundMcpAuthFilter.CREDENTIAL_ATTRIBUTE)).isSameAs(updated);
    }

    private static InboundMcpTokenService.Credential credential(String id) {
        return new InboundMcpTokenService.Credential(id, "name", true, Set.of(), Set.of("list_projects"));
    }

    private static MockHttpServletRequest request() {
        var request = new MockHttpServletRequest();
        request.setRequestURI("/mcp");
        request.setServerName("localhost");
        request.setServerPort(80);
        return request;
    }

    private static MockHttpServletResponse invoke(InboundMcpAuthFilter filter, MockHttpServletRequest request,
            FilterChain chain) throws IOException, ServletException {
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response;
    }

    private static String fingerprint(String token) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }
}
