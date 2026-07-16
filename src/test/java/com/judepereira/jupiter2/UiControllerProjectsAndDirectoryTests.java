package com.judepereira.jupiter2;

import com.judepereira.jupiter2.agent.harness.AgentTurnRequest;
import com.judepereira.jupiter2.agent.harness.AgentTurnResult;
import com.judepereira.jupiter2.agent.harness.CodingAgentHarness;
import com.judepereira.jupiter2.agent.llm.AgentStreamListener;
import com.judepereira.jupiter2.ui.UiController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ui.ConcurrentModel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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
                .containsExactly(tuple("Workspace #1", projectPath.toAbsolutePath().normalize().toString()));
        assertThat(activeWorkspace(model)).isNotNull();
        assertThat(activeWorkspace(model).path()).isEqualTo(projectPath.toAbsolutePath().normalize().toString());
        assertThat(sessions(model)).extracting(UiController.Session::name)
                .containsExactly("Session #1");
        assertThat(activeSession(model)).isNotNull();
        assertThat(activeSession(model).name()).isEqualTo("Session #1");
        assertThat(model.getAttribute("workspaceRoot")).isEqualTo(projectPath.toAbsolutePath().normalize().toString());
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

        String home = Path.of(System.getProperty("user.home")).toString();
        assertThat(modal.getAttribute("currentPath")).isEqualTo(home);
        assertThat(modal.getAttribute("selectedPath")).isEqualTo(home);
        assertThat(modal.getAttribute("startPath")).isEqualTo(home);

        Path visibleDir = Files.createDirectory(tempDir.resolve("visible-dir"));
        Files.createFile(tempDir.resolve("notes.txt"));
        Files.createDirectories(visibleDir.resolve("nested-dir"));

        ConcurrentModel listing = new ConcurrentModel();
        String view = controller.listDirectory(tempDir.toString(), listing);

        assertThat(view).isEqualTo("fragments/directory-list :: node");
        assertThat(listing.getAttribute("path")).isEqualTo(tempDir.toAbsolutePath().normalize().toString());
        assertThat(listing.getAttribute("expanded")).isEqualTo(true);
        assertThat(directoryEntries(listing)).extracting(UiController.DirectoryEntry::name)
                .containsExactly("visible-dir");
        assertThat(directoryEntries(listing)).allSatisfy(entry -> assertThat(entry.directory()).isTrue());
        assertThat(directoryEntries(listing).get(0).path()).isEqualTo(visibleDir.toString());
    }

    @Test
    public void chatMessagesStayScopedToTheActiveProjectAndSession(@TempDir Path firstProject,
                                                                 @TempDir Path secondProject) {
        RecordingHarness harness = new RecordingHarness();
        UiController controller = new UiController(harness, agentProperties(), Runnable::run);

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
        assertThat(conversation(harness.requests.get(0))).containsExactly("USER:alpha");
        assertThat(conversation(harness.requests.get(1))).containsExactly("USER:beta");

        ConcurrentModel firstHistory = new ConcurrentModel();
        controller.activateProject(firstProjectId, firstHistory);
        assertThat(chatMessages(firstHistory)).extracting(UiController.ChatMessage::text)
                .containsExactly("Welcome to Jupiter", "alpha", "reply-1");

        ConcurrentModel secondHistory = new ConcurrentModel();
        controller.activateProject(secondProjectId, secondHistory);
        assertThat(chatMessages(secondHistory)).extracting(UiController.ChatMessage::text)
                .containsExactly("Welcome to Jupiter", "beta", "reply-2");
    }

    private static UiController newController() {
        return new UiController(new CodingAgentHarness(null, null, null) {
            @Override
            public AgentTurnResult runTurnStreaming(AgentTurnRequest request, AgentStreamListener listener) {
                AgentTurnResult result = new AgentTurnResult("reply", List.of());
                listener.onComplete(result);
                return result;
            }
        }, agentProperties(), Runnable::run);
    }

    private static com.judepereira.jupiter2.agent.config.AgentProperties agentProperties() {
        com.judepereira.jupiter2.agent.config.AgentProperties props = new com.judepereira.jupiter2.agent.config.AgentProperties();
        props.setWorkspaceRoot(".");
        return props;
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
    private static List<UiController.DirectoryEntry> directoryEntries(ConcurrentModel model) {
        return (List<UiController.DirectoryEntry>) model.getAttribute("directoryEntries");
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
