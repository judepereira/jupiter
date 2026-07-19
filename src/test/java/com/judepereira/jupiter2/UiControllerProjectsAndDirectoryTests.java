package com.judepereira.jupiter2;

import com.judepereira.jupiter2.agent.harness.AgentTurnRequest;
import com.judepereira.jupiter2.agent.harness.AgentTurnResult;
import com.judepereira.jupiter2.agent.harness.CodingAgentHarness;
import com.judepereira.jupiter2.agent.llm.AgentStreamListener;
import com.judepereira.jupiter2.persistence.AppStateService;
import com.judepereira.jupiter2.ui.UiController;
import com.judepereira.jupiter2.persistence.TestAppStateSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ui.ConcurrentModel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

public class UiControllerProjectsAndDirectoryTests {

    @Test
    public void addProjectCreatesActiveProjectWorkspaceAndDefaultSession(@TempDir Path projectPath) {
        UiController controller = newController();

        ConcurrentModel model = new ConcurrentModel();
        String view = controller.addProject("Alpha", projectPath.toString(), model);

        assertThat(view).isEqualTo("fragments/projects :: shellUpdates");
        assertThat(projects(model)).extracting(UiController.Project::name, UiController.Project::path)
                .containsExactly(tuple("Alpha", projectPath.toAbsolutePath().normalize().toString()));
        assertThat(activeProject(model)).isNotNull();
        assertThat(activeProject(model).name()).isEqualTo("Alpha");
        assertThat(workspaces(model)).extracting(UiController.Workspace::name, UiController.Workspace::path)
                .containsExactly(tuple("Default Workspace", projectPath.toAbsolutePath().normalize().toString()));
        assertThat(activeWorkspace(model)).isNotNull();
        assertThat(activeWorkspace(model).path()).isEqualTo(projectPath.toAbsolutePath().normalize().toString());
        assertThat(sessions(model)).extracting(UiController.Session::name)
                .containsExactly("Session #1");
        assertThat(activeSession(model)).isNotNull();
        assertThat(activeSession(model).name()).isEqualTo("Session #1");
        assertThat(model.getAttribute("terminalOob")).isEqualTo(true);
        assertThat((List<?>) model.getAttribute("terminalTabs")).isEmpty();
        assertThat(model.getAttribute("bottomPanelOpen")).isEqualTo(false);
        assertThat(model.getAttribute("workspaceRoot")).isEqualTo(projectPath.toAbsolutePath().normalize().toString());
    }

    @Test
    public void addSessionMakesMultipleSessionsVisibleAndActivatesTheNewOne(@TempDir Path projectPath) {
        UiController controller = newController();

        ConcurrentModel addProject = new ConcurrentModel();
        controller.addProject("Alpha", projectPath.toString(), addProject);
        long sessionOneId = activeSession(addProject).id();

        ConcurrentModel addSession = new ConcurrentModel();
        String view = controller.addSession("Feature work", addSession);

        assertThat(view).isEqualTo("fragments/projects :: shellUpdates");
        assertThat(sessions(addSession)).extracting(UiController.Session::name)
                .containsExactly("Session #1", "Feature work");
        assertThat(activeSession(addSession).name()).isEqualTo("Feature work");
        assertThat(activeSession(addSession).id()).isNotEqualTo(sessionOneId);
        assertThat(activeWorkspace(addSession).path()).isEqualTo(projectPath.toAbsolutePath().normalize().toString());
        assertThat(addSession.getAttribute("terminalOob")).isEqualTo(true);
        assertThat((List<?>) addSession.getAttribute("terminalTabs")).isEmpty();
    }

