package com.judepereira.jupiter2.persistence;

import com.judepereira.jupiter2.agent.llm.dto.Message;
import com.judepereira.jupiter2.persistence.Persistence.AppStateView;
import com.judepereira.jupiter2.persistence.Persistence.ChatMessageView;
import com.judepereira.jupiter2.persistence.Persistence.QueuedChatTurn;
import com.judepereira.jupiter2.persistence.Persistence.ToolCallTraceInput;
import com.judepereira.jupiter2.persistence.Persistence.ProjectView;
import com.judepereira.jupiter2.persistence.Persistence.SessionView;
import com.judepereira.jupiter2.persistence.Persistence.WorkspaceView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

public class AppStateServicePersistenceTests {

    @Test
    public void creatingAProjectStillCreatesOneWorkspaceAndSessionOne(@TempDir Path projectPath) {
        AppStateService service = TestAppStateSupport.appStateService();

        service.addOrReopenProject("Alpha", projectPath.toString());

        AppStateView view = service.loadViewData();
        assertThat(view.activeProject()).isNotNull();
        assertThat(view.projects()).extracting(ProjectView::name, ProjectView::path)
                .containsExactly(tuple("Alpha", projectPath.toAbsolutePath().normalize().toString()));
        assertThat(view.workspaces()).extracting(WorkspaceView::name, WorkspaceView::path)
                .containsExactly(tuple("Workspace #1", projectPath.toAbsolutePath().normalize().toString()));
        assertThat(view.activeWorkspace()).isNotNull();
        assertThat(view.activeWorkspace().path()).isEqualTo(projectPath.toAbsolutePath().normalize().toString());
        assertThat(view.sessions()).extracting(SessionView::name).containsExactly("Session #1");
        assertThat(view.activeSession()).isNotNull();
        assertThat(view.activeSession().name()).isEqualTo("Session #1");
        assertThat(view.activeSessionDetail().chatMessages()).extracting(ChatMessageView::text)
                .containsExactly("Welcome to Jupiter. Let's get started - what's on your mind?");
    }

    @Test
    public void creatingASecondSessionActivatesItAndKeepsChatHistoriesIsolated(@TempDir Path projectPath) {
        AppStateService service = TestAppStateSupport.appStateService();

        service.addOrReopenProject("Alpha", projectPath.toString());
        AppStateView initial = service.loadViewData();
        long workspaceId = initial.activeWorkspace().id();
        long sessionOneId = initial.activeSession().id();

        QueuedChatTurn firstTurn = service.appendUserMessageAndPendingAssistant(sessionOneId, "hello from session one");
        service.completeAssistantMessage(sessionOneId, firstTurn.assistantMessage().id(), "reply one", List.of());

        service.createSession(workspaceId, "Feature work");
        AppStateView afterCreate = service.loadViewData();
        long sessionTwoId = afterCreate.activeSession().id();

        assertThat(afterCreate.activeSession().name()).isEqualTo("Feature work");
        assertThat(afterCreate.sessions()).extracting(SessionView::name).containsExactly("Session #1", "Feature work");
        assertThat(sessionTwoId).isNotEqualTo(sessionOneId);

        QueuedChatTurn secondTurn = service.appendUserMessageAndPendingAssistant(sessionTwoId, "hello from session two");
        service.completeAssistantMessage(sessionTwoId, secondTurn.assistantMessage().id(), "reply two", List.of());

        assertThat(service.buildConversationHistory(sessionOneId)).extracting(Message::getRole, Message::getContent)
                .containsExactly(
                        tuple(Message.Role.USER, "hello from session one"),
                        tuple(Message.Role.ASSISTANT, "reply one"));
        assertThat(service.buildConversationHistory(sessionTwoId)).extracting(Message::getRole, Message::getContent)
                .containsExactly(
                        tuple(Message.Role.USER, "hello from session two"),
                        tuple(Message.Role.ASSISTANT, "reply two"));
    }

