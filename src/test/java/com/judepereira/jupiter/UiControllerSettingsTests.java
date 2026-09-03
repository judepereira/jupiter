package com.judepereira.jupiter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.judepereira.jupiter.agent.config.AgentProperties;
import com.judepereira.jupiter.agent.harness.CodingAgentHarness;
import com.judepereira.jupiter.git.GitAutoUpdateService;
import com.judepereira.jupiter.git.ManualGitPullCoordinator;
import com.judepereira.jupiter.agent.mcp.McpProjectMcpServerRuntimeManager;
import com.judepereira.jupiter.command.CommandStreamService;
import com.judepereira.jupiter.openai.oauth.OpenAiOAuthService;
import com.judepereira.jupiter.agent.llm.dto.ModelResponse;
import com.judepereira.jupiter.agent.llm.dto.ModelResponseMetadata;
import com.judepereira.jupiter.persistence.AppStateService;
import com.judepereira.jupiter.persistence.Persistence.LifecycleHookSettings;
import com.judepereira.jupiter.persistence.Persistence.McpServerHeader;
import com.judepereira.jupiter.persistence.Persistence.McpServerView;
import com.judepereira.jupiter.persistence.TestAppStateSupport;
import com.judepereira.jupiter.persistence.TokenUsageService;
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
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
    public void settingsModalIncludesHooksWithoutAnActiveProject(@TempDir Path workspaceRoot) {
        TestContext context = newContext(workspaceRoot);
        when(context.openAiOAuthService().currentView()).thenReturn(null);

        ConcurrentModel model = new ConcurrentModel();
        String view = context.controller().settingsModal(model);

        assertThat(view).isEqualTo("fragments/projects :: settingsModal");
        assertThat(model.getAttribute("activeProject")).isNull();
        assertThat(model.getAttribute("lifecycleHookSettings")).isEqualTo(new LifecycleHookSettings(null, null, null, 30));
    }

    @Test
    public void settingsModalIncludesAutoGitUpdatePreference(@TempDir Path workspaceRoot) {
        TestContext context = newContext(workspaceRoot);
        ConcurrentModel model = new ConcurrentModel();

        context.controller().settingsModal(model);

        assertThat(model.getAttribute("autoGitUpdateEnabled")).isEqualTo(true);
    }

    @Test
    public void applyAutoGitUpdateSettingsPersistsPreference(@TempDir Path workspaceRoot) {
        TestContext context = newContext(workspaceRoot);

        assertThat(context.controller().applyAutoGitUpdateSettings(false)).isEqualTo("fragments/projects :: modalClose");
        assertThat(context.appStateService().loadAutoGitUpdateEnabled()).isFalse();

        context.controller().applyAutoGitUpdateSettings(true);
        assertThat(context.appStateService().loadAutoGitUpdateEnabled()).isTrue();
    }

    @Test
    public void manualPullUsesActiveWorkspaceAndReportsResult(@TempDir Path workspaceRoot) {
        TestContext context = newContext(workspaceRoot);
        context.controller().addProject("Alpha", workspaceRoot.toString(), new ConcurrentModel());
        long workspaceId = context.appStateService().loadViewData().activeWorkspace().id();
        when(context.gitAutoUpdateService().updateWorkspaceManually(workspaceId)).thenReturn(
                new GitAutoUpdateService.UpdateResult(GitAutoUpdateService.UpdateResult.Status.UP_TO_DATE, "abc", "abc", null, false));

        ConcurrentModel model = new ConcurrentModel();
        assertThat(context.controller().pullActiveWorkspace(model)).isEqualTo("fragments/projects :: gitPullControl");
        assertThat(model.getAttribute("gitPullBusy")).isEqualTo(true);
        assertThat(model.getAttribute("workspaceId")).isEqualTo(workspaceId);
        assertThat(context.executor().size()).isEqualTo(1);

        ConcurrentModel duplicateModel = new ConcurrentModel();
        context.controller().pullActiveWorkspace(duplicateModel);
        assertThat(context.executor().size()).isEqualTo(1);
        context.executor().runNext();
        verify(context.gitAutoUpdateService()).updateWorkspaceManually(workspaceId);

        ConcurrentModel status = new ConcurrentModel();
        assertThat(context.controller().gitPullStatus(workspaceId, status)).isEqualTo("fragments/projects :: gitPullControl");
        assertThat(status.getAttribute("gitPullBusy")).isEqualTo(false);
    }

    @Test
    public void applyLifecycleHookSettingsPersistsWithoutAnActiveProject(@TempDir Path workspaceRoot) {
        TestContext context = newContext(workspaceRoot);

        String view = context.controller().applyLifecycleHookSettings("echo done", "echo error", "echo subagent", 45, new ConcurrentModel());

        assertThat(view).isEqualTo("fragments/projects :: modalClose");
        assertThat(context.appStateService().loadLifecycleHookSettings())
                .isEqualTo(new LifecycleHookSettings("echo done", "echo error", "echo subagent", 45));
    }

    @Test
    public void lifecycleHookValidationIsPropagated(@TempDir Path workspaceRoot) {
        TestContext context = newContext(workspaceRoot);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> context.controller().applyLifecycleHookSettings("", "", "", 3601, new ConcurrentModel()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 1 and 3600");
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
        verify(context.mcpRuntimeManager(), times(1)).reloadProject(projectId);
    }

    @Test
    public void usageEndpointReturnsActiveProjectHourlyUsageAndModelFallback(@TempDir Path workspaceRoot) {
        TestContext context = newContext(workspaceRoot);
        context.controller().addProject("Alpha", workspaceRoot.toString(), new ConcurrentModel());
        long sessionId = context.appStateService().loadViewData().activeSession().id();
        Instant hour = Instant.now().truncatedTo(ChronoUnit.HOURS);
        context.tokenUsageService().recordModelResponse(sessionId, "openai/gpt-5.5", "chat",
                new ModelResponse("ok", null, new ModelResponseMetadata(10, 5, 15, null, null, null, null, null, null, Map.of())));
        context.tokenUsageService().recordModelResponse(sessionId, "stale-model", "chat",
                new ModelResponse("ok", null, new ModelResponseMetadata(null, null, null, null, null, null, null, null, null, Map.of())));

        ConcurrentModel model = new ConcurrentModel();
        assertThat(context.controller().settingsUsage("7d", model)).isEqualTo("fragments/projects :: settingsUsage");
        assertThat((String) model.getAttribute("usageRange")).isEqualTo("7d");
        String json = (String) model.getAttribute("usageJson");
        assertThat(json).contains("GPT-5.5").contains("stale-model").contains("\"input\":10").contains("\"input\":null");
    }

    @Test
    public void usageEndpointNormalizesInvalidRange(@TempDir Path workspaceRoot) {
        TestContext context = newContext(workspaceRoot);
        context.controller().addProject("Alpha", workspaceRoot.toString(), new ConcurrentModel());

        ConcurrentModel model = new ConcurrentModel();
        context.controller().settingsUsage("invalid", model);

        assertThat(model.getAttribute("usageRange")).isEqualTo("24h");
    }

    @Test
    public void usageEndpointReturnsEmptyDataForActiveProjectWithoutUsage(@TempDir Path workspaceRoot) {
        TestContext context = newContext(workspaceRoot);
        context.controller().addProject("Alpha", workspaceRoot.toString(), new ConcurrentModel());

        ConcurrentModel model = new ConcurrentModel();
        assertThat(context.controller().settingsUsage("24h", model)).isEqualTo("fragments/projects :: settingsUsage");
        assertThat(model.getAttribute("usageJson")).isEqualTo("[]");
    }

    @Test
    public void usageEndpointReturnsEmptyFragmentWithoutActiveProject(@TempDir Path workspaceRoot) {
        TestContext context = newContext(workspaceRoot);
        ConcurrentModel model = new ConcurrentModel();

        assertThat(context.controller().settingsUsage("24h", model)).isEqualTo("fragments/projects :: usageEmpty");
    }

    @Test
    public void applyMcpSettingsPersistsCatalogAndReloadsAffectedProjects(@TempDir Path workspaceRoot) {
        TestContext context = newContext(workspaceRoot);
        context.controller().addProject("Alpha", workspaceRoot.toString(), new ConcurrentModel());
        long alphaId = context.appStateService().loadViewData().activeProject().id();
        context.controller().addProject("Beta", workspaceRoot.resolveSibling("beta").toString(), new ConcurrentModel());
        long betaId = context.appStateService().loadViewData().activeProject().id();
        context.appStateService().activateProject(alphaId);

        String payload = """
                {"servers":[
                  {"name":"Local MCP","url":"http://localhost:3000/mcp","enabled":true,
                   "headers":[{"name":"Authorization","value":"Bearer token"}],
                   "exposedProjectIds":[%d,%d]}
                ]}
                """.formatted(alphaId, betaId);

        ConcurrentModel model = new ConcurrentModel();
        String view = context.controller().applyMcpSettings(payload, model);

        assertThat(view).isEqualTo("fragments/projects :: modalClose");
        McpServerView saved = context.appStateService().listMcpServers().getFirst();
        assertThat(saved.name()).isEqualTo("Local MCP");
        assertThat(saved.url()).isEqualTo("http://localhost:3000/mcp");
        assertThat(saved.enabled()).isTrue();
        assertThat(saved.headers()).containsExactly(new McpServerHeader("Authorization", "Bearer token"));
        assertThat(saved.exposedProjectIds()).containsExactlyInAnyOrder(alphaId, betaId);
        verify(context.mcpRuntimeManager(), times(1)).reloadProject(alphaId);
        verify(context.mcpRuntimeManager(), times(1)).reloadProject(betaId);
    }

    private static TestContext newContext(Path workspaceRoot) {
        AgentProperties properties = new AgentProperties();
        properties.setWorkspaceRoot(workspaceRoot.toAbsolutePath().normalize().toString());
        TerminalManager terminalManager = mock(TerminalManager.class);
        OpenAiOAuthService openAiOAuthService = mock(OpenAiOAuthService.class);
        McpProjectMcpServerRuntimeManager mcpRuntimeManager = mock(McpProjectMcpServerRuntimeManager.class);
        GitAutoUpdateService gitAutoUpdateService = mock(GitAutoUpdateService.class);

        TestAppStateSupport.AppStateTestContext appStateContext = TestAppStateSupport.appStateContext(event -> {});
        AppStateService appStateService = appStateContext.service();
        TokenUsageService tokenUsageService = new TokenUsageService(appStateContext.repository(), new ObjectMapper());
        QueuedExecutor executor = new QueuedExecutor();
        ManualGitPullCoordinator coordinator = new ManualGitPullCoordinator(appStateService, gitAutoUpdateService,
                new SystemBalloonService(new ObjectMapper()), executor);

        return new TestContext(appStateService,
                tokenUsageService,
                openAiOAuthService,
                mcpRuntimeManager,
                gitAutoUpdateService,
                executor,
                new UiController(mock(CodingAgentHarness.class), properties, appStateService,
                        new com.judepereira.jupiter.agent.catalog.AgentDefinitionService(new ObjectMapper()),
                        ModelCatalogTestSupport.modelCatalogService(),
                        new SystemBalloonService(new ObjectMapper()),
                        new WorkspaceRailRefreshService(),
                        terminalManager,
                        new TerminalStateService(),
                        openAiOAuthService,
                        TestAppStateSupport.contextCompactionService(appStateService),
                        tokenUsageService,
                        mock(CommandStreamService.class),
                        mcpRuntimeManager,
                        gitAutoUpdateService, coordinator, "test"));
    }

    private record TestContext(AppStateService appStateService, TokenUsageService tokenUsageService, OpenAiOAuthService openAiOAuthService, McpProjectMcpServerRuntimeManager mcpRuntimeManager, GitAutoUpdateService gitAutoUpdateService, QueuedExecutor executor, UiController controller) {
    }

    private static final class QueuedExecutor extends AbstractExecutorService {
        private final Queue<Runnable> tasks = new ArrayDeque<>();
        private boolean shutdown;

        @Override public void execute(Runnable command) { if (shutdown) throw new IllegalStateException("shutdown"); tasks.add(command); }
        @Override public void shutdown() { shutdown = true; }
        @Override public List<Runnable> shutdownNow() { shutdown = true; List<Runnable> result = List.copyOf(tasks); tasks.clear(); return result; }
        @Override public boolean isShutdown() { return shutdown; }
        @Override public boolean isTerminated() { return shutdown && tasks.isEmpty(); }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return isTerminated(); }
        int size() { return tasks.size(); }
        void runNext() { tasks.remove().run(); }
    }
}