    @Test
    public void collapseWorkspaceClearsTheActiveWorkspaceAndSessionWhileKeepingTheProjectSelected(@TempDir Path projectPath) {
        UiController controller = newController();

        ConcurrentModel addProject = new ConcurrentModel();
        controller.addProject("Alpha", projectPath.toString(), addProject);

        ConcurrentModel collapse = new ConcurrentModel();
        String view = controller.collapseWorkspace(activeWorkspace(addProject).id(), collapse);

        assertThat(view).isEqualTo("fragments/projects :: shellUpdates");
        assertThat(activeProject(collapse)).isNotNull();
        assertThat(activeProject(collapse).name()).isEqualTo("Alpha");
        assertThat(workspaces(collapse)).extracting(UiController.Workspace::name)
                .containsExactly("Default Workspace");
        assertThat(activeWorkspace(collapse)).isNull();
        assertThat(sessions(collapse)).isEmpty();
        assertThat(activeSession(collapse)).isNull();
    }

    @Test
    public void indexExposesProjectTabsAndActiveWorkspaceSessionData(@TempDir Path firstProject,
                                                                     @TempDir Path secondProject) {
        UiController controller = newController();

        ConcurrentModel addOne = new ConcurrentModel();
        controller.addProject("First", firstProject.toString(), addOne);
        long firstProjectId = activeProject(addOne).id();

        ConcurrentModel addTwo = new ConcurrentModel();
        controller.addProject("Second", secondProject.toString(), addTwo);
        long secondProjectId = activeProject(addTwo).id();

        ConcurrentModel model = new ConcurrentModel();
        controller.index(model);

        assertThat(projects(model)).extracting(UiController.Project::name)
                .containsExactly("First", "Second");
        assertThat(activeProject(model).id()).isEqualTo(secondProjectId);
        assertThat(workspaces(model)).extracting(UiController.Workspace::path)
                .containsExactly(secondProject.toAbsolutePath().normalize().toString());
        assertThat(sessions(model)).extracting(UiController.Session::name)
                .containsExactly("Session #1");

        ConcurrentModel switched = new ConcurrentModel();
        controller.activateProject(firstProjectId, switched);
        assertThat(activeProject(switched).id()).isEqualTo(firstProjectId);
        assertThat(workspaces(switched)).extracting(UiController.Workspace::path)
                .containsExactly(firstProject.toAbsolutePath().normalize().toString());
    }

    @Test
    public void indexPopulatesEmptyProjectShellStateWithoutNullCollections() {
        UiController controller = newController();

        ConcurrentModel model = new ConcurrentModel();
        controller.index(model);

        assertThat(projects(model)).isEmpty();
        assertThat(workspaces(model)).isEmpty();
        assertThat(sessions(model)).isEmpty();
        assertThat(activeProject(model)).isNull();
        assertThat(activeWorkspace(model)).isNull();
        assertThat(activeSession(model)).isNull();
    }

    @Test
    public void directoryBrowserStartsAtUserHomeAndListsDirectoriesLazily(@TempDir Path tempDir) throws Exception {
        UiController controller = newController();

        ConcurrentModel modal = new ConcurrentModel();
        controller.newProjectModal(modal);

        Path homePath = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
        String home = homePath.toString();
        String selectedName = homePath.getFileName() == null ? home : homePath.getFileName().toString();
        assertThat(modal.getAttribute("currentPath")).isEqualTo(home);
        assertThat(modal.getAttribute("selectedPath")).isEqualTo(home);
        assertThat(modal.getAttribute("startPath")).isEqualTo(home);
        assertThat(modal.getAttribute("selectedName")).isEqualTo(selectedName);

        Path visibleDir = Files.createDirectory(tempDir.resolve("visible-dir"));
        Files.createFile(tempDir.resolve("notes.txt"));
        Files.createDirectories(visibleDir.resolve("nested-dir"));

        ConcurrentModel listing = new ConcurrentModel();
        String view = controller.listDirectory(visibleDir.toString(), listing);

        assertThat(view).isEqualTo("fragments/directory-list :: nodeResponse");
        assertThat(listing.getAttribute("name")).isEqualTo("visible-dir");
        assertThat(listing.getAttribute("path")).isEqualTo(visibleDir.toAbsolutePath().normalize().toString());
        assertThat(listing.getAttribute("expanded")).isEqualTo(true);
        assertThat(listing.getAttribute("selectedPath")).isEqualTo(visibleDir.toAbsolutePath().normalize().toString());
        assertThat(listing.getAttribute("selectedName")).isEqualTo("visible-dir");
        assertThat(directoryEntries(listing)).extracting("name")
                .containsExactly("nested-dir");
        assertThat(directoryEntries(listing)).allSatisfy(entry -> assertThat(entry).extracting("directory").isEqualTo(true));
        assertThat(directoryEntries(listing)).extracting("path")
                .containsExactly(visibleDir.resolve("nested-dir").toAbsolutePath().normalize().toString());
    }

