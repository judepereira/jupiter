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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.web.server.ResponseStatusException;

class InboundMcpSettingsControllerTests {
    private final InboundMcpTokenService tokens = mock(InboundMcpTokenService.class);
    private final AppStateService appState = mock(AppStateService.class);
    private final InboundMcpSettingsController controller = new InboundMcpSettingsController(tokens, appState);

    @Test
    void creationReturnsTokenWithoutCachingAndFailureReopensForm() {
        when(tokens.list()).thenReturn(List.of());
        when(appState.listVisibleProjects()).thenReturn(List.of());
        when(tokens.create("credential", true, Set.of(), Set.of("list_projects"))).thenReturn("new-secret");
        var response = new MockHttpServletResponse();
        var model = new ExtendedModelMap();
        controller.create("credential", true, Set.of(), Set.of("list_projects"), sameOriginRequest(), response, model);
        assertThat(model.getAttribute("issuedToken")).isEqualTo("new-secret");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");

        when(tokens.create("bad", false, Set.of(), Set.of())).thenThrow(new IllegalArgumentException());
        var failedModel = new ExtendedModelMap();
        controller.create("bad", false, Set.of(), Set.of(), sameOriginRequest(), response, failedModel);
        assertThat(failedModel.getAttribute("showCreateForm")).isEqualTo(true);
        assertThat(failedModel).doesNotContainKey("issuedToken");
    }

    @Test
    void removedRevealAndRotateEndpointsAreUnavailable() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).setViewResolvers((viewName, locale) -> null).build();
        assertThat(mvc.perform(MockMvcRequestBuilders.post("/ui/settings/inbound-mcp/id/reveal")).andReturn()
                .getResponse().getStatus()).isEqualTo(404);
        assertThat(mvc.perform(MockMvcRequestBuilders.post("/ui/settings/inbound-mcp/id/rotate")).andReturn()
                .getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void crossOriginCreationIsForbiddenBeforeTokenOperation() {
        var request = sameOriginRequest();
        request.removeHeader("Origin");
        request.addHeader("Origin", "https://attacker.example");
        Assertions
                .assertThatThrownBy(() -> controller.create("credential", true, Set.of(), Set.of(), request,
                        new MockHttpServletResponse(), new ExtendedModelMap()))
                .isInstanceOf(ResponseStatusException.class);
        verify(tokens, never()).create("credential", true, Set.of(), Set.of());
    }

    @Test
    void rejectsOriginComponentsAndRefererUserInfo() {
        for (String value : List.of("http://user@localhost", "http://localhost?query=1", "http://localhost/path",
                "http://localhost#fragment")) {
            var request = sameOriginRequest();
            request.removeHeader("Origin");
            request.addHeader("Origin", value);
            Assertions
                    .assertThatThrownBy(() -> controller.create("credential", true, Set.of(), Set.of(), request,
                            new MockHttpServletResponse(), new ExtendedModelMap()))
                    .isInstanceOf(ResponseStatusException.class);
        }

        var referer = sameOriginRequest();
        referer.removeHeader("Origin");
        referer.addHeader("Referer", "http://user@localhost");
        Assertions
                .assertThatThrownBy(() -> controller.create("credential", true, Set.of(), Set.of(), referer,
                        new MockHttpServletResponse(), new ExtendedModelMap()))
                .isInstanceOf(ResponseStatusException.class);
        verify(tokens, never()).create("credential", true, Set.of(), Set.of());
    }

    @Test
    void normalizedHttpsOriginIgnoresForwardedHeaders() {
        var request = sameOriginRequest();
        request.setScheme("https");
        request.setSecure(true);
        request.setServerPort(443);
        request.removeHeader("Origin");
        request.addHeader("Origin", "HTTPS://LOCALHOST");
        request.addHeader("Forwarded", "proto=http;host=attacker.example");
        when(tokens.list()).thenReturn(List.of());
        when(appState.listVisibleProjects()).thenReturn(List.of());
        when(tokens.create("credential", true, Set.of(), Set.of())).thenReturn("secret");

        controller.create("credential", true, Set.of(), Set.of(), request, new MockHttpServletResponse(),
                new ExtendedModelMap());

        verify(tokens).create("credential", true, Set.of(), Set.of());
    }

    @Test
    void refererMayContainPathQueryAndFragmentButOriginMustBeSerialized() {
        var referer = sameOriginRequest();
        referer.removeHeader("Origin");
        referer.addHeader("Referer", "http://localhost/path?q=1#section");
        when(tokens.list()).thenReturn(List.of());
        when(appState.listVisibleProjects()).thenReturn(List.of());
        when(tokens.create("credential", true, Set.of(), Set.of())).thenReturn("secret");
        controller.create("credential", true, Set.of(), Set.of(), referer, new MockHttpServletResponse(),
                new ExtendedModelMap());
        verify(tokens).create("credential", true, Set.of(), Set.of());

        var origin = sameOriginRequest();
        origin.removeHeader("Origin");
        origin.addHeader("Origin", "http://localhost/path");
        Assertions
                .assertThatThrownBy(() -> controller.create("credential", true, Set.of(), Set.of(), origin,
                        new MockHttpServletResponse(), new ExtendedModelMap()))
                .isInstanceOf(ResponseStatusException.class);
    }

    private MockHttpServletRequest sameOriginRequest() {
        var request = new MockHttpServletRequest();
        request.setServerName("localhost");
        request.setServerPort(80);
        request.addHeader("Origin", "http://localhost");
        return request;
    }
}
