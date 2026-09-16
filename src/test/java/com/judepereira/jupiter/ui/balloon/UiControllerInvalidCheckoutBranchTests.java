package com.judepereira.jupiter.ui.balloon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.judepereira.jupiter.agent.catalog.AgentDefinitionService;
import com.judepereira.jupiter.agent.config.AgentProperties;
import com.judepereira.jupiter.agent.config.OpenAiOAuthProperties;
import com.judepereira.jupiter.agent.harness.CodingAgentHarness;
import com.judepereira.jupiter.anthropic.oauth.AnthropicOAuthService;
import com.judepereira.jupiter.command.CommandCatalogService;
import com.judepereira.jupiter.command.CommandStreamService;
import com.judepereira.jupiter.config.HttpAuthProperties;
import com.judepereira.jupiter.git.GitAutoUpdateService;
import com.judepereira.jupiter.git.ManualGitPullCoordinator;
import com.judepereira.jupiter.openai.oauth.OpenAiOAuthService;
import com.judepereira.jupiter.persistence.AppStateRepository;
import com.judepereira.jupiter.persistence.AppStateService;
import com.judepereira.jupiter.persistence.TestAppStateSupport;
import com.judepereira.jupiter.terminal.TerminalManager;
import com.judepereira.jupiter.terminal.TerminalStateService;
import com.judepereira.jupiter.testsupport.ModelCatalogTestSupport;
import com.judepereira.jupiter.ui.ChatPresentationService;
import com.judepereira.jupiter.ui.UiController;
import com.judepereira.jupiter.ui.rail.WorkspaceRailRefreshService;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.ui.ConcurrentModel;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class UiControllerInvalidCheckoutBranchTests {

    @Test
    void invalidNewBranchNameKeepsStateAndPublishesValidationBalloon(@TempDir Path projectRoot) throws Exception {
        initGitRepo(projectRoot);

        SystemBalloonService balloonService = new SystemBalloonService(new ObjectMapper(), () -> new SseEmitter(0L));
        var appStateService = TestAppStateSupport.appStateService();
        UiController controller = controller(projectRoot, appStateService, balloonService);

        ConcurrentModel addProject = new ConcurrentModel();
        controller.addProject("Alpha", projectRoot.toString(), addProject);

        var project = activeProject(addProject);
        var workspace = activeWorkspace(addProject);
        var session = activeSession(addProject);

        ConcurrentModel model = new ConcurrentModel();
        String view = controller.addWorkspace(" feature unsafe ", "create", model);

        assertThat(view).isEqualTo("fragments/projects :: workspaceModal");
        assertThat(model.getAttribute("branchName")).isEqualTo("feature unsafe");
        assertThat(model.getAttribute("branchMode")).isEqualTo("create");
        assertThat(model.getAttribute("createBranch")).isEqualTo(true);
        assertThat(model.getAttribute("modalOob")).isEqualTo(true);
        assertThat(activeProject(model).id()).isEqualTo(project.id());
        assertThat(activeWorkspace(model).id()).isEqualTo(workspace.id());
        assertThat(activeSession(model).id()).isEqualTo(session.id());
        assertThat(workspaces(model)).containsExactlyElementsOf(workspaces(addProject));
        assertThat(sessions(model)).containsExactlyElementsOf(sessions(addProject));
        assertThat(balloonService.publishedBalloons()).hasSize(1);
        SystemBalloon balloon = balloonService.publishedBalloons().get(0);
        assertThat(balloon.type()).isEqualTo(SystemBalloon.Type.ERROR);
        assertThat(balloon.title()).isEqualTo("Invalid Branch Name");
        assertThat(balloon.body()).contains("Invalid Git branch name");
        assertThat(balloon.body()).contains("feature unsafe");
    }

    @Test
    void invalidBranchModeThrowsIllegalArgumentException(@TempDir Path projectRoot) throws Exception {
        initGitRepo(projectRoot);

        SystemBalloonService balloonService = new SystemBalloonService(new ObjectMapper(), () -> new SseEmitter(0L));
        var appStateService = TestAppStateSupport.appStateService();
        UiController controller = controller(projectRoot, appStateService, balloonService);

        ConcurrentModel addProject = new ConcurrentModel();
        controller.addProject("Alpha", projectRoot.toString(), addProject);

        assertThatThrownBy(() -> controller.addWorkspace("feature-safe", "toggle", new ConcurrentModel()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Invalid branch mode: toggle");
    }

    @Test
    void invalidExistingBranchCheckoutKeepsStateAndPublishesGitErrorBalloon(@TempDir Path projectRoot)
            throws Exception {
        initGitRepo(projectRoot);

        SystemBalloonService balloonService = new SystemBalloonService(new ObjectMapper(), () -> new SseEmitter(0L));
        var appStateService = TestAppStateSupport.appStateService();
        UiController controller = controller(projectRoot, appStateService, balloonService);

        ConcurrentModel addProject = new ConcurrentModel();
        controller.addProject("Alpha", projectRoot.toString(), addProject);

        var project = activeProject(addProject);
        var workspace = activeWorkspace(addProject);
        var session = activeSession(addProject);

        ConcurrentModel model = new ConcurrentModel();
        String view = controller.addWorkspace("missing-branch", "checkout", model);

        assertThat(view).isEqualTo("fragments/projects :: workspaceModal");
        assertThat(model.getAttribute("branchName")).isEqualTo("missing-branch");
        assertThat(model.getAttribute("branchMode")).isEqualTo("checkout");
        assertThat(model.getAttribute("createBranch")).isEqualTo(false);
        assertThat(model.getAttribute("modalOob")).isEqualTo(true);
        assertThat(activeProject(model).id()).isEqualTo(project.id());
        assertThat(activeWorkspace(model).id()).isEqualTo(workspace.id());
        assertThat(activeSession(model).id()).isEqualTo(session.id());
        assertThat(workspaces(model)).containsExactlyElementsOf(workspaces(addProject));
        assertThat(sessions(model)).containsExactlyElementsOf(sessions(addProject));
        assertThat(balloonService.publishedBalloons()).hasSize(1);
        SystemBalloon balloon = balloonService.publishedBalloons().get(0);
        assertThat(balloon.type()).isEqualTo(SystemBalloon.Type.ERROR);
        assertThat(balloon.title()).isEqualTo("Checkout Failed");
        assertThat(balloon.body()).contains("Could not check out existing Git branch");
        assertThat(balloon.body()).contains("missing-branch");
        assertThat(balloon.body()).contains("fatal: invalid reference: missing-branch");
    }

    private static UiController controller(Path projectRoot, AppStateService appStateService,
            SystemBalloonService balloonService) {
        return new UiController(mock(CodingAgentHarness.class), agentProperties(projectRoot), appStateService,
                new AgentDefinitionService(new ObjectMapper()), ModelCatalogTestSupport.modelCatalogService(),
                ModelCatalogTestSupport.resolutionService(ModelCatalogTestSupport.modelCatalogService()), null, null,
                null, Mockito.mock(AnthropicOAuthService.class), balloonService,
                new WorkspaceRailRefreshService(() -> new SseEmitter(0L),
                        (emitter, eventName, data) -> emitter.send(SseEmitter.event().name(eventName).data(data))),
                appStateService.activeStreamRegistryService(), mock(TerminalManager.class), new TerminalStateService(),
                new OpenAiOAuthService(new OpenAiOAuthProperties(), new ObjectMapper(), HttpClient.newHttpClient(),
                        mock(AppStateRepository.class), null),
                TestAppStateSupport.contextCompactionService(appStateService), null, mock(CommandStreamService.class),
                new CommandCatalogService("", System.getProperty("user.home")), null, new ChatPresentationService(),
                null, null, new HttpAuthProperties(), mock(GitAutoUpdateService.class),
                mock(ManualGitPullCoordinator.class), "0.0.1-SNAPSHOT", System.getProperty("user.home"));
    }

    private static AgentProperties agentProperties(Path workspaceRoot) {
        AgentProperties props = new AgentProperties();
        props.setWorkspaceRoot(workspaceRoot.toString());
        return props;
    }

    private static void initGitRepo(Path projectRoot) throws Exception {
        Files.writeString(projectRoot.resolve("README.md"), "hello\n");
        runGit(projectRoot, "git", "init");
        runGit(projectRoot, "git", "add", "README.md");
        runGit(projectRoot, "git", "-c", "user.name=Jude", "-c", "user.email=jude@example.com", "commit", "-m", "init");
    }

    private static void runGit(Path workingDirectory, String... command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).directory(workingDirectory.toFile()).redirectErrorStream(true)
                .start();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            String output = new String(process.getInputStream().readAllBytes());
            throw new IllegalStateException("git command failed: " + String.join(" ", command) + "\n" + output);
        }
    }

    private static UiController.Project activeProject(ConcurrentModel model) {
        return (UiController.Project) model.getAttribute("activeProject");
    }

    private static UiController.Workspace activeWorkspace(ConcurrentModel model) {
        return (UiController.Workspace) model.getAttribute("activeWorkspace");
    }

    private static UiController.Session activeSession(ConcurrentModel model) {
        return (UiController.Session) model.getAttribute("activeSession");
    }

    @SuppressWarnings("unchecked")
    private static List<UiController.Workspace> workspaces(ConcurrentModel model) {
        return (List<UiController.Workspace>) model.getAttribute("workspaces");
    }

    @SuppressWarnings("unchecked")
    private static List<UiController.Session> sessions(ConcurrentModel model) {
        return (List<UiController.Session>) model.getAttribute("sessions");
    }
}
