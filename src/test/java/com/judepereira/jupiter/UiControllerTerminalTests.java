package com.judepereira.jupiter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.judepereira.jupiter.agent.catalog.ThinkingLevel;
import com.judepereira.jupiter.agent.config.AgentProperties;
import com.judepereira.jupiter.agent.harness.CodingAgentHarness;
import com.judepereira.jupiter.command.CommandStreamService;
import com.judepereira.jupiter.openai.oauth.OpenAiOAuthService;
import com.judepereira.jupiter.persistence.Persistence.ProjectEnvironmentVariable;
import com.judepereira.jupiter.persistence.TestAppStateSupport;
import com.judepereira.jupiter.terminal.TerminalHandle;
import com.judepereira.jupiter.terminal.TerminalManager;
import com.judepereira.jupiter.terminal.TerminalStateService;
import com.judepereira.jupiter.terminal.TerminalTab;
import com.judepereira.jupiter.testsupport.ModelCatalogTestSupport;
import com.judepereira.jupiter.ui.UiController;
import com.judepereira.jupiter.ui.balloon.SystemBalloonService;
import com.judepereira.jupiter.ui.rail.WorkspaceRailRefreshService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ui.ConcurrentModel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class UiControllerTerminalTests {

    @Test
    public void openingTerminalPanelCreatesInitialTerminalTab(@TempDir Path workspaceRoot) {
        TestContext context = newContext(workspaceRoot);
        UiController controller = context.controller();

        controller.addProject("Alpha", workspaceRoot.toString(), new ConcurrentModel());
        long projectId = context.appStateService().loadViewData().activeProject().id();
        context.appStateService().updateProjectEnvironmentVariables(projectId, List.of(new ProjectEnvironmentVariable("API_URL", "https://example.test")));

        ConcurrentModel model = new ConcurrentModel();
        String view = controller.openTerminalPanel(model);

        assertThat(view).isEqualTo("fragments/terminal :: panel");
        assertThat(terminalTabs(model)).extracting(TerminalTab::title, TerminalTab::active)
                .containsExactly(org.assertj.core.api.Assertions.tuple("Terminal 1", true));
        assertThat(activeTerminal(model).id()).isEqualTo("terminal-1");
        assertThat(bottomPanelMode(model)).isEqualTo("terminal");
        assertThat(bottomPanelOpen(model)).isTrue();
        verify(context.terminalManager()).createTerminal(eq(workspaceRoot.toAbsolutePath().normalize().toString()), eq(Map.of("API_URL", "https://example.test")));
    }

    @Test
    public void openingTerminalPanelTogglesClosedAndReopensExistingTabs(@TempDir Path workspaceRoot) {
        TestContext context = newContext(workspaceRoot);
        UiController controller = context.controller();

        controller.addProject("Alpha", workspaceRoot.toString(), new ConcurrentModel());
        long projectId = context.appStateService().loadViewData().activeProject().id();
        context.appStateService().updateProjectEnvironmentVariables(projectId, List.of(new ProjectEnvironmentVariable("API_URL", "https://example.test")));

        controller.openTerminalPanel(new ConcurrentModel());

        ConcurrentModel closedModel = new ConcurrentModel();
        controller.openTerminalPanel(closedModel);

        assertThat(terminalTabs(closedModel)).extracting(TerminalTab::title, TerminalTab::active)
                .containsExactly(org.assertj.core.api.Assertions.tuple("Terminal 1", true));
        assertThat(activeTerminal(closedModel).id()).isEqualTo("terminal-1");
        assertThat(bottomPanelMode(closedModel)).isEqualTo("none");
        assertThat(bottomPanelOpen(closedModel)).isFalse();

        ConcurrentModel reopenedModel = new ConcurrentModel();
        controller.openTerminalPanel(reopenedModel);

        assertThat(terminalTabs(reopenedModel)).extracting(TerminalTab::title, TerminalTab::active)
                .containsExactly(org.assertj.core.api.Assertions.tuple("Terminal 1", true));
        assertThat(activeTerminal(reopenedModel).id()).isEqualTo("terminal-1");
        assertThat(bottomPanelMode(reopenedModel)).isEqualTo("terminal");
        assertThat(bottomPanelOpen(reopenedModel)).isTrue();
        verify(context.terminalManager()).createTerminal(eq(workspaceRoot.toAbsolutePath().normalize().toString()), eq(Map.of("API_URL", "https://example.test")));
    }

    @Test
    public void terminalTabsStayScopedToTheirWorkspace(@TempDir Path projectRoot) throws Exception {
        initGitRepo(projectRoot);
        String baseBranchName = "feature-b-" + projectRoot.getFileName();

        TestContext context = newContext(projectRoot);
        UiController controller = context.controller();

        controller.addProject("Alpha", projectRoot.toString(), new ConcurrentModel());
        long projectId = context.appStateService().loadViewData().activeProject().id();
        context.appStateService().updateProjectEnvironmentVariables(projectId, List.of(new ProjectEnvironmentVariable("API_URL", "https://example.test")));
        long workspaceAId = context.appStateService().loadViewData().activeWorkspace().id();

        ConcurrentModel openModel = new ConcurrentModel();
        controller.openTerminalPanel(openModel);
        assertThat(terminalTabs(openModel)).extracting(TerminalTab::title, TerminalTab::active)
                .containsExactly(org.assertj.core.api.Assertions.tuple("Terminal 1", true));

        String uniqueBranchName = baseBranchName + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        ConcurrentModel workspaceBModel = new ConcurrentModel();
        controller.addWorkspace(uniqueBranchName, "create", workspaceBModel);

        assertThat(workspaceBModel.getAttribute("terminalOob")).isEqualTo(true);
        assertThat(terminalTabs(workspaceBModel)).isEmpty();
        assertThat(activeTerminal(workspaceBModel)).isNull();
        assertThat(bottomPanelMode(workspaceBModel)).isEqualTo("none");
        assertThat(bottomPanelOpen(workspaceBModel)).isFalse();

        ConcurrentModel switchedBackModel = new ConcurrentModel();
        controller.activateWorkspace(workspaceAId, switchedBackModel);

        assertThat(switchedBackModel.getAttribute("terminalOob")).isEqualTo(true);
        assertThat(terminalTabs(switchedBackModel)).extracting(TerminalTab::title, TerminalTab::active)
                .containsExactly(org.assertj.core.api.Assertions.tuple("Terminal 1", true));
        assertThat(activeTerminal(switchedBackModel).id()).isEqualTo("terminal-1");
        assertThat(bottomPanelMode(switchedBackModel)).isEqualTo("terminal");
        assertThat(bottomPanelOpen(switchedBackModel)).isTrue();
    }

    @Test
    public void switchingSessionsWithinTheSameWorkspaceKeepsTerminalTabs(@TempDir Path projectRoot) throws Exception {
        initGitRepo(projectRoot);

        TestContext context = newContext(projectRoot);
        UiController controller = context.controller();

        controller.addProject("Alpha", projectRoot.toString(), new ConcurrentModel());
        controller.openTerminalPanel(new ConcurrentModel());
        long sessionOneId = context.appStateService().loadViewData().activeSession().id();

        ConcurrentModel sessionTwoModel = new ConcurrentModel();
        controller.addSession("Feature work", sessionTwoModel);

        assertThat(sessionTwoModel.getAttribute("terminalOob")).isEqualTo(true);
        assertThat(terminalTabs(sessionTwoModel)).extracting(TerminalTab::title, TerminalTab::active)
                .containsExactly(org.assertj.core.api.Assertions.tuple("Terminal 1", true));
        assertThat(activeTerminal(sessionTwoModel).id()).isEqualTo("terminal-1");

        ConcurrentModel backToSessionOneModel = new ConcurrentModel();
        controller.activateSession(sessionOneId, backToSessionOneModel);

        assertThat(backToSessionOneModel.getAttribute("terminalOob")).isEqualTo(true);
        assertThat(terminalTabs(backToSessionOneModel)).extracting(TerminalTab::title, TerminalTab::active)
                .containsExactly(org.assertj.core.api.Assertions.tuple("Terminal 1", true));
        assertThat(activeTerminal(backToSessionOneModel).id()).isEqualTo("terminal-1");
        assertThat(backToSessionOneModel.getAttribute("includeChatContainer")).isEqualTo(true);
        assertThat((List<?>) backToSessionOneModel.getAttribute("agents")).isNotEmpty();
        assertThat((List<?>) backToSessionOneModel.getAttribute("models")).isNotEmpty();
        assertThat(backToSessionOneModel.getAttribute("thinkingLevels")).isEqualTo(List.of(ThinkingLevel.values()));
        assertThat(backToSessionOneModel.getAttribute("selectedAgent")).isEqualTo(backToSessionOneModel.getAttribute("defaultAgent"));
        assertThat(backToSessionOneModel.getAttribute("selectedModel")).isEqualTo(backToSessionOneModel.getAttribute("defaultModel"));
        assertThat(backToSessionOneModel.getAttribute("selectedThinking")).isEqualTo(backToSessionOneModel.getAttribute("defaultThinking"));
    }

    @Test
    public void creatingNewTerminalAddsTabAndActivatesNewest(@TempDir Path workspaceRoot) {
        TestContext context = newContext(workspaceRoot);
        UiController controller = context.controller();

        controller.addProject("Alpha", workspaceRoot.toString(), new ConcurrentModel());
        long projectId = context.appStateService().loadViewData().activeProject().id();
        context.appStateService().updateProjectEnvironmentVariables(projectId, List.of(new ProjectEnvironmentVariable("API_URL", "https://example.test")));
        controller.openTerminalPanel(new ConcurrentModel());

        ConcurrentModel model = new ConcurrentModel();
        String view = controller.newTerminal(model);

        assertThat(view).isEqualTo("fragments/terminal :: panel");
        assertThat(terminalTabs(model)).extracting(TerminalTab::title, TerminalTab::active)
                .containsExactly(
                        org.assertj.core.api.Assertions.tuple("Terminal 1", false),
                        org.assertj.core.api.Assertions.tuple("Terminal 2", true));
        assertThat(activeTerminal(model).id()).isEqualTo("terminal-2");
        assertThat(bottomPanelMode(model)).isEqualTo("terminal");
        assertThat(bottomPanelOpen(model)).isTrue();
        verify(context.terminalManager(), times(2)).createTerminal(eq(workspaceRoot.toAbsolutePath().normalize().toString()), eq(Map.of("API_URL", "https://example.test")));
    }

    @Test
    public void creatingWorkspaceRunsWorkspaceInitCommandsInWorkspaceInitTerminal(@TempDir Path projectRoot) throws Exception {
        initGitRepo(projectRoot);

        TestContext context = newContext(projectRoot);
        UiController controller = context.controller();

        controller.addProject("Alpha", projectRoot.toString(), new ConcurrentModel());
        long projectId = context.appStateService().loadViewData().activeProject().id();
        context.appStateService().updateProjectEnvironmentVariables(projectId, List.of(
                new ProjectEnvironmentVariable("API_URL", "https://example.test"),
                new ProjectEnvironmentVariable("PROJECT_ENV", "alpha")));
        String commands = "echo init-one\npwd\ntouch init-ran.txt";
        context.appStateService().updateProjectWorkspaceInitCommands(projectId, commands);

        String branchName = "feature-init-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        ConcurrentModel model = new ConcurrentModel();
        String view = controller.addWorkspace(branchName, "create", model);

        Path worktreePath = projectRoot.toAbsolutePath().normalize().resolveSibling(".trees")
                .resolve(projectRoot.getFileName().toString())
                .resolve(branchName)
                .toAbsolutePath()
                .normalize();
        assertThat(view).isEqualTo("fragments/projects :: shellUpdates");
        assertThat(terminalTabs(model)).extracting(TerminalTab::title, TerminalTab::active)
                .containsExactly(org.assertj.core.api.Assertions.tuple("Workspace Init", true));
        assertThat(bottomPanelMode(model)).isEqualTo("terminal");
        assertThat(bottomPanelOpen(model)).isTrue();
        assertThat(context.terminalStateService().snapshot(context.appStateService().loadViewData().activeWorkspace().id()).bottomPanelOpen()).isTrue();
        verify(context.terminalManager()).createTerminal(eq(worktreePath.toString()), eq("Workspace Init"), eq(Map.of(
                "API_URL", "https://example.test",
                "PROJECT_ENV", "alpha")));
        verify(context.terminalManager()).write(anyString(), eq(commands + "\n"));
    }

    @Test
    public void creatingWorkspaceWithBlankWorkspaceInitCommandsDoesNotAutoCreateATerminal(@TempDir Path projectRoot) throws Exception {
        initGitRepo(projectRoot);

        TestContext context = newContext(projectRoot);
        UiController controller = context.controller();

        controller.addProject("Alpha", projectRoot.toString(), new ConcurrentModel());
        long projectId = context.appStateService().loadViewData().activeProject().id();
        context.appStateService().updateProjectWorkspaceInitCommands(projectId, "   ");

        String branchName = "feature-blank-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        ConcurrentModel model = new ConcurrentModel();
        controller.addWorkspace(branchName, "create", model);

        assertThat(terminalTabs(model)).isEmpty();
        assertThat(activeTerminal(model)).isNull();
        assertThat(bottomPanelMode(model)).isEqualTo("none");
        assertThat(bottomPanelOpen(model)).isFalse();
        verify(context.terminalManager(), never()).createTerminal(anyString(), anyString(), anyMap());
    }

    @Test
    public void creatingWorkspaceWithNullWorkspaceInitCommandsDoesNotAutoCreateATerminal(@TempDir Path projectRoot) throws Exception {
        initGitRepo(projectRoot);

        TestContext context = newContext(projectRoot);
        UiController controller = context.controller();

        controller.addProject("Alpha", projectRoot.toString(), new ConcurrentModel());
        long projectId = context.appStateService().loadViewData().activeProject().id();
        context.appStateService().updateProjectWorkspaceInitCommands(projectId, null);

        String branchName = "feature-null-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        ConcurrentModel model = new ConcurrentModel();
        controller.addWorkspace(branchName, "create", model);

        assertThat(terminalTabs(model)).isEmpty();
        assertThat(activeTerminal(model)).isNull();
        assertThat(bottomPanelMode(model)).isEqualTo("none");
        assertThat(bottomPanelOpen(model)).isFalse();
        verify(context.terminalManager(), never()).createTerminal(anyString(), anyString(), anyMap());
    }

    @Test
    public void activatingTerminalSwitchesActiveId(@TempDir Path workspaceRoot) {
        TestContext context = newContext(workspaceRoot);
        UiController controller = context.controller();

        controller.addProject("Alpha", workspaceRoot.toString(), new ConcurrentModel());
        long projectId = context.appStateService().loadViewData().activeProject().id();
        context.appStateService().updateProjectEnvironmentVariables(projectId, List.of(new ProjectEnvironmentVariable("API_URL", "https://example.test")));
        controller.openTerminalPanel(new ConcurrentModel());
        controller.newTerminal(new ConcurrentModel());

        ConcurrentModel model = new ConcurrentModel();
        controller.activateTerminal("terminal-1", model);

        assertThat(activeTerminal(model).id()).isEqualTo("terminal-1");
        assertThat(terminalTabs(model)).extracting(TerminalTab::id, TerminalTab::active)
                .containsExactly(
                        org.assertj.core.api.Assertions.tuple("terminal-1", true),
                        org.assertj.core.api.Assertions.tuple("terminal-2", false));
        assertThat(bottomPanelMode(model)).isEqualTo("terminal");
        assertThat(bottomPanelOpen(model)).isTrue();
    }

    @Test
    public void closingNonLastTerminalLeavesPaneOpenWithRemainingActiveTab(@TempDir Path workspaceRoot) {
        TestContext context = newContext(workspaceRoot);
        UiController controller = context.controller();

        controller.addProject("Alpha", workspaceRoot.toString(), new ConcurrentModel());
        controller.openTerminalPanel(new ConcurrentModel());
        controller.newTerminal(new ConcurrentModel());

        ConcurrentModel model = new ConcurrentModel();
        String view = controller.closeTerminal("terminal-2", model);

        assertThat(view).isEqualTo("fragments/terminal :: panel");
        assertThat(terminalTabs(model)).extracting(TerminalTab::id, TerminalTab::active)
                .containsExactly(org.assertj.core.api.Assertions.tuple("terminal-1", true));
        assertThat(activeTerminal(model).id()).isEqualTo("terminal-1");
        assertThat(bottomPanelMode(model)).isEqualTo("terminal");
        assertThat(bottomPanelOpen(model)).isTrue();
        verify(context.terminalManager()).closeTerminal("terminal-2");
    }

    @Test
    public void closingLastTerminalClosesTerminalPane(@TempDir Path workspaceRoot) {
        TestContext context = newContext(workspaceRoot);
        UiController controller = context.controller();

        controller.addProject("Alpha", workspaceRoot.toString(), new ConcurrentModel());
        controller.openTerminalPanel(new ConcurrentModel());

        ConcurrentModel model = new ConcurrentModel();
        String view = controller.closeTerminal("terminal-1", model);

        assertThat(view).isEqualTo("fragments/terminal :: panel");
        assertThat(terminalTabs(model)).isEmpty();
        assertThat(activeTerminal(model)).isNull();
        assertThat(bottomPanelMode(model)).isEqualTo("none");
        assertThat(bottomPanelOpen(model)).isFalse();
        verify(context.terminalManager()).closeTerminal("terminal-1");
    }

    private static TestContext newContext(Path workspaceRoot) {
        TerminalManager terminalManager = mock(TerminalManager.class);
        AtomicInteger sequence = new AtomicInteger();
        when(terminalManager.createTerminal(anyString(), anyMap())).thenAnswer(invocation -> {
            int n = sequence.incrementAndGet();
            return new TerminalHandle("terminal-" + n, "Terminal " + n);
        });
        when(terminalManager.createTerminal(anyString(), anyString(), anyMap())).thenAnswer(invocation -> {
            int n = sequence.incrementAndGet();
            return new TerminalHandle("terminal-" + n, invocation.getArgument(1));
        });

        AgentProperties properties = new AgentProperties();
        properties.setWorkspaceRoot(workspaceRoot.toAbsolutePath().normalize().toString());

        return new TestContext(
                TestAppStateSupport.appStateService(),
                new TerminalStateService(),
                terminalManager,
                properties);
    }

    private static void initGitRepo(Path projectPath) throws IOException, InterruptedException {
        Files.createDirectories(projectPath);
        runGit(projectPath, "git", "init");
        runGit(projectPath, "git", "config", "user.name", "Jupiter Tests");
        runGit(projectPath, "git", "config", "user.email", "tests@example.com");
        Files.writeString(projectPath.resolve("README.md"), "hello\n");
        runGit(projectPath, "git", "add", "README.md");
        runGit(projectPath, "git", "commit", "-m", "init");
    }

    private static void runGit(Path workingDirectory, String... command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command)
                .directory(workingDirectory.toFile())
                .redirectErrorStream(true)
                .start();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            String output = new String(process.getInputStream().readAllBytes());
            throw new IllegalStateException("git command failed: " + String.join(" ", command) + "\n" + output);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<TerminalTab> terminalTabs(ConcurrentModel model) {
        return (List<TerminalTab>) model.getAttribute("terminalTabs");
    }

    private static TerminalTab activeTerminal(ConcurrentModel model) {
        return (TerminalTab) model.getAttribute("activeTerminal");
    }

    private static String bottomPanelMode(ConcurrentModel model) {
        return (String) model.getAttribute("bottomPanelMode");
    }

    private static boolean bottomPanelOpen(ConcurrentModel model) {
        return Boolean.TRUE.equals(model.getAttribute("bottomPanelOpen"));
    }

    private record TestContext(
            com.judepereira.jupiter.persistence.AppStateService appStateService,
            TerminalStateService terminalStateService,
            TerminalManager terminalManager,
            AgentProperties properties) {

            private UiController controller() {
            return new UiController(mock(CodingAgentHarness.class), properties, appStateService,
                    new com.judepereira.jupiter.agent.catalog.AgentDefinitionService(new ObjectMapper()),
                    ModelCatalogTestSupport.modelCatalogService(),
                    new SystemBalloonService(new ObjectMapper()),
                    new WorkspaceRailRefreshService(),
                    terminalManager,
                    terminalStateService,
                    new OpenAiOAuthService(new com.judepereira.jupiter.agent.config.OpenAiOAuthProperties(), new ObjectMapper(), java.net.http.HttpClient.newHttpClient()),
                    TestAppStateSupport.contextCompactionService(appStateService),
                    mock(CommandStreamService.class),
                    "0.0.1-SNAPSHOT");
            }
        }
}
