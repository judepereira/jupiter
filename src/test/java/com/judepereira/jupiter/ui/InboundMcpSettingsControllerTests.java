package com.judepereira.jupiter.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.judepereira.jupiter.agent.mcp.InboundMcpTokenService;
import com.judepereira.jupiter.persistence.AppStateService;
import java.util.List;
import java.util.Set;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.web.server.ResponseStatusException;

class InboundMcpSettingsControllerTests {
    private final InboundMcpTokenService tokens = mock(InboundMcpTokenService.class);
    private final AppStateService appState = mock(AppStateService.class);
    private final InboundMcpSettingsController controller = new InboundMcpSettingsController(tokens, appState);

    @Test
    void createRejectsWhitespaceNameWithoutExposingToken() {
        when(tokens.list()).thenReturn(List.of());
        when(appState.listVisibleProjects()).thenReturn(List.of());
        when(tokens.create("   ", false, Set.of(), Set.of())).thenThrow(new IllegalArgumentException("bad name"));
        var model = new ExtendedModelMap();
        var response = new MockHttpServletResponse();

        controller.create("   ", false, Set.of(), Set.of(), sameOriginRequest(), response, model);

        assertThat(model.getAttribute("settingsError"))
                .isEqualTo("Unable to create credential. Check the name, projects, and tools.");
        assertThat(model).doesNotContainKey("issuedToken");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    }

    @Test
    void staleCredentialRevealAndRotateReturnSafeErrorsAndNoStore() {
        when(tokens.list()).thenReturn(List.of());
        when(appState.listVisibleProjects()).thenReturn(List.of());
        when(tokens.reveal("stale")).thenThrow(new IllegalArgumentException("Credential not found"));
        when(tokens.rotate("stale")).thenThrow(new IllegalArgumentException("Credential not found"));
        var revealModel = new ExtendedModelMap();
        var revealResponse = new MockHttpServletResponse();
        controller.reveal("stale", sameOriginRequest(), revealResponse, revealModel);
        assertThat(revealModel.getAttribute("settingsError"))
                .isEqualTo("Unable to reveal credential. It may no longer be available.");
        assertThat(revealModel).doesNotContainKey("issuedToken");
        assertThat(revealResponse.getHeader("Cache-Control")).isEqualTo("no-store");

        var rotateModel = new ExtendedModelMap();
        var rotateResponse = new MockHttpServletResponse();
        controller.rotate("stale", sameOriginRequest(), rotateResponse, rotateModel);
        assertThat(rotateModel.getAttribute("settingsError"))
                .isEqualTo("Unable to rotate credential. It may no longer be available.");
        assertThat(rotateModel).doesNotContainKey("issuedToken");
        assertThat(rotateResponse.getHeader("Cache-Control")).isEqualTo("no-store");
    }

    @Test
    void crossOriginRequestIsForbiddenBeforeTokenOperation() {
        var request = sameOriginRequest();
        request.setSecure(true);
        request.addHeader("Origin", "https://attacker.example");
        request.addHeader("Forwarded", "proto=https;host=localhost");

        Assertions.assertThatThrownBy(
                () -> controller.reveal("credential", request, new MockHttpServletResponse(), new ExtendedModelMap()))
                .isInstanceOf(ResponseStatusException.class);
        verify(tokens, never()).reveal("credential");
    }

    @Test
    void httpsSameOriginIsAcceptedRegardlessOfForwardedHeader() {
        var request = sameOriginRequest();
        request.setSecure(true);
        request.setServerPort(443);
        request.removeHeader("Origin");
        request.addHeader("Origin", "https://localhost");
        request.addHeader("Forwarded", "proto=http;host=attacker.example");
        when(tokens.reveal("credential")).thenReturn("secret-token");
        when(tokens.list()).thenReturn(List.of());
        when(appState.listVisibleProjects()).thenReturn(List.of());
        var model = new ExtendedModelMap();

        controller.reveal("credential", request, new MockHttpServletResponse(), model);

        assertThat(model.getAttribute("issuedToken")).isEqualTo("secret-token");
        verify(tokens).reveal("credential");
    }

    @Test
    void refererMayContainPathQueryAndFragmentButOriginMustBeSerialized() {
        var refererRequest = sameOriginRequest();
        refererRequest.removeHeader("Origin");
        refererRequest.addHeader("Referer", "http://localhost/path?q=1#section");
        when(tokens.reveal("credential")).thenReturn("secret-token");
        when(tokens.list()).thenReturn(List.of());
        when(appState.listVisibleProjects()).thenReturn(List.of());
        controller.reveal("credential", refererRequest, new MockHttpServletResponse(), new ExtendedModelMap());
        verify(tokens).reveal("credential");

        var originRequest = sameOriginRequest();
        originRequest.removeHeader("Origin");
        originRequest.addHeader("Origin", "http://localhost/path");
        Assertions.assertThatThrownBy(() -> controller.reveal("credential", originRequest,
                new MockHttpServletResponse(), new ExtendedModelMap())).isInstanceOf(ResponseStatusException.class);
    }

    private MockHttpServletRequest sameOriginRequest() {
        var request = new MockHttpServletRequest();
        request.setServerName("localhost");
        request.setServerPort(80);
        request.addHeader("Origin", "http://localhost");
        return request;
    }
}