    @Test
    public void directoryBrowserSortsHiddenDirectoriesLast(@TempDir Path tempDir) throws Exception {
        UiController controller = newController();

        Files.createDirectory(tempDir.resolve("beta"));
        Files.createDirectory(tempDir.resolve(".config"));
        Files.createDirectory(tempDir.resolve("alpha"));
        Files.createDirectory(tempDir.resolve(".cache"));
        Files.createFile(tempDir.resolve("notes.txt"));

        ConcurrentModel listing = new ConcurrentModel();
        controller.listDirectory(tempDir.toString(), listing);

        assertThat(directoryEntries(listing)).extracting("name")
                .containsExactly("alpha", "beta", ".cache", ".config");
    }

    @Test
    public void directoryBrowserCollapseReturnsCollapsedNodeAndKeepsSelectionConsistent(@TempDir Path tempDir) throws Exception {
        UiController controller = newController();

        Path visibleDir = Files.createDirectory(tempDir.resolve("visible-dir"));
        Files.createDirectories(visibleDir.resolve("nested-dir"));

        ConcurrentModel collapse = new ConcurrentModel();
        String view = controller.collapseDirectory(visibleDir.toString(), collapse);

        assertThat(view).isEqualTo("fragments/directory-list :: nodeResponse");
        assertThat(collapse.getAttribute("name")).isEqualTo("visible-dir");
        assertThat(collapse.getAttribute("path")).isEqualTo(visibleDir.toAbsolutePath().normalize().toString());
        assertThat(collapse.getAttribute("expanded")).isEqualTo(false);
        assertThat(collapse.getAttribute("selectedPath")).isEqualTo(visibleDir.toAbsolutePath().normalize().toString());
        assertThat(collapse.getAttribute("selectedName")).isEqualTo("visible-dir");
    }

    @Test
    public void workspaceModalEndpointExposesBranchDefaultsAndFragment(@TempDir Path projectPath) {
        UiController controller = newController();

        controller.addProject("Alpha", projectPath.toString(), new ConcurrentModel());

        ConcurrentModel modal = new ConcurrentModel();
        String view = controller.newWorkspaceModal(modal);

        assertThat(view).isEqualTo("fragments/projects :: workspaceModal");
        assertThat(modal.getAttribute("branchName")).isEqualTo("");
        assertThat(modal.getAttribute("branchMode")).isEqualTo("create");
        assertThat(modal.getAttribute("createBranch")).isEqualTo(true);
        assertThat(activeProject(modal)).isNotNull();
    }

    @Test
    public void cleanNonDefaultWorkspaceWithoutAnUpstreamClosesWithoutAConfirmationModal(@TempDir Path projectPath) throws Exception {
        initGitRepo(projectPath);
        UiController controller = newController();

        ConcurrentModel addProject = new ConcurrentModel();
        controller.addProject("Alpha", projectPath.toString(), addProject);

        ConcurrentModel addWorkspace = new ConcurrentModel();
        String branchName = "feature-close-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        controller.addWorkspace(branchName, "create", addWorkspace);
        UiController.Workspace featureWorkspace = activeWorkspace(addWorkspace);

        ConcurrentModel close = new ConcurrentModel();
        String view = controller.closeWorkspace(featureWorkspace.id(), close);

        assertThat(view).isEqualTo("fragments/projects :: shellUpdates");
        assertThat(workspaces(close)).extracting(UiController.Workspace::path)
                .containsExactly(projectPath.toAbsolutePath().normalize().toString());
        assertThat(activeWorkspace(close).path()).isEqualTo(projectPath.toAbsolutePath().normalize().toString());
        assertThat(Files.exists(Path.of(featureWorkspace.path()))).isFalse();
    }