    @Test
    public void closingOneOfMultipleSessionsDeletesItAndFallsBackToAnotherSession(@TempDir Path projectPath) {
        AppStateService service = TestAppStateSupport.appStateService();

        service.addOrReopenProject("Alpha", projectPath.toString());
        AppStateView initial = service.loadViewData();
        long workspaceId = initial.activeWorkspace().id();
        long firstSessionId = initial.activeSession().id();

        service.createSession(workspaceId, "Feature work");
        AppStateView afterCreate = service.loadViewData();
        long secondSessionId = afterCreate.activeSession().id();

        service.closeSession(secondSessionId);

        AppStateView view = service.loadViewData();
        assertThat(view.activeProject()).isNotNull();
        assertThat(view.activeWorkspace()).isNotNull();
        assertThat(view.activeWorkspace().id()).isEqualTo(workspaceId);
        assertThat(view.sessions()).extracting(SessionView::id).containsExactly(firstSessionId);
        assertThat(view.activeSession()).isNotNull();
        assertThat(view.activeSession().id()).isEqualTo(firstSessionId);
    }

    @Test
    public void closingTheOnlySessionLeavesTheActiveProjectAndWorkspaceButClearsTheActiveSession(@TempDir Path projectPath) {
        AppStateService service = TestAppStateSupport.appStateService();

        service.addOrReopenProject("Alpha", projectPath.toString());
        AppStateView initial = service.loadViewData();

        service.closeSession(initial.activeSession().id());

        AppStateView view = service.loadViewData();
        assertThat(view.activeProject()).isNotNull();
        assertThat(view.activeWorkspace()).isNotNull();
        assertThat(view.sessions()).isEmpty();
        assertThat(view.activeSession()).isNull();
    }

