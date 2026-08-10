package com.judepereira.jupiter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.judepereira.jupiter.agent.config.AgentProperties;
import com.judepereira.jupiter.agent.harness.CodingAgentHarness;
import com.judepereira.jupiter.openai.oauth.OpenAiOAuthService;
import com.judepereira.jupiter.persistence.AppStateService;
import com.judepereira.jupiter.persistence.TestAppStateSupport;
import com.judepereira.jupiter.testsupport.ModelCatalogTestSupport;
import com.judepereira.jupiter.terminal.TerminalManager;
import com.judepereira.jupiter.terminal.TerminalStateService;
import com.judepereira.jupiter.ui.UiController;
import com.judepereira.jupiter.ui.balloon.SystemBalloonService;
import com.judepereira.jupiter.ui.rail.WorkspaceRailRefreshService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ui.ConcurrentModel;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

public class UiControllerSettingsTests {

    @Test
    public void settingsModalIncludesActiveProjectAndWorkspaceInitCommands(@TempDir Path workspaceRoot) {
        TestContext context = newContext(workspaceRoot);
        context.controller().addProject("Alpha", workspaceRoot.toString(), new ConcurrentModel());

        OpenAiOAuthService.OpenAiOAuthView disconnected = new OpenAiOAuthService.OpenAiOAuthView(false, false, "OpenAI is not connected.", null, null, null, null);
        when(context.openAiOAuthService().currentView()).thenReturn(disconnected);

        long projectId = context.appStateService().loadViewData().activeProject().id();
        String commands = "echo init-one\npwd\ntouch init-ran.txt";
        context.appStateService().updateProjectWorkspaceInitCommands(projectId, commands);

        ConcurrentModel model = new ConcurrentModel();
        String view = context.controller().settingsModal(model);

        assertThat(view).isEqualTo("fragments/projects :: settingsModal");
        assertThat(model.getAttribute("activeProject")).isInstanceOf(UiController.Project.class);
        UiController.Project activeProject = (UiController.Project) model.getAttribute("activeProject");
        assertThat(activeProject.name()).isEqualTo("Alpha");
        assertThat(activeProject.path()).isEqualTo(workspaceRoot.toAbsolutePath().normalize().toString());
        assertThat(activeProject.workspaceInitCommands()).isEqualTo(commands);
        assertThat(model.getAttribute("openAiOAuthView")).isInstanceOf(OpenAiOAuthService.OpenAiOAuthView.class);
        assertThat(model.getAttribute("openAiOAuthView")).isEqualTo(disconnected);
    }

    @Test
    public void settingsModalDoesNotResetOpenAiOAuthState(@TempDir Path workspaceRoot) {
        TestContext context = newContext(workspaceRoot);
        context.controller().addProject("Alpha", workspaceRoot.toString(), new ConcurrentModel());

        OpenAiOAuthService.OpenAiOAuthView disconnected = new OpenAiOAuthService.OpenAiOAuthView(false, false, "OpenAI is not connected.", null, null, null, null);
        when(context.openAiOAuthService().currentView()).thenReturn(disconnected);

        ConcurrentModel model = new ConcurrentModel();
        String view = context.controller().settingsModal(model);

        assertThat(view).isEqualTo("fragments/projects :: settingsModal");
        verify(context.openAiOAuthService()).currentView();
        verify(context.openAiOAuthService(), never()).resetConnectionState();
        assertThat(model.getAttribute("openAiOAuthView")).isEqualTo(disconnected);
    }

    @Test
    public void logoutEndpointClearsOpenAiOAuthState(@TempDir Path workspaceRoot) {
        TestContext context = newContext(workspaceRoot);
        OpenAiOAuthService.OpenAiOAuthView disconnected = new OpenAiOAuthService.OpenAiOAuthView(false, false, "OpenAI is not connected.", null, null, null, null);
        when(context.openAiOAuthService().resetConnectionState()).thenReturn(disconnected);

        ConcurrentModel model = new ConcurrentModel();
        String view = context.controller().logoutOpenAiOAuth(model);

        assertThat(view).isEqualTo("fragments/projects :: openaiOAuthSection");
        verify(context.openAiOAuthService()).resetConnectionState();
        assertThat(model.getAttribute("openAiOAuthView")).isEqualTo(disconnected);
    }

    @Test
    public void applySettingsPersistsMultilineCommandsAndMultipleEnvironmentVariables(@TempDir Path workspaceRoot) {
        TestContext context = newContext(workspaceRoot);
        context.controller().addProject("Alpha", workspaceRoot.toString(), new ConcurrentModel());

        String commands = "echo init-one\npwd\ntouch init-ran.txt";
        ConcurrentModel model = new ConcurrentModel();
        String view = context.controller().applySettings(commands,
                List.of("API_URL", "FEATURE_FLAG", "API_URL", ""),
                List.of("https://example.test", "true", "https://override.test", "ignored"),
                model);

        assertThat(view).isEqualTo("fragments/projects :: modalClose");
        assertThat(context.appStateService().loadViewData().activeProject().workspaceInitCommands()).isEqualTo(commands);
        long projectId = context.appStateService().loadViewData().activeProject().id();
        assertThat(context.appStateService().loadProjectEnvironmentVariables(projectId))
                .containsEntry("FEATURE_FLAG", "true")
                .containsEntry("API_URL", "https://override.test")
                .doesNotContainKey("");
    }

    private static TestContext newContext(Path workspaceRoot) {
        AppStateService appStateService = TestAppStateSupport.appStateService();
        AgentProperties properties = new AgentProperties();
        properties.setWorkspaceRoot(workspaceRoot.toAbsolutePath().normalize().toString());
        TerminalManager terminalManager = mock(TerminalManager.class);
        OpenAiOAuthService openAiOAuthService = mock(OpenAiOAuthService.class);

        return new TestContext(appStateService,
                openAiOAuthService,
                new UiController(mock(CodingAgentHarness.class), properties, appStateService,
                        new com.judepereira.jupiter.agent.catalog.AgentDefinitionService(new ObjectMapper()),
                        ModelCatalogTestSupport.modelCatalogService(),
                        new SystemBalloonService(new ObjectMapper()),
                        new WorkspaceRailRefreshService(),
                        terminalManager,
                        new TerminalStateService(),
                        openAiOAuthService,
                        TestAppStateSupport.contextCompactionService(appStateService),
                        "test"));
    }

    private record TestContext(AppStateService appStateService, OpenAiOAuthService openAiOAuthService, UiController controller) {
    }
}