    @Test
    public void dirtyNonDefaultWorkspaceCloseReturnsTheConfirmationModal(@TempDir Path projectPath) throws Exception {
        initGitRepo(projectPath);
        UiController controller = newController();

        ConcurrentModel addProject = new ConcurrentModel();
        controller.addProject("Alpha", projectPath.toString(), addProject);

        ConcurrentModel addWorkspace = new ConcurrentModel();
        String branchName = "feature-close-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        controller.addWorkspace(branchName, "create", addWorkspace);
        UiController.Workspace featureWorkspace = activeWorkspace(addWorkspace);
        Path featurePath = Path.of(featureWorkspace.path());
        Files.writeString(featurePath.resolve("dirty.txt"), "dirty\n");

        ConcurrentModel close = new ConcurrentModel();
        String view = controller.closeWorkspace(featureWorkspace.id(), close);

        assertThat(view).isEqualTo("fragments/projects :: workspaceCloseModal");
        assertThat(close.getAttribute("workspaceCloseStatus")).isInstanceOf(AppStateService.WorkspaceCloseInspection.class);
        assertThat(((AppStateService.WorkspaceCloseInspection) close.getAttribute("workspaceCloseStatus")).uncommittedChanges()).isTrue();
    }

    @Test
    public void confirmedDirtyWorkspaceCloseForceRemovesTheWorktreeAndReturnsShellUpdates(@TempDir Path projectPath) throws Exception {
        initGitRepo(projectPath);
        UiController controller = newController();

        ConcurrentModel addProject = new ConcurrentModel();
        controller.addProject("Alpha", projectPath.toString(), addProject);

        ConcurrentModel addWorkspace = new ConcurrentModel();
        String branchName = "feature-close-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        controller.addWorkspace(branchName, "create", addWorkspace);
        UiController.Workspace featureWorkspace = activeWorkspace(addWorkspace);
        Path featurePath = Path.of(featureWorkspace.path());
        Files.writeString(featurePath.resolve("dirty.txt"), "dirty\n");

        ConcurrentModel confirm = new ConcurrentModel();
        String view = controller.confirmWorkspaceClose(featureWorkspace.id(), confirm);

        assertThat(view).isEqualTo("fragments/projects :: shellUpdates");
        assertThat(Files.exists(featurePath)).isFalse();
        assertThat(workspaces(confirm)).extracting(UiController.Workspace::path)
                .containsExactly(projectPath.toAbsolutePath().normalize().toString());
        assertThat(activeWorkspace(confirm).path()).isEqualTo(projectPath.toAbsolutePath().normalize().toString());
        assertThat(sessions(confirm)).extracting(UiController.Session::name).containsExactly("Session #1");
        assertThat(activeSession(confirm).name()).isEqualTo("Session #1");
    }

    @Test
    public void newSessionButtonEndpointReturnsTheButtonFragment() {
        UiController controller = newController();

        assertThat(controller.newSessionButton()).isEqualTo("fragments/projects :: newSessionButton");
    }