    @Test
    public void closingANonDefaultWorkspaceDeletesItsRowsAndFallsBackToAnotherWorkspace(@TempDir Path projectPath) throws Exception {
        initGitRepo(projectPath);

        AppStateService service = TestAppStateSupport.appStateService();
        service.addOrReopenProject("Alpha", projectPath.toString());
        AppStateView initial = service.loadViewData();
        long defaultWorkspaceId = initial.activeWorkspace().id();
        long defaultSessionId = initial.activeSession().id();
        long projectId = initial.activeProject().id();

        String branchName = "feature-close-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        WorkspaceView featureWorkspace = service.createWorkspace(projectId, branchName, true);
        AppStateView afterCreate = service.loadViewData();
        long featureSessionId = afterCreate.activeSession().id();

        service.closeWorkspace(featureWorkspace.id());

        AppStateView view = service.loadViewData();
        assertThat(view.activeWorkspace()).isNotNull();
        assertThat(view.activeWorkspace().id()).isEqualTo(defaultWorkspaceId);
        assertThat(view.activeSession()).isNotNull();
        assertThat(view.activeSession().id()).isEqualTo(defaultSessionId);
        assertThat(view.workspaces()).extracting(WorkspaceView::id).containsExactly(defaultWorkspaceId);
        assertThat(view.sessions()).extracting(SessionView::id).containsExactly(defaultSessionId);

        assertThatThrownBy(() -> service.closeSession(featureSessionId)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    public void closingTheDefaultWorkspaceIsRejected(@TempDir Path projectPath) {
        AppStateService service = TestAppStateSupport.appStateService();

        service.addOrReopenProject("Alpha", projectPath.toString());
        AppStateView initial = service.loadViewData();

        assertThatThrownBy(() -> service.closeWorkspace(initial.activeWorkspace().id()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Default workspace cannot be deleted");
    }

    @Test
    public void creatingAWorkspaceCreatesGitWorktreeAndSessionOneForThatWorkspace(@TempDir Path projectPath) throws Exception {
        initGitRepo(projectPath);

        AppStateService service = TestAppStateSupport.appStateService();
        service.addOrReopenProject("Alpha", projectPath.toString());
        AppStateView initial = service.loadViewData();
        long projectId = initial.activeProject().id();

        String branchName = "feature-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Path worktreePath = projectPath.toAbsolutePath().normalize().resolveSibling(".trees").resolve(branchName).toAbsolutePath().normalize();

        WorkspaceView workspace = service.createWorkspace(projectId, branchName, true);
        AppStateView view = service.loadViewData();

        assertThat(workspace.path()).isEqualTo(worktreePath.toString());
        assertThat(Files.exists(worktreePath)).isTrue();
        assertThat(view.activeWorkspace()).isNotNull();
        assertThat(view.activeWorkspace().path()).isEqualTo(worktreePath.toString());
        assertThat(view.workspaces()).extracting(WorkspaceView::path)
                .containsExactly(projectPath.toAbsolutePath().normalize().toString(), worktreePath.toString());
        assertThat(view.activeSession()).isNotNull();
        assertThat(view.activeSession().name()).isEqualTo("Session #1");
        assertThat(view.sessions()).extracting(SessionView::name).containsExactly("Session #1");
        assertThat(view.activeSessionDetail().workspaceRoot()).isEqualTo(worktreePath.toString());
    }

    @Test
    public void closeProjectFallsBackToPreviousVisibleProject(@TempDir Path firstProject,
                                                             @TempDir Path secondProject) {
        AppStateService service = TestAppStateSupport.appStateService();

        service.addOrReopenProject("First", firstProject.toString());
        long firstProjectId = service.loadViewData().activeProject().id();

        service.addOrReopenProject("Second", secondProject.toString());
        long secondProjectId = service.loadViewData().activeProject().id();

        service.closeProject(secondProjectId);

        AppStateView view = service.loadViewData();
        assertThat(view.activeProject()).isNotNull();
        assertThat(view.activeProject().id()).isEqualTo(firstProjectId);
        assertThat(view.projects()).extracting(ProjectView::name).containsExactly("First");
    }

    @Test
    public void reopeningAClosedProjectRestoresItsSessionAndChat(@TempDir Path projectPath) {
        AppStateService service = TestAppStateSupport.appStateService();

        service.addOrReopenProject("Alpha", projectPath.toString());
        AppStateView initial = service.loadViewData();
        long projectId = initial.activeProject().id();
        long sessionId = initial.activeSession().id();

        QueuedChatTurn turn = service.appendUserMessageAndPendingAssistant(sessionId, "hello");
        service.completeAssistantMessage(sessionId, turn.assistantMessage().id(), "reply", List.of());

        service.closeProject(projectId);
        service.addOrReopenProject("Alpha", projectPath.toString());

        AppStateView reopened = service.loadViewData();
        assertThat(reopened.activeProject()).isNotNull();
        assertThat(reopened.activeSession()).isNotNull();
        assertThat(reopened.activeSession().id()).isEqualTo(sessionId);
        assertThat(reopened.activeSessionDetail().chatMessages()).extracting(ChatMessageView::text)
                .containsExactly("Welcome to Jupiter. Let's get started - what's on your mind?", "hello", "reply");
    }

    @Test
    public void buildConversationHistoryKeepsStructuredToolCallAndToolResultInOrder(@TempDir Path projectPath) {
        AppStateService service = TestAppStateSupport.appStateService();

        service.addOrReopenProject("Alpha", projectPath.toString());
        long sessionId = service.loadViewData().activeSession().id();

        QueuedChatTurn firstTurn = service.appendUserMessageAndPendingAssistant(sessionId, "make it happen");
        ToolCallTraceInput trace = new ToolCallTraceInput("tool-call-1", "write_file",
                Map.of("path", "x.txt", "content", "hello"), true, "wrote x.txt", Map.of("path", "x.txt"));

        service.appendToolCallTrace(sessionId, firstTurn.assistantMessage().id(), trace);
        service.completeAssistantMessage(sessionId, firstTurn.assistantMessage().id(), "done", List.of(trace));
        service.appendUserMessageAndPendingAssistant(sessionId, "next turn");

        List<Message> history = service.buildConversationHistory(sessionId);

        assertThat(history).hasSize(5);
        assertThat(history.get(0).getRole()).isEqualTo(Message.Role.USER);
        assertThat(history.get(0).getContent()).isEqualTo("make it happen");

        assertThat(history.get(1).getRole()).isEqualTo(Message.Role.ASSISTANT);
        assertThat(history.get(1).getToolCalls()).hasSize(1);
        assertThat(history.get(1).getToolCalls().get(0).getToolCallId()).isEqualTo("tool-call-1");

        assertThat(history.get(2).getRole()).isEqualTo(Message.Role.TOOL);
        assertThat(history.get(2).getToolCallId()).isEqualTo("tool-call-1");
        assertThat(history.get(2).getContent()).isEqualTo("wrote x.txt");

        assertThat(history.get(3).getRole()).isEqualTo(Message.Role.ASSISTANT);
        assertThat(history.get(3).getContent()).isEqualTo("done");
        assertThat(history.get(3).getToolCalls()).isNullOrEmpty();

        assertThat(history.get(4).getRole()).isEqualTo(Message.Role.USER);
        assertThat(history.get(4).getContent()).isEqualTo("next turn");
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
}
