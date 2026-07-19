package com.judepereira.jupiter2.ui.balloon;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.judepereira.jupiter2.agent.config.AgentProperties;
import com.judepereira.jupiter2.agent.harness.CodingAgentHarness;
import com.judepereira.jupiter2.persistence.TestAppStateSupport;
import com.judepereira.jupiter2.terminal.TerminalManager;
import com.judepereira.jupiter2.terminal.TerminalStateService;
import com.judepereira.jupiter2.ui.UiController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ui.ConcurrentModel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class UiControllerInvalidCheckoutBranchTests {

    @Test
    void invalidExistingBranchCheckoutKeepsStateAndPublishesGitErrorBalloon(@TempDir Path projectRoot) throws Exception {
        initGitRepo(projectRoot);

        SystemBalloonService balloonService = new SystemBalloonService(new ObjectMapper());
        UiController controller = new UiController(
                mock(CodingAgentHarness.class),
                agentProperties(projectRoot),
                TestAppStateSupport.appStateService(),
                mock(TerminalManager.class),
                new TerminalStateService(),
                balloonService,
                Runnable::run);

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
    private static java.util.List<UiController.Workspace> workspaces(ConcurrentModel model) {
        return (java.util.List<UiController.Workspace>) model.getAttribute("workspaces");
    }

    @SuppressWarnings("unchecked")
    private static java.util.List<UiController.Session> sessions(ConcurrentModel model) {
        return (java.util.List<UiController.Session>) model.getAttribute("sessions");
    }
}