    @Test
    public void chatMessagesStayScopedToTheActiveProjectAndSession(@TempDir Path firstProject,
                                                                 @TempDir Path secondProject) {
        RecordingHarness harness = new RecordingHarness();
        UiController controller = TestAppStateSupport.controller(harness, agentProperties());

        ConcurrentModel firstAdd = new ConcurrentModel();
        controller.addProject("First", firstProject.toString(), firstAdd);
        long firstProjectId = activeProject(firstAdd).id();

        ConcurrentModel secondAdd = new ConcurrentModel();
        controller.addProject("Second", secondProject.toString(), secondAdd);
        long secondProjectId = activeProject(secondAdd).id();

        controller.activateProject(firstProjectId, new ConcurrentModel());
        ConcurrentModel firstSend = new ConcurrentModel();
        controller.sendMessage("alpha", firstSend, null);
        controller.streamChat(assistantId(firstSend));

        controller.activateProject(secondProjectId, new ConcurrentModel());
        ConcurrentModel secondSend = new ConcurrentModel();
        controller.sendMessage("beta", secondSend, null);
        controller.streamChat(assistantId(secondSend));

        assertThat(harness.requests).hasSize(2);
        assertThat(conversation(harness.requests.get(0))).containsExactly(
                "SYSTEM:You are a concise coding assistant. Use available tools to inspect and modify the workspace when helpful. Prefer tools for file edits and external commands; return a final assistant message when done.",
                "USER:alpha");
        assertThat(conversation(harness.requests.get(1))).containsExactly(
                "SYSTEM:You are a concise coding assistant. Use available tools to inspect and modify the workspace when helpful. Prefer tools for file edits and external commands; return a final assistant message when done.",
                "USER:beta");

        ConcurrentModel firstHistory = new ConcurrentModel();
        controller.activateProject(firstProjectId, firstHistory);
        assertThat(chatMessages(firstHistory)).extracting(UiController.ChatMessage::text)
                .containsExactly("Welcome to Jupiter. Let's get started - what's on your mind?", "alpha", "reply-1");

        ConcurrentModel secondHistory = new ConcurrentModel();
        controller.activateProject(secondProjectId, secondHistory);
        assertThat(chatMessages(secondHistory)).extracting(UiController.ChatMessage::text)
                .containsExactly("Welcome to Jupiter. Let's get started - what's on your mind?", "beta", "reply-2");
    }

    private static UiController newController() {
        return TestAppStateSupport.controller(new CodingAgentHarness(null, null, null) {
            @Override
            public AgentTurnResult runTurnStreaming(AgentTurnRequest request, AgentStreamListener listener) {
                AgentTurnResult result = new AgentTurnResult("reply", List.of());
                listener.onComplete(result);
                return result;
            }
        }, agentProperties());
    }

    private static com.judepereira.jupiter2.agent.config.AgentProperties agentProperties() {
        com.judepereira.jupiter2.agent.config.AgentProperties props = new com.judepereira.jupiter2.agent.config.AgentProperties();
        props.setWorkspaceRoot(".");
        return props;
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
    private static List<UiController.Project> projects(ConcurrentModel model) {
        return (List<UiController.Project>) model.getAttribute("projects");
    }

    @SuppressWarnings("unchecked")
    private static List<UiController.Workspace> workspaces(ConcurrentModel model) {
        return (List<UiController.Workspace>) model.getAttribute("workspaces");
    }

    @SuppressWarnings("unchecked")
    private static List<UiController.Session> sessions(ConcurrentModel model) {
        return (List<UiController.Session>) model.getAttribute("sessions");
    }

    @SuppressWarnings("unchecked")
    private static List<?> directoryEntries(ConcurrentModel model) {
        return (List<?>) model.getAttribute("directoryEntries");
    }

    @SuppressWarnings("unchecked")
    private static List<UiController.ChatMessage> chatMessages(ConcurrentModel model) {
        return (List<UiController.ChatMessage>) model.getAttribute("chatMessages");
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

    private static String assistantId(ConcurrentModel model) {
        List<UiController.ChatMessage> messages = chatMessages(model);
        return messages.get(messages.size() - 1).id();
    }

    private static List<String> conversation(AgentTurnRequest request) {
        return request.getConversationHistory().stream()
                .map(message -> message.getRole().name() + ":" + message.getContent())
                .toList();
    }

    private static final class RecordingHarness extends CodingAgentHarness {
        private final List<AgentTurnRequest> requests = new ArrayList<>();

        private RecordingHarness() {
            super(null, null, null);
        }

        @Override
        public AgentTurnResult runTurnStreaming(AgentTurnRequest request, AgentStreamListener listener) {
            requests.add(request);
            AgentTurnResult result = new AgentTurnResult("reply-" + requests.size(), List.of());
            listener.onComplete(result);
            return result;
        }
    }
}
