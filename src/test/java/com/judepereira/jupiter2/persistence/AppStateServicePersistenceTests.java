package com.judepereira.jupiter2.persistence;

import com.judepereira.jupiter2.agent.llm.dto.Message;
import com.judepereira.jupiter2.persistence.Persistence.AppStateView;
import com.judepereira.jupiter2.persistence.Persistence.ChatMessageView;
import com.judepereira.jupiter2.persistence.Persistence.QueuedChatTurn;
import com.judepereira.jupiter2.persistence.Persistence.ToolCallTraceInput;
import com.judepereira.jupiter2.persistence.Persistence.ProjectView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public class AppStateServicePersistenceTests {

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
}
