package com.judepereira.jupiter.persistence;

import com.judepereira.jupiter.agent.llm.dto.Message;
import com.judepereira.jupiter.agent.llm.AgentModelClient;
import com.judepereira.jupiter.agent.llm.AgentModelClientFactory;
import com.judepereira.jupiter.agent.llm.AgentModelOptions;
import com.judepereira.jupiter.agent.llm.dto.ModelResponse;
import com.judepereira.jupiter.agent.llm.dto.ToolCall;
import com.judepereira.jupiter.agent.llm.dto.ToolDefinition;
import com.judepereira.jupiter.agent.catalog.AgentDefinition;
import com.judepereira.jupiter.agent.catalog.AgentMode;
import com.judepereira.jupiter.agent.catalog.ModelDefinition;
import com.judepereira.jupiter.agent.catalog.ThinkingLevel;
import com.judepereira.jupiter.persistence.Persistence.AppStateView;
import com.judepereira.jupiter.persistence.Persistence.ChatMessageView;
import com.judepereira.jupiter.persistence.Persistence.ChatMessageMetadata;
import com.judepereira.jupiter.persistence.Persistence.ChangedFileDraft;
import com.judepereira.jupiter.persistence.Persistence.ChangedFileView;
import com.judepereira.jupiter.persistence.Persistence.QueuedChatTurn;
import com.judepereira.jupiter.persistence.Persistence.ReviewSource;
import com.judepereira.jupiter.persistence.Persistence.ToolCallTraceInput;
import com.judepereira.jupiter.persistence.Persistence.McpServerHeader;
import com.judepereira.jupiter.persistence.Persistence.McpServerView;
import com.judepereira.jupiter.persistence.Persistence.ProjectEnvironmentVariable;
import com.judepereira.jupiter.persistence.Persistence.ProjectView;
import com.judepereira.jupiter.persistence.Persistence.SessionView;
import com.judepereira.jupiter.persistence.Persistence.WorkspaceView;
import com.judepereira.jupiter.testsupport.ModelCatalogTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
                .containsExactly(tuple("Default Workspace", projectPath.toAbsolutePath().normalize().toString()));
        assertThat(view.activeWorkspace()).isNotNull();
        assertThat(view.activeWorkspace().path()).isEqualTo(projectPath.toAbsolutePath().normalize().toString());
        assertThat(view.sessions()).extracting(SessionView::name).containsExactly("Session #1");
        assertThat(view.activeSession()).isNotNull();
        assertThat(view.activeSession().name()).isEqualTo("Session #1");
        assertThat(view.activeSessionDetail().chatMessages()).extracting(ChatMessageView::text)
                .containsExactly("Welcome to Jupiter. Let's get started - what's on your mind?");
    }

    @Test
    public void completedInactiveTurnsMarkOnlyThatSessionAndWorkspaceUnread(@TempDir Path projectPath) {
        List<Object> events = new ArrayList<>();
        TestAppStateSupport.AppStateTestContext context = TestAppStateSupport.appStateContext(event -> events.add(event));
        AppStateService service = context.service();

        service.addOrReopenProject("Alpha", projectPath.toString());
        AppStateView initial = service.loadViewData();
        long workspaceId = initial.activeWorkspace().id();
        long sessionOneId = initial.activeSession().id();
        service.createSession(workspaceId, "Feature work");
        long sessionTwoId = service.loadViewData().activeSession().id();

        QueuedChatTurn inactiveTurn = service.appendUserMessageAndPendingAssistant(sessionOneId, "hello from inactive session");
        service.appendToolCallTrace(sessionOneId, inactiveTurn.assistantMessage().id(),
                new ToolCallTraceInput("tool-1", "read_file", Map.of("path", "README.md"), true, "read README", Map.of()));
        context.activeStreamRegistryService().register(inactiveTurn.assistantMessage().id(), sessionOneId, projectPath.toString());

        AppStateView afterToolCall = service.loadViewData();
        assertThat(afterToolCall.sessions()).extracting(SessionView::id, SessionView::inProgress)
                .containsExactly(tuple(sessionOneId, true), tuple(sessionTwoId, false));
        assertThat(afterToolCall.workspaces()).extracting(WorkspaceView::id, WorkspaceView::inProgress)
                .containsExactly(tuple(workspaceId, true));
        assertThat(afterToolCall.sessions()).extracting(SessionView::id, SessionView::unread)
                .containsExactly(tuple(sessionOneId, false), tuple(sessionTwoId, false));

        context.activeStreamRegistryService().unregister(inactiveTurn.assistantMessage().id());
        events.clear();
        service.completeAssistantMessage(sessionOneId, inactiveTurn.assistantMessage().id(), "reply one", List.of());

        AppStateView afterComplete = service.loadViewData();
        assertThat(afterComplete.sessions()).extracting(SessionView::id, SessionView::inProgress)
                .containsExactly(tuple(sessionOneId, false), tuple(sessionTwoId, false));
        assertThat(afterComplete.workspaces()).extracting(WorkspaceView::id, WorkspaceView::inProgress)
                .containsExactly(tuple(workspaceId, false));
        assertThat(afterComplete.sessions()).extracting(SessionView::id, SessionView::unread)
                .containsExactly(tuple(sessionOneId, true), tuple(sessionTwoId, false));
        assertThat(afterComplete.workspaces()).extracting(WorkspaceView::id, WorkspaceView::unread)
                .containsExactly(tuple(workspaceId, true));
        assertThat(events).extracting(Object::toString)
                .containsExactly("SessionMarkedUnreadEvent[sessionId=" + sessionOneId + "]");

        service.activateSession(sessionOneId);

        AppStateView afterActivate = service.loadViewData();
        assertThat(afterActivate.activeSession().id()).isEqualTo(sessionOneId);
        assertThat(afterActivate.sessions()).extracting(SessionView::id, SessionView::inProgress)
                .containsExactly(tuple(sessionOneId, false), tuple(sessionTwoId, false));
        assertThat(afterActivate.workspaces()).extracting(WorkspaceView::id, WorkspaceView::inProgress)
                .containsExactly(tuple(workspaceId, false));
        assertThat(afterActivate.sessions()).extracting(SessionView::id, SessionView::unread)
                .containsExactly(tuple(sessionOneId, false), tuple(sessionTwoId, false));
        assertThat(afterActivate.workspaces()).extracting(WorkspaceView::id, WorkspaceView::unread)
                .containsExactly(tuple(workspaceId, false));
    }

    @Test
    public void completedAssistantMessagePersistsCompletedAtAndThreadsItIntoSessionDetail(@TempDir Path projectPath) {
        TestAppStateSupport.AppStateTestContext context = TestAppStateSupport.appStateContext(event -> {});
        AppStateService service = context.service();
        AppStateRepository repository = context.repository();

        service.addOrReopenProject("Alpha", projectPath.toString());
        long sessionId = service.loadViewData().activeSession().id();

        QueuedChatTurn queuedTurn = service.appendUserMessageAndPendingAssistant(sessionId, "hello");
        assertThat(queuedTurn.assistantMessage().completedTs()).isNull();

        var pendingRow = repository.findMessageBySessionAndPublicId(sessionId, queuedTurn.assistantMessage().id());
        assertThat(pendingRow.completedAt()).isNull();
        assertThat(pendingRow.pending()).isTrue();

        ChatMessageView completed = service.completeAssistantMessage(sessionId, queuedTurn.assistantMessage().id(), "reply", List.of());
        assertThat(completed.completedTs()).isNotNull();

        var completedRow = repository.findMessageBySessionAndPublicId(sessionId, queuedTurn.assistantMessage().id());
        assertThat(completedRow.completedAt()).isNotNull();
        assertThat(completedRow.pending()).isFalse();

        ChatMessageView threaded = service.loadSessionDetail(sessionId).chatMessages().stream()
                .filter(message -> message.id().equals(queuedTurn.assistantMessage().id()))
                .findFirst()
                .orElseThrow();
        assertThat(threaded.completedTs()).isEqualTo(completedRow.completedAt().toEpochMilli());
        assertThat(threaded.text()).isEqualTo("reply");
    }

    @Test
    public void stopAssistantMessagePersistsStoppedTextAndClearsPendingFlag(@TempDir Path projectPath) {
        TestAppStateSupport.AppStateTestContext context = TestAppStateSupport.appStateContext(event -> {});
        AppStateService service = context.service();

        service.addOrReopenProject("Alpha", projectPath.toString());
        long sessionId = service.loadViewData().activeSession().id();

        QueuedChatTurn queuedTurn = service.appendUserMessageAndPendingAssistant(sessionId, "hello");

        ChatMessageView stopped = service.stopAssistantMessage(sessionId, queuedTurn.assistantMessage().id(), "partial reply");

        assertThat(stopped.pending()).isFalse();
        assertThat(stopped.completedTs()).isNotNull();
        assertThat(stopped.text()).isEqualTo("partial reply\n\nAction Interrupted");

        ChatMessageView threaded = service.loadSessionDetail(sessionId).chatMessages().stream()
                .filter(message -> message.id().equals(queuedTurn.assistantMessage().id()))
                .findFirst()
                .orElseThrow();
        assertThat(threaded.pending()).isFalse();
        assertThat(threaded.text()).isEqualTo("partial reply\n\nAction Interrupted");
    }

    @Test
    public void completedTaskToolCallKeepsItsPersistedToolCallIdAfterCompletionClearsToolCallsJson(@TempDir Path projectPath) {
        TestAppStateSupport.AppStateTestContext context = TestAppStateSupport.appStateContext(event -> {});
        AppStateService service = context.service();
        AppStateRepository repository = context.repository();

        service.addOrReopenProject("Alpha", projectPath.toString());
        long sessionId = service.loadViewData().activeSession().id();

        QueuedChatTurn turn = service.appendUserMessageAndPendingAssistant(sessionId, "use a task");
        ToolCallTraceInput trace = new ToolCallTraceInput("task-1", "task", Map.of("agentId", "engineer", "requestSummary", "Write the parser implementation"), true, "running", Map.of());

        service.appendToolCallTrace(sessionId, turn.assistantMessage().id(), trace);
        service.completeAssistantMessage(sessionId, turn.assistantMessage().id(), "done", List.of(trace));

        var assistantRow = repository.findMessageBySessionAndPublicId(sessionId, turn.assistantMessage().id());
        assertThat(assistantRow.toolCallsJson()).isNull();
        assertThat(repository.listToolCallTracesByAssistantMessage(assistantRow.id()))
                .singleElement()
                .extracting(AppStateRepository.ToolCallTraceRow::toolCallId)
                .isEqualTo("task-1");

        ChatMessageView assistant = service.loadViewData().activeSessionDetail().chatMessages().stream()
                .filter(message -> "assistant".equals(message.role()))
                .findFirst()
                .orElseThrow();
        assertThat(assistant.toolCalls()).singleElement().satisfies(call -> assertThat(call.toolCallId()).isEqualTo("task-1"));
    }

    @Test
    public void taskToolCallViewPrefersRequestSummaryAndFallsBackToLegacyTaskBody(@TempDir Path projectPath) {
        TestAppStateSupport.AppStateTestContext context = TestAppStateSupport.appStateContext(event -> {});
        AppStateService service = context.service();

        service.addOrReopenProject("Alpha", projectPath.toString());
        long sessionId = service.loadViewData().activeSession().id();

        QueuedChatTurn turn = service.appendUserMessageAndPendingAssistant(sessionId, "use a task");
        ToolCallTraceInput trace = new ToolCallTraceInput("task-1", "task", Map.of(
                "agentId", "engineer",
                "requestSummary", "Implement the parser",
                "task", "Write the parser implementation"
        ), true, "running", Map.of());

        service.appendToolCallTrace(sessionId, turn.assistantMessage().id(), trace);
        service.completeAssistantMessage(sessionId, turn.assistantMessage().id(), "done", List.of(trace));

        ChatMessageView assistant = service.loadViewData().activeSessionDetail().chatMessages().stream()
                .filter(message -> "assistant".equals(message.role()))
                .findFirst()
                .orElseThrow();
        assertThat(assistant.toolCalls()).singleElement().satisfies(call -> assertThat(call.taskBody()).isEqualTo("Implement the parser"));
    }

    @Test
    public void taskToolCallViewDerivesTaskBodyFromLegacyTaskField(@TempDir Path projectPath) {
        TestAppStateSupport.AppStateTestContext context = TestAppStateSupport.appStateContext(event -> {});
        AppStateService service = context.service();

        service.addOrReopenProject("Alpha", projectPath.toString());
        long sessionId = service.loadViewData().activeSession().id();

        QueuedChatTurn turn = service.appendUserMessageAndPendingAssistant(sessionId, "use a task");
        ToolCallTraceInput trace = new ToolCallTraceInput("task-1", "task", Map.of("agentId", "engineer", "requestSummary", "Implement the parser", "task", "Write the parser implementation"), true, "running", Map.of());

        service.appendToolCallTrace(sessionId, turn.assistantMessage().id(), trace);
        service.completeAssistantMessage(sessionId, turn.assistantMessage().id(), "done", List.of(trace));

        ChatMessageView assistant = service.loadViewData().activeSessionDetail().chatMessages().stream()
                .filter(message -> "assistant".equals(message.role()))
                .findFirst()
                .orElseThrow();
        assertThat(assistant.toolCalls()).singleElement().satisfies(call -> assertThat(call.taskBody()).isEqualTo("Implement the parser"));
    }
    @Test
    public void forkPrimarySessionCopiesConversationAndToolCallStateWithoutDraftOrReviewState(@TempDir Path projectPath) {
        TestAppStateSupport.AppStateTestContext context = TestAppStateSupport.appStateContext(event -> {});
        AppStateService service = context.service();
        AppStateRepository repository = context.repository();
        service.addOrReopenProject("Alpha", projectPath.toString());
        long sourceSessionId = service.loadViewData().activeSession().id();
        service.updateSessionDraft(sourceSessionId, "draft text");
        service.addChangedFilesToSession(sourceSessionId, List.of(new ChangedFileDraft("src/Fork.java", "diff")));
        ChatMessageMetadata metadata = new ChatMessageMetadata("engineer", "Engineer", "openai/gpt-5.5", "HIGH");
        QueuedChatTurn turn = service.appendUserMessageAndPendingAssistant(sourceSessionId, "user-1", "assistant-1", "use a task", metadata);
        ToolCallTraceInput trace = new ToolCallTraceInput("task-1", "task", Map.of("agentId", "engineer", "requestSummary", "Write the parser implementation"), true, "task output", Map.of("sessionId", sourceSessionId));
        service.appendToolCallTrace(sourceSessionId, turn.assistantMessage().id(), trace);
        service.completeAssistantMessage(sourceSessionId, turn.assistantMessage().id(), "final reply", List.of(trace));
        long forkedSessionId = service.forkPrimarySessionAtAssistantMessage(sourceSessionId, turn.assistantMessage().id());
        AppStateView forkedView = service.loadViewData();
        assertThat(forkedView.activeSession().id()).isEqualTo(forkedSessionId);
        assertThat(forkedView.activeSessionDetail().chatDraft()).isEmpty();
        assertThat(forkedView.activeSessionDetail().changedFiles()).isEmpty();
        var sourceMessages = repository.listMessagesBySession(sourceSessionId);
        var forkMessages = repository.listMessagesBySession(forkedSessionId);
        assertThat(forkMessages).hasSize(sourceMessages.size());
        assertThat(forkMessages).extracting(AppStateRepository.ConversationMessageRow::publicId)
                .doesNotContainAnyElementsOf(sourceMessages.stream().map(AppStateRepository.ConversationMessageRow::publicId).toList());
        var forkAssistant = forkMessages.stream().filter(message -> "assistant".equals(message.role()) && message.showInChat()).findFirst().orElseThrow();
        var sourceAssistant = repository.findMessageBySessionAndPublicId(sourceSessionId, turn.assistantMessage().id());
        assertThat(forkAssistant.completedAt()).isNotNull();
        assertThat(forkAssistant.pending()).isFalse();
        assertThat(forkAssistant.content()).isEqualTo("final reply");
        assertThat(forkAssistant.agentId()).isEqualTo(sourceAssistant.agentId());
        assertThat(forkAssistant.modelId()).isEqualTo(sourceAssistant.modelId());
        assertThat(forkAssistant.thinkingLevel()).isEqualTo(sourceAssistant.thinkingLevel());
        var sourceTraces = repository.listToolCallTracesBySession(sourceSessionId);
        var forkTraces = repository.listToolCallTracesBySession(forkedSessionId);
        assertThat(forkTraces).hasSize(sourceTraces.size());
        assertThat(forkTraces).allSatisfy(traceRow -> {
            assertThat(traceRow.assistantMessageId()).isEqualTo(forkAssistant.id());
            assertThat(traceRow.machineSummaryJson()).doesNotContain("subagentSessionId", "subagentAgentId", "subagentAgentName");
        });
    }
    @Test
    public void forkPrimarySessionFailsLoudlyForHiddenSessionsNonAssistantPendingAndForeignMessages(@TempDir Path projectPath) {
        TestAppStateSupport.AppStateTestContext context = TestAppStateSupport.appStateContext(event -> {});
        AppStateService service = context.service();
        service.addOrReopenProject("Alpha", projectPath.toString());
        long sourceSessionId = service.loadViewData().activeSession().id();
        QueuedChatTurn turn = service.appendUserMessageAndPendingAssistant(sourceSessionId, "hello");
        AgentDefinition subagent = new AgentDefinition("engineer", "Engineer", "", "hidden", AgentMode.SUBAGENT,
                "openai/gpt-5.5", ThinkingLevel.MEDIUM, "low", true, true, List.of("write_file"));
        long hiddenSessionId = service.createHiddenSubagentSession(sourceSessionId, "task-1", subagent);
        QueuedChatTurn hiddenTurn = service.appendUserMessageAndPendingAssistant(hiddenSessionId, "child");
        assertThatThrownBy(() -> service.forkPrimarySessionAtAssistantMessage(sourceSessionId, turn.userMessage().id()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("assistant message");
        assertThatThrownBy(() -> service.forkPrimarySessionAtAssistantMessage(sourceSessionId, turn.assistantMessage().id()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pending assistant message");
        assertThatThrownBy(() -> service.forkPrimarySessionAtAssistantMessage(hiddenSessionId, hiddenTurn.assistantMessage().id()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Hidden sessions");

        service.addOrReopenProject("Beta", projectPath.resolveSibling("beta").toString());
        long foreignSessionId = service.loadViewData().activeSession().id();
        QueuedChatTurn foreignTurn = service.appendUserMessageAndPendingAssistant(foreignSessionId, "foreign");
        service.completeAssistantMessage(foreignSessionId, foreignTurn.assistantMessage().id(), "foreign reply", List.of());
        service.activateSession(sourceSessionId);
        assertThatThrownBy(() -> service.forkPrimarySessionAtAssistantMessage(sourceSessionId, foreignTurn.assistantMessage().id()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("message");
    }
    @Test
    public void completedTaskTurnWithHiddenChildSessionDoesNotSynthesizeTheOldCallOntoALaterPendingTurn(@TempDir Path projectPath) {
        TestAppStateSupport.AppStateTestContext context = TestAppStateSupport.appStateContext(event -> {});
        AppStateService service = context.service();

        service.addOrReopenProject("Alpha", projectPath.toString());
        long sessionId = service.loadViewData().activeSession().id();

        QueuedChatTurn firstTurn = service.appendUserMessageAndPendingAssistant(sessionId, "use a task");
        ToolCallTraceInput trace = new ToolCallTraceInput("task-1", "task", Map.of("agentId", "engineer", "requestSummary", "Use a task"), true, "running", Map.of());
        service.appendToolCallTrace(sessionId, firstTurn.assistantMessage().id(), trace);

        AgentDefinition subagent = new AgentDefinition("engineer", "Engineer", "", "Hidden subagent prompt", AgentMode.SUBAGENT,
                "openai/gpt-5.5", ThinkingLevel.MEDIUM, "low", true, true, List.of("write_file"));
        long hiddenSessionId = service.createHiddenSubagentSession(sessionId, "task-1", subagent);
        var child = service.loadSubagentSessionDetail(hiddenSessionId);
        assertThat(child.parentSessionId()).isEqualTo(sessionId);
        assertThat(child.parentToolCallId()).isEqualTo("task-1");

        service.completeAssistantMessage(sessionId, firstTurn.assistantMessage().id(), "done", List.of(trace));
        QueuedChatTurn nextTurn = service.appendUserMessageAndPendingAssistant(sessionId, "next turn");
        context.activeStreamRegistryService().register(nextTurn.assistantMessage().id(), sessionId, projectPath.toString());

        List<ChatMessageView> messages = service.loadViewData().activeSessionDetail().chatMessages();
        assertThat(messages).filteredOn(message -> "assistant".equals(message.role()) && !message.pending())
                .singleElement()
                .satisfies(message -> assertThat(message.toolCalls()).extracting(call -> call.toolCallId()).containsExactly("task-1"));
        assertThat(messages).filteredOn(message -> "assistant".equals(message.role()) && message.pending())
                .singleElement()
                .satisfies(message -> assertThat(message.toolCalls()).isEmpty());
        context.activeStreamRegistryService().unregister(nextTurn.assistantMessage().id());
    }

    @Test
    public void pendingParentAssistantWithHiddenChildSessionShowsASyntheticRunningTaskCallBeforeTheTraceIsAppended(@TempDir Path projectPath) {
        TestAppStateSupport.AppStateTestContext context = TestAppStateSupport.appStateContext(event -> {});
        AppStateService service = context.service();

        service.addOrReopenProject("Alpha", projectPath.toString());
        long sessionId = service.loadViewData().activeSession().id();

        QueuedChatTurn turn = service.appendUserMessageAndPendingAssistant(sessionId, "use a task");
        context.activeStreamRegistryService().register(turn.assistantMessage().id(), sessionId, projectPath.toString());
        AgentDefinition subagent = new AgentDefinition("engineer", "Engineer", "", "Hidden subagent prompt", AgentMode.SUBAGENT,
                "openai/gpt-5.5", ThinkingLevel.MEDIUM, "low", true, true, List.of("write_file"));
        long hiddenSessionId = service.createHiddenSubagentSession(sessionId, "task-1", subagent);

        ChatMessageView assistant = service.loadViewData().activeSessionDetail().chatMessages().stream()
                .filter(message -> "assistant".equals(message.role()) && message.pending())
                .findFirst()
                .orElseThrow();

        assertThat(assistant.toolCalls()).singleElement().satisfies(call -> {
            assertThat(call.toolCallId()).isEqualTo("task-1");
            assertThat(call.toolName()).isEqualTo("task");
            assertThat(call.status()).isEqualTo("running");
            assertThat(call.subagentSessionId()).isEqualTo(hiddenSessionId);
            assertThat(call.subagentAgentId()).isEqualTo("engineer");
            assertThat(call.subagentAgentName()).isEqualTo("Engineer");
        });
        context.activeStreamRegistryService().unregister(turn.assistantMessage().id());
    }

    @Test
    public void laterPendingAssistantWithHiddenChildSessionStillShowsASyntheticRunningTaskCallWhenAnEarlierTurnUsedTheSameToolCallId(@TempDir Path projectPath) {
        TestAppStateSupport.AppStateTestContext context = TestAppStateSupport.appStateContext(event -> {});
        AppStateService service = context.service();

        service.addOrReopenProject("Alpha", projectPath.toString());
        long sessionId = service.loadViewData().activeSession().id();

        QueuedChatTurn firstTurn = service.appendUserMessageAndPendingAssistant(sessionId, "use a task");
        ToolCallTraceInput trace = new ToolCallTraceInput("task-1", "task", Map.of("agentId", "engineer", "requestSummary", "Use a task again"), true, "running", Map.of());
        service.appendToolCallTrace(sessionId, firstTurn.assistantMessage().id(), trace);
        service.completeAssistantMessage(sessionId, firstTurn.assistantMessage().id(), "done", List.of(trace));

        QueuedChatTurn nextTurn = service.appendUserMessageAndPendingAssistant(sessionId, "use the same task again");
        context.activeStreamRegistryService().register(nextTurn.assistantMessage().id(), sessionId, projectPath.toString());
        AgentDefinition subagent = new AgentDefinition("engineer", "Engineer", "", "Hidden subagent prompt", AgentMode.SUBAGENT,
                "openai/gpt-5.5", ThinkingLevel.MEDIUM, "low", true, true, List.of("write_file"));
        long hiddenSessionId = service.createHiddenSubagentSession(sessionId, "task-1", subagent);

        ChatMessageView assistant = service.loadViewData().activeSessionDetail().chatMessages().stream()
                .filter(message -> "assistant".equals(message.role()) && message.pending())
                .findFirst()
                .orElseThrow();

        assertThat(assistant.toolCalls()).singleElement().satisfies(call -> {
            assertThat(call.toolCallId()).isEqualTo("task-1");
            assertThat(call.toolName()).isEqualTo("task");
            assertThat(call.status()).isEqualTo("running");
            assertThat(call.subagentSessionId()).isEqualTo(hiddenSessionId);
            assertThat(call.subagentAgentId()).isEqualTo("engineer");
            assertThat(call.subagentAgentName()).isEqualTo("Engineer");
        });
        assertThat(service.loadViewData().activeSessionDetail().chatMessages()).filteredOn(message -> "assistant".equals(message.role()) && !message.pending())
                .anySatisfy(message -> assertThat(message.toolCalls()).extracting(call -> call.toolCallId()).contains("task-1"));
        context.activeStreamRegistryService().unregister(nextTurn.assistantMessage().id());
    }

    @Test
    public void activeHiddenAndAlreadyUnreadSessionsDoNotPublishUnreadEvents(@TempDir Path projectPath) {
        List<Object> events = new ArrayList<>();
        TestAppStateSupport.AppStateTestContext context = TestAppStateSupport.appStateContext(event -> events.add(event));
        AppStateService service = context.service();
        AppStateRepository repository = context.repository();

        service.addOrReopenProject("Alpha", projectPath.toString());
        AppStateView initial = service.loadViewData();
        long workspaceId = initial.activeWorkspace().id();
        long activeSessionId = initial.activeSession().id();

        QueuedChatTurn activeTurn = service.appendUserMessageAndPendingAssistant(activeSessionId, "active user");
        events.clear();
        service.completeAssistantMessage(activeSessionId, activeTurn.assistantMessage().id(), "active reply", List.of());
        assertThat(events).isEmpty();
        assertThat(service.loadViewData().sessions()).filteredOn(session -> session.id() == activeSessionId)
                .singleElement().satisfies(session -> assertThat(session.unread()).isFalse());

        AgentDefinition subagent = new AgentDefinition("engineer", "Engineer", "", "Hidden subagent prompt", AgentMode.SUBAGENT,
                "openai/gpt-5.5", ThinkingLevel.MEDIUM, "low", true, true, List.of("write_file"));
        long hiddenSessionId = service.createHiddenSubagentSession(activeSessionId, "parent-tool-call", subagent);
        QueuedChatTurn hiddenTurn = service.appendUserMessageAndPendingAssistant(hiddenSessionId, "hidden user");
        events.clear();
        service.completeAssistantMessage(hiddenSessionId, hiddenTurn.assistantMessage().id(), "hidden reply", List.of());
        assertThat(events).isEmpty();
        assertThat(repository.findSession(hiddenSessionId).unread()).isFalse();
        assertThat(repository.findSession(hiddenSessionId).hidden()).isTrue();

        service.createSession(workspaceId, "Already unread");
        long unreadSessionId = service.loadViewData().activeSession().id();
        service.activateSession(activeSessionId);
        repository.updateSessionUnread(unreadSessionId, true);
        QueuedChatTurn unreadTurn = service.appendUserMessageAndPendingAssistant(unreadSessionId, "unread user");
        events.clear();
        service.completeAssistantMessage(unreadSessionId, unreadTurn.assistantMessage().id(), "unread reply", List.of());
        assertThat(events).isEmpty();
        assertThat(repository.findSession(unreadSessionId).unread()).isTrue();
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
    public void activatingOrReopeningAWorkspaceWithNoSessionsRestoresTheWorkspaceAndClearsTheActiveSession(@TempDir Path projectPath) {
        AppStateService service = TestAppStateSupport.appStateService();

        service.addOrReopenProject("Alpha", projectPath.toString());
        AppStateView initial = service.loadViewData();
        long projectId = initial.activeProject().id();
        long workspaceId = initial.activeWorkspace().id();
        long sessionId = initial.activeSession().id();

        service.closeSession(sessionId);
        service.collapseWorkspace(workspaceId);

        service.activateWorkspace(workspaceId);

        AppStateView afterActivateWorkspace = service.loadViewData();
        assertThat(afterActivateWorkspace.activeWorkspace()).isNotNull();
        assertThat(afterActivateWorkspace.activeWorkspace().id()).isEqualTo(workspaceId);
        assertThat(afterActivateWorkspace.sessions()).isEmpty();
        assertThat(afterActivateWorkspace.activeSession()).isNull();

        service.closeProject(projectId);
        service.addOrReopenProject("Alpha", projectPath.toString());

        AppStateView reopened = service.loadViewData();
        assertThat(reopened.activeProject()).isNotNull();
        assertThat(reopened.activeProject().id()).isEqualTo(projectId);
        assertThat(reopened.activeWorkspace()).isNotNull();
        assertThat(reopened.activeWorkspace().id()).isEqualTo(workspaceId);
        assertThat(reopened.sessions()).isEmpty();
        assertThat(reopened.activeSession()).isNull();
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
        Path worktreePath = projectPath.toAbsolutePath().normalize().resolveSibling(".trees")
                .resolve(projectPath.getFileName().toString())
                .resolve(branchName)
                .toAbsolutePath()
                .normalize();

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
    public void creatingAWorkspaceAllowsSlashSeparatedGitBranchNames(@TempDir Path projectPath) throws Exception {
        initGitRepo(projectPath);

        AppStateService service = TestAppStateSupport.appStateService();
        service.addOrReopenProject("Alpha", projectPath.toString());
        long projectId = service.loadViewData().activeProject().id();

        String branchName = "feature/slash-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Path worktreePath = projectPath.toAbsolutePath().normalize().resolveSibling(".trees")
                .resolve(projectPath.getFileName().toString())
                .resolve(branchName)
                .toAbsolutePath()
                .normalize();

        WorkspaceView workspace = service.createWorkspace(projectId, branchName, true);

        assertThat(workspace.name()).isEqualTo(branchName);
        assertThat(workspace.path()).isEqualTo(worktreePath.toString());
        assertThat(Files.exists(worktreePath)).isTrue();
    }

    @Test
    public void creatingAWorkspaceRejectsInvalidNewBranchNameBeforePersistingWorkspace(@TempDir Path projectPath) throws Exception {
        initGitRepo(projectPath);

        AppStateService service = TestAppStateSupport.appStateService();
        service.addOrReopenProject("Alpha", projectPath.toString());
        AppStateView initial = service.loadViewData();
        long projectId = initial.activeProject().id();

        assertThatThrownBy(() -> service.createWorkspace(projectId, "feature unsafe", true))
                .isInstanceOf(InvalidGitBranchNameException.class)
                .hasMessageContaining("Invalid Git branch name");

        AppStateView view = service.loadViewData();
        assertThat(view.workspaces()).extracting(WorkspaceView::id)
                .containsExactly(initial.activeWorkspace().id());
        assertThat(view.sessions()).extracting(SessionView::id)
                .containsExactly(initial.activeSession().id());
    }

    @Test
    public void creatingAWorkspaceRejectsBlankNewBranchNameBeforePersistingWorkspace(@TempDir Path projectPath) throws Exception {
        initGitRepo(projectPath);

        AppStateService service = TestAppStateSupport.appStateService();
        service.addOrReopenProject("Alpha", projectPath.toString());
        AppStateView initial = service.loadViewData();
        long projectId = initial.activeProject().id();

        assertThatThrownBy(() -> service.createWorkspace(projectId, "   ", true))
                .isInstanceOf(InvalidGitBranchNameException.class)
                .hasMessageContaining("Branch name is required");

        AppStateView view = service.loadViewData();
        assertThat(view.workspaces()).extracting(WorkspaceView::id)
                .containsExactly(initial.activeWorkspace().id());
        assertThat(view.sessions()).extracting(SessionView::id)
                .containsExactly(initial.activeSession().id());
    }

    @Test
    public void checkingOutExistingBranchDoesNotUseNewBranchNameValidation(@TempDir Path projectPath) throws Exception {
        initGitRepo(projectPath);

        AppStateService service = TestAppStateSupport.appStateService();
        service.addOrReopenProject("Alpha", projectPath.toString());
        long projectId = service.loadViewData().activeProject().id();

        assertThatThrownBy(() -> service.createWorkspace(projectId, "feature unsafe", false))
                .isInstanceOf(GitWorktreeException.class)
                .isNotInstanceOf(InvalidGitBranchNameException.class);
    }

    @Test
    public void reviewSourceSwitchesBetweenSessionAndGitChangedFiles(@TempDir Path projectPath) throws Exception {
        initGitRepo(projectPath);

        AppStateService service = TestAppStateSupport.appStateService();
        service.addOrReopenProject("Alpha", projectPath.toString());
        long sessionId = service.loadViewData().activeSession().id();

        Files.writeString(projectPath.resolve("session-only.txt"), "session content\n");
        Files.writeString(projectPath.resolve("outside-only.txt"), "outside content\n");

        service.addChangedFilesToSession(sessionId, List.of(new ChangedFileDraft("session-only.txt", "session diff")));

        AppStateView sessionView = service.loadViewData();
        assertThat(sessionView.activeSessionDetail()).isNotNull();
        assertThat(sessionView.activeSessionDetail().reviewSource()).isEqualTo(ReviewSource.SESSION);
        assertThat(sessionView.activeSessionDetail().changedFiles()).extracting(com.judepereira.jupiter.persistence.Persistence.ChangedFileView::path)
                .containsExactly("session-only.txt");

        service.switchReviewSource(sessionId, ReviewSource.GIT);

        AppStateView gitView = service.loadViewData();
        assertThat(gitView.activeSessionDetail()).isNotNull();
        assertThat(gitView.activeSessionDetail().reviewSource()).isEqualTo(ReviewSource.GIT);
        assertThat(gitView.activeSessionDetail().changedFiles()).extracting(com.judepereira.jupiter.persistence.Persistence.ChangedFileView::path)
                .contains("session-only.txt", "outside-only.txt");
    }

    @Test
    public void recordingChangedFilesKeepsReviewPanelClosedAndSelectsLatestSessionFile(@TempDir Path projectPath) throws Exception {
        initGitRepo(projectPath);

        AppStateService service = TestAppStateSupport.appStateService();
        service.addOrReopenProject("Alpha", projectPath.toString());
        long sessionId = service.loadViewData().activeSession().id();

        assertThat(service.loadViewData().activeSessionDetail().reviewPanelOpen()).isFalse();

        service.addChangedFilesToSession(sessionId, List.of(
                new ChangedFileDraft("first-review-file.txt", "first diff"),
                new ChangedFileDraft("second-review-file.txt", "second diff")
        ));

        AppStateView view = service.loadViewData();
        assertThat(view.activeSessionDetail()).isNotNull();
        assertThat(view.activeSessionDetail().reviewPanelOpen()).isFalse();
        assertThat(view.activeSessionDetail().reviewSource()).isEqualTo(ReviewSource.SESSION);
        assertThat(view.activeSessionDetail().selectedFile()).isNotNull();
        assertThat(view.activeSessionDetail().selectedFile().path()).isEqualTo("second-review-file.txt");
        assertThat(view.activeSessionDetail().changedFiles()).extracting(com.judepereira.jupiter.persistence.Persistence.ChangedFileView::path)
                .containsExactly("second-review-file.txt", "first-review-file.txt");
    }

    @Test
    public void gitReviewReloadIgnoresDeletedSelectedFilesAndKeepsLiveChangedFiles(@TempDir Path projectPath) throws Exception {
        initGitRepo(projectPath);

        AppStateService service = TestAppStateSupport.appStateService();
        service.addOrReopenProject("Alpha", projectPath.toString());
        long sessionId = service.loadViewData().activeSession().id();

        Path liveFile = projectPath.resolve("live-git.txt");
        Path removedFile = projectPath.resolve("removed-git.txt");
        Files.writeString(liveFile, "live change\n");
        Files.writeString(removedFile, "removed change\n");

        service.switchReviewSource(sessionId, ReviewSource.GIT);
        AppStateView initialGitView = service.loadViewData();
        assertThat(initialGitView.activeSessionDetail().reviewSource()).isEqualTo(ReviewSource.GIT);
        assertThat(initialGitView.activeSessionDetail().changedFiles()).extracting(ChangedFileView::path)
                .contains("live-git.txt", "removed-git.txt");

        Files.delete(removedFile);

        AppStateView reloaded = service.loadViewData();
        assertThat(reloaded.activeSessionDetail()).isNotNull();
        assertThat(reloaded.activeSessionDetail().reviewSource()).isEqualTo(ReviewSource.GIT);
        assertThat(reloaded.activeSessionDetail().selectedFile()).isNull();
        assertThat(reloaded.activeSessionDetail().changedFiles()).extracting(ChangedFileView::path)
                .contains("live-git.txt")
                .doesNotContain("removed-git.txt");
    }

    @Test
    public void updateProjectEnvironmentVariablesIgnoreBlankNamesAndKeepLastDuplicate(@TempDir Path projectPath) {
        AppStateService service = TestAppStateSupport.appStateService();
        service.addOrReopenProject("Alpha", projectPath.toString());
        AppStateView view = service.loadViewData();
        long projectId = view.activeProject().id();
        long sessionId = view.activeSession().id();

        service.updateProjectEnvironmentVariables(projectId, List.of(
                new ProjectEnvironmentVariable("API_URL", "https://first.test"),
                new ProjectEnvironmentVariable("", "ignored"),
                new ProjectEnvironmentVariable("API_URL", "https://override.test"),
                new ProjectEnvironmentVariable("FEATURE_FLAG", "true")
        ));

        assertThat(service.loadProjectEnvironmentVariables(projectId))
                .containsEntry("API_URL", "https://override.test")
                .containsEntry("FEATURE_FLAG", "true")
                .doesNotContainKey("");
        assertThat(service.loadSessionProjectEnvironmentVariables(sessionId))
                .containsEntry("API_URL", "https://override.test")
                .containsEntry("FEATURE_FLAG", "true")
                .doesNotContainKey("");
    }

    @Test
    public void mcpServerRoundTripsHeadersAndExposures(@TempDir Path projectPath) {
        AppStateService service = TestAppStateSupport.appStateService();
        service.addOrReopenProject("Alpha", projectPath.toString());
        long projectId = service.loadViewData().activeProject().id();

        McpServerView created = service.createMcpServer("  Local MCP  ", "  http://localhost:3000/mcp  ", true, List.of(
                new McpServerHeader(" Authorization ", "  Bearer token  "),
                new McpServerHeader("Authorization", "Bearer override"),
                new McpServerHeader("X-Trace", " 1 ")
        ), List.of(projectId, projectId));

        assertThat(created.name()).isEqualTo("Local MCP");
        assertThat(created.url()).isEqualTo("http://localhost:3000/mcp");
        assertThat(created.headers()).containsExactly(
                new McpServerHeader("Authorization", "Bearer override"),
                new McpServerHeader("X-Trace", "1")
        );
        assertThat(created.exposedProjectIds()).containsExactly(projectId);

        assertThat(service.loadEnabledMcpServersForProject(projectId)).singleElement().extracting(McpServerView::id).isEqualTo(created.id());
    }

    @Test
    public void reopeningAClosedProjectPreservesMcpServerExposureAssignments(@TempDir Path projectPath) {
        AppStateService service = TestAppStateSupport.appStateService();

        service.addOrReopenProject("Alpha", projectPath.toString());
        AppStateView initial = service.loadViewData();
        long projectId = initial.activeProject().id();

        McpServerView server = service.createMcpServer("Server", "http://localhost:3000", true,
                List.of(new McpServerHeader("Authorization", "Bearer token")), List.of(projectId));
        assertThat(service.loadEnabledMcpServersForProject(projectId)).extracting(McpServerView::id).containsExactly(server.id());

        service.closeProject(projectId);
        service.addOrReopenProject("Alpha", projectPath.toString());

        AppStateView reopened = service.loadViewData();
        long reopenedProjectId = reopened.activeProject().id();
        assertThat(reopenedProjectId).isEqualTo(projectId);
        assertThat(service.loadEnabledMcpServersForProject(reopenedProjectId)).extracting(McpServerView::id).containsExactly(server.id());
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

    @Test
    public void compactionMarksOlderTurnsOutOfModelAndAddsVisibleSummary(@TempDir Path projectPath) {
        AppStateService service = TestAppStateSupport.appStateService();
        service.addOrReopenProject("Alpha", projectPath.toString());
        long sessionId = service.loadViewData().activeSession().id();

        for (int i = 1; i <= 7; i++) {
            String userText = "turn-" + i + " " + "u".repeat(800);
            QueuedChatTurn turn = service.appendUserMessageAndPendingAssistant(sessionId, userText);
            service.completeAssistantMessage(sessionId, turn.assistantMessage().id(), "reply-" + i + " " + "a".repeat(120), List.of());
        }

        AgentDefinition agent = new AgentDefinition("plan", "Plan", "", "Summarize", AgentMode.AGENT, "openai/gpt-5.5", ThinkingLevel.LOW, null, true, true,
                List.of("list_files", "read_file", "search_code", "write_file", "apply_patch", "run_command"));
        var modelCatalog = ModelCatalogTestSupport.modelCatalogService("https://models.dev/catalog.json", """
                {
                  "models": {
                    "openai/gpt-5.5": {
                      "id": "openai/gpt-5.5",
                      "name": "GPT-5.5",
                      "reasoning": true,
                      "tool_call": true,
                      "release_date": "2026-04-23",
                      "limit": {
                        "context": 5000,
                        "output": 500
                      }
                    }
                  }
                }
                """);

        RecordingSummaryClient client = new RecordingSummaryClient();
        ContextCompactionService compactionService = new ContextCompactionService(service,
                new com.judepereira.jupiter.agent.llm.AgentModelClientFactory(null, new com.judepereira.jupiter.agent.config.AgentProperties()) {
                    @Override
                    public com.judepereira.jupiter.agent.llm.AgentModelClient getClient() {
                        return client;
                    }
                }) {
            @Override
            public java.util.Optional<ChatMessageView> compactIfNeeded(long sessionId, AgentDefinition ignoredAgent, ModelDefinition ignoredModel,
                                                                         ThinkingLevel ignoredThinkingLevel, String ignoredWorkspaceRoot, String ignoredUpcomingUserText) {
                service.markTurnsIncludeInModelFalse(sessionId, 5);
                client.chat(List.of(new Message(Message.Role.SYSTEM, "Summarize"), new Message(Message.Role.USER, "transcript")), List.of(),
                        new AgentModelOptions(ignoredModel.id(), ignoredModel.apiModelId(), ignoredThinkingLevel, ignoredModel.supportsReasoning(), ignoredAgent.textVerbosity()));
                return java.util.Optional.of(service.appendVisibleSystemMessage(sessionId, "compact summary", 5L));
            }
        };
        var model = modelCatalog.getRequired(agent.defaultModel());

        ChatMessageView summary = compactionService.compactIfNeeded(sessionId, agent, model, agent.defaultThinkingLevel(), projectPath.toString(),
                "new user turn " + "x".repeat(800)).orElseThrow();

        assertThat(summary.text()).isEqualTo("compact summary");
        assertThat(client.toolCalls).allMatch(List::isEmpty);
        assertThat(client.options).hasSize(1);
        assertThat(client.options.getFirst().modelId()).isEqualTo(model.id());
        assertThat(client.options.getFirst().apiModelId()).isEqualTo(model.apiModelId());
        assertThat(client.options.getFirst().thinkingLevel()).isEqualTo(agent.defaultThinkingLevel());
        assertThat(client.options.getFirst().supportsReasoning()).isEqualTo(model.supportsReasoning());
        assertThat(client.conversations.getFirst()).extracting(Message::getRole)
                .containsExactly(Message.Role.SYSTEM, Message.Role.USER);
        assertThat(service.loadViewData().activeSessionDetail().chatMessages()).extracting(ChatMessageView::text)
                .contains("compact summary");

        assertThat(service.buildConversationHistory(sessionId)).extracting(Message::getContent)
                .contains("Previous conversation summary:\n\ncompact summary")
                .doesNotContain("turn-1 " + "u".repeat(800));
    }

    @Test
    public void compactionKeepsToolCallPairsTogetherInRemainingHistory(@TempDir Path projectPath) {
        AppStateService service = TestAppStateSupport.appStateService();
        service.addOrReopenProject("Alpha", projectPath.toString());
        long sessionId = service.loadViewData().activeSession().id();

        for (int i = 1; i <= 3; i++) {
            String userText = "turn-" + i + " " + "u".repeat(200);
            QueuedChatTurn turn = service.appendUserMessageAndPendingAssistant(sessionId, userText);
            service.completeAssistantMessage(sessionId, turn.assistantMessage().id(), "reply-" + i + " " + "a".repeat(200), List.of());
        }

        QueuedChatTurn toolTurn = service.appendUserMessageAndPendingAssistant(sessionId, "tool turn " + "t".repeat(200));
        ToolCallTraceInput trace = new ToolCallTraceInput("tool-call-1", "write_file",
                Map.of("path", "x.txt", "content", "hello"), true, "wrote x.txt", Map.of("path", "x.txt"));
        service.appendToolCallTrace(sessionId, toolTurn.assistantMessage().id(), trace);
        service.completeAssistantMessage(sessionId, toolTurn.assistantMessage().id(), "tool reply " + "r".repeat(200), List.of(trace));

        for (int i = 5; i <= 6; i++) {
            String userText = "turn-" + i + " " + "u".repeat(200);
            QueuedChatTurn turn = service.appendUserMessageAndPendingAssistant(sessionId, userText);
            service.completeAssistantMessage(sessionId, turn.assistantMessage().id(), "reply-" + i + " " + "a".repeat(200), List.of());
        }

        ContextCompactionService compactionService = new ContextCompactionService(service,
                new com.judepereira.jupiter.agent.llm.AgentModelClientFactory(null, new com.judepereira.jupiter.agent.config.AgentProperties()) {
                    @Override
                    public com.judepereira.jupiter.agent.llm.AgentModelClient getClient() {
                        return new RecordingSummaryClient();
                    }
                }) {
            @Override
            public java.util.Optional<ChatMessageView> compactIfNeeded(long sessionId, AgentDefinition ignoredAgent, ModelDefinition ignoredModel,
                                                                       ThinkingLevel ignoredThinkingLevel, String ignoredWorkspaceRoot, String ignoredUpcomingUserText) {
                service.markTurnsIncludeInModelFalse(sessionId, 3);
                return java.util.Optional.of(service.appendVisibleSystemMessage(sessionId, "compact summary", 3L));
            }
        };
        AgentDefinition agent = new AgentDefinition("plan", "Plan", "", "Summarize", AgentMode.AGENT, "test-model", ThinkingLevel.LOW, null, true, true, List.of());
        ModelDefinition model = new ModelDefinition("test-model", "Test", "test", "test", false, false, 5000, 32, null, null, null);

        compactionService.compactIfNeeded(sessionId, agent, model, agent.defaultThinkingLevel(), projectPath.toString(), "next user " + "q".repeat(20))
                .orElseThrow();

        QueuedChatTurn nextTurn = service.appendUserMessageAndPendingAssistant(sessionId, "after compaction " + "n".repeat(40));
        service.completeAssistantMessage(sessionId, nextTurn.assistantMessage().id(), "after compaction reply", List.of());

        List<Message> history = service.buildConversationHistory(sessionId);
        assertThat(history).extracting(Message::getContent)
                .contains("Previous conversation summary:\n\ncompact summary")
                .doesNotContain("turn-1 " + "u".repeat(200))
                .contains("wrote x.txt");
        assertThat(history).filteredOn(message -> message.getRole() == Message.Role.ASSISTANT && message.getToolCalls() != null && !message.getToolCalls().isEmpty())
                .singleElement()
                .satisfies(message -> assertThat(message.getToolCalls()).extracting(ToolCall::getToolCallId).contains("tool-call-1"));
        assertThat(history).filteredOn(message -> message.getRole() == Message.Role.TOOL)
                .singleElement()
                .extracting(Message::getToolCallId)
                .isEqualTo("tool-call-1");
        assertThat(history).anySatisfy(message -> assertThat(message.getContent()).contains("after compaction"));

        assertThat(history).extracting(Message::getRole).contains(Message.Role.TOOL);
    }

    @Test
    public void midTurnCompactionKeepsCurrentToolTurnInHistory(@TempDir Path projectPath) {
        AppStateService service = TestAppStateSupport.appStateService();
        service.addOrReopenProject("Alpha", projectPath.toString());
        long sessionId = service.loadViewData().activeSession().id();

        for (int i = 1; i <= 3; i++) {
            String userText = "turn-" + i + " " + "u".repeat(120);
            QueuedChatTurn turn = service.appendUserMessageAndPendingAssistant(sessionId, userText);
            service.completeAssistantMessage(sessionId, turn.assistantMessage().id(), "reply-" + i + " " + "a".repeat(80), List.of());
        }

        QueuedChatTurn toolTurn = service.appendUserMessageAndPendingAssistant(sessionId, "tool turn " + "t".repeat(120));
        String hugeOutput = "tool-result-" + "x".repeat(5000);
        ToolCallTraceInput trace = new ToolCallTraceInput("tool-call-1", "write_file",
                Map.of("path", "x.txt", "content", "hello"), true, hugeOutput, Map.of("path", "x.txt"));
        service.appendToolCallTrace(sessionId, toolTurn.assistantMessage().id(), trace);

        ContextCompactionService compactionService = new ContextCompactionService(service,
                new com.judepereira.jupiter.agent.llm.AgentModelClientFactory(null, new com.judepereira.jupiter.agent.config.AgentProperties()) {
                    @Override
                    public AgentModelClient getClient() {
                        return new RecordingSummaryClient();
                    }
                }) {
            @Override
            public java.util.Optional<ChatMessageView> compactIfNeeded(long sessionId, AgentDefinition ignoredAgent, ModelDefinition ignoredModel,
                                                                       ThinkingLevel ignoredThinkingLevel, String ignoredWorkspaceRoot, String ignoredUpcomingUserText) {
                service.markTurnsIncludeInModelFalse(sessionId, 3);
                return java.util.Optional.of(service.appendVisibleSystemMessage(sessionId, "compact summary", 3L));
            }
        };
        AgentDefinition agent = new AgentDefinition("plan", "Plan", "", "Summarize", AgentMode.AGENT, "test-model", ThinkingLevel.LOW, null, true, true, List.of("write_file"));
        ModelDefinition model = new ModelDefinition("test-model", "Test", "test", "test", false, false, 5000, 32, null, null, null);

        compactionService.compactIfNeeded(sessionId, agent, model, agent.defaultThinkingLevel(), projectPath.toString(), null)
                .orElseThrow();

        List<Message> history = service.buildConversationHistory(sessionId);
        assertThat(history).extracting(Message::getContent)
                .contains("Previous conversation summary:\n\ncompact summary")
                .contains("tool turn " + "t".repeat(120))
                .contains(hugeOutput)
                .doesNotContain("turn-1 " + "u".repeat(120))
                .doesNotContain("turn-2 " + "u".repeat(120));
        assertThat(history).extracting(Message::getRole).contains(Message.Role.USER, Message.Role.ASSISTANT, Message.Role.TOOL);
        assertThat(history).anySatisfy(message -> {
            assertThat(message.getRole()).isEqualTo(Message.Role.USER);
            assertThat(message.getContent()).startsWith("Previous conversation summary:\n\n");
        });
    }

    private static AgentModelClientFactory fakeFactory(AgentModelClient client) {
        return new AgentModelClientFactory(null, new com.judepereira.jupiter.agent.config.AgentProperties()) {
            @Override
            public AgentModelClient getClient() {
                return client;
            }
        };
    }

    private static final class RecordingSummaryClient implements AgentModelClient {
        private final List<List<Message>> conversations = new ArrayList<>();
        private final List<List<ToolDefinition>> toolCalls = new ArrayList<>();
        private final List<AgentModelOptions> options = new ArrayList<>();

        @Override
        public ModelResponse chat(List<Message> conversation, List<ToolDefinition> tools) {
            return chat(conversation, tools, null);
        }

        @Override
        public ModelResponse chat(List<Message> conversation, List<ToolDefinition> tools, AgentModelOptions options) {
            conversations.add(List.copyOf(conversation));
            toolCalls.add(List.copyOf(tools));
            this.options.add(options);
            return new ModelResponse("compact summary", null);
        }

        @Override
        public ModelResponse chatStreaming(List<Message> conversation, List<ToolDefinition> tools, AgentModelOptions options,
                                           java.util.function.Consumer<String> onDelta) {
            throw new AssertionError("context compaction should not stream");
        }
    }

    @Test
    public void pendingAssistantMetadataIsPersistedAndRendered(@TempDir Path projectPath) {
        AppStateService service = TestAppStateSupport.appStateService();

        service.addOrReopenProject("Alpha", projectPath.toString());
        long sessionId = service.loadViewData().activeSession().id();

        ChatMessageMetadata metadata = new ChatMessageMetadata("agent-1", "Agent One", "model-1", "HIGH");
        service.appendUserMessageAndPendingAssistant(sessionId, null, null, "hello", metadata);

        ChatMessageView assistant = service.loadViewData().activeSessionDetail().chatMessages().stream()
                .filter(message -> "assistant".equals(message.role()))
                .findFirst()
                .orElseThrow();
        assertThat(assistant.metadata()).isEqualTo(metadata);
        assertThat(service.loadViewData().activeSessionDetail().chatMessages().stream()
                .filter(message -> "assistant".equals(message.role()))
                .findFirst()
                .orElseThrow()
                .metadata()).isEqualTo(metadata);
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
