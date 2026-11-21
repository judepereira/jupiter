package com.judepereira.jupiter.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.judepereira.jupiter.agent.mcp.InboundMcpTokenService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class InboundMcpAuthFilterTests {
    private static final String TOKEN = "valid-token";

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
        for (String origin : new String[]{"not-an-origin", "https://foreign.example", "http://user@localhost",
                "http://localhost/path", "http://localhost?query=1", "http://localhost#fragment"}) {
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
    void unknownSessionReturnedByTheRegisteredFilterIsNotFound() throws Exception {
        var tokens = mock(InboundMcpTokenService.class);
        when(tokens.authenticate(TOKEN)).thenReturn(credential("own"));
        when(tokens.sessionStatus("stale-session", "own")).thenReturn(InboundMcpTokenService.SessionStatus.UNKNOWN);
        var request = request();
        request.addHeader("Authorization", "Bearer " + TOKEN);
        request.addHeader("Mcp-Session-Id", "stale-session");
        var chain = mock(FilterChain.class);

        assertThat(invoke(new InboundMcpAuthFilter(tokens), request, chain).getStatus()).isEqualTo(404);
        Mockito.verifyNoInteractions(chain);
    }

    @Test
    void sessionIsBoundToCredentialAndInvalidBearerIsRejected() throws Exception {
        var tokens = mock(InboundMcpTokenService.class);
        var own = credential("own");
        when(tokens.authenticate(TOKEN)).thenReturn(own);
        when(tokens.sessionStatus("session", "own")).thenReturn(InboundMcpTokenService.SessionStatus.VALID);
        var ownRequest = request();
        ownRequest.addHeader("Authorization", "Bearer " + TOKEN);
        ownRequest.addHeader("Mcp-Session-Id", "session");
        assertThat(invoke(new InboundMcpAuthFilter(tokens), ownRequest, mock(FilterChain.class)).getStatus())
                .isEqualTo(200);

        when(tokens.sessionStatus("session", "other")).thenReturn(InboundMcpTokenService.SessionStatus.MISMATCH);
        var otherRequest = request();
        otherRequest.addHeader("Authorization", "Bearer " + TOKEN);
        otherRequest.addHeader("Mcp-Session-Id", "session");
        when(tokens.authenticate(TOKEN)).thenReturn(credential("other"));
        assertThat(invoke(new InboundMcpAuthFilter(tokens), otherRequest, mock(FilterChain.class)).getStatus())
                .isEqualTo(403);

        when(tokens.sessionStatus(Mockito.anyString(), Mockito.anyString()))
                .thenReturn(InboundMcpTokenService.SessionStatus.UNKNOWN);
        var unknownRequest = request();
        unknownRequest.addHeader("Authorization", "Bearer " + TOKEN);
        unknownRequest.addHeader("Mcp-Session-Id", "unknown");
        assertThat(invoke(new InboundMcpAuthFilter(tokens), unknownRequest, mock(FilterChain.class)).getStatus())
                .isEqualTo(404);

        when(tokens.authenticate(TOKEN)).thenReturn(null);
        var staleRequest = request();
        staleRequest.addHeader("Authorization", "Bearer " + TOKEN);
        var staleResponse = invoke(new InboundMcpAuthFilter(tokens), staleRequest, mock(FilterChain.class));
        assertThat(staleResponse.getStatus()).isEqualTo(401);
        assertThat(staleResponse.getHeader("WWW-Authenticate")).isEqualTo("Bearer");
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
        return new InboundMcpTokenService.Credential(id, true, Set.of(), Set.of("list_projects"));
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

}
