package com.judepereira.jupiter.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.judepereira.jupiter.agent.catalog.AgentDefinition;
import com.judepereira.jupiter.agent.catalog.AgentMode;
import com.judepereira.jupiter.agent.catalog.ModelDefinition;
import com.judepereira.jupiter.agent.catalog.ThinkingLevel;
import com.judepereira.jupiter.agent.harness.SystemPromptComposer;
import com.judepereira.jupiter.agent.llm.AgentModelClient;
import com.judepereira.jupiter.agent.llm.AgentModelClientFactory;
import com.judepereira.jupiter.agent.llm.AgentModelOptions;
import com.judepereira.jupiter.agent.llm.dto.Message;
import com.judepereira.jupiter.agent.llm.dto.ModelResponse;
import com.judepereira.jupiter.agent.llm.dto.ModelResponseMetadata;
import com.judepereira.jupiter.agent.llm.dto.ToolDefinition;
import com.judepereira.jupiter.testsupport.SkillTestSupport;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

class ContextCompactionServiceTests {

    @Test
    void compactionUsesStreamingForSummaryGeneration(@TempDir Path projectPath) {
        AppStateService service = TestAppStateSupport.appStateService();
        service.addOrReopenProject("Alpha", projectPath.toString());
        long sessionId = service.loadViewData().activeSession().id();

        for (int i = 1; i <= 8; i++) {
            var turn = service.appendUserMessageAndPendingAssistant(sessionId, "turn-" + i + " " + "u".repeat(15000));
            service.completeAssistantMessage(sessionId, turn.assistantMessage().id(),
                    "reply-" + i + " " + "a".repeat(15000), List.of());
        }

        RecordingStreamingFactory factory = recordingStreamingFactory();
        ContextCompactionService compactionService = new ContextCompactionService(service, factory, null,
                new SystemPromptComposer(SkillTestSupport.defaultComponents().renderer()),
                SkillTestSupport.defaultComponents().discovery());
        AgentDefinition agent = new AgentDefinition("plan", "Plan", "", "Summarize", AgentMode.AGENT, "test-model",
                ThinkingLevel.LOW, null, true, true, List.of("write_file"));
        ModelDefinition model = new ModelDefinition("test-model", "Test", "test", "test", false, true, 50000, 32, null,
                null, null, null, null, List.of("text"), List.of("text"));

        var summary = compactionService.compactIfNeeded(sessionId, agent, model, agent.defaultThinkingLevel(),
                projectPath.toString(), "next user " + "x".repeat(250)).orElseThrow();

        assertThat(summary.text()).isEqualTo("streamed summary");
        assertThat(factory.client.conversations).hasSize(1);
        assertThat(factory.client.streamed).isTrue();
    }

    @Test
    void staleConversationIsRejectedBeforeAnyCompactionWrite() {
        AppStateService service = TestAppStateSupport.appStateService();
        service.addOrReopenProject("Alpha", "workspace");
        long sessionId = service.loadViewData().activeSession().id();
        var turn = service.appendUserMessageAndPendingAssistant(sessionId, "first");
        service.completeAssistantMessage(sessionId, turn.assistantMessage().id(), "reply", List.of());
        List<AppStateRepository.ConversationMessageRow> expected = service
                .listIncludedCompletedConversationMessages(sessionId);
        var changedTurn = service.appendUserMessageAndPendingAssistant(sessionId, "changed");
        service.completeAssistantMessage(sessionId, changedTurn.assistantMessage().id(), "reply 2", List.of());

        assertThatThrownBy(() -> service.compactConversationAtomically(sessionId, expected,
                List.of(expected.getFirst().id()), "summary", rows -> {
                    throw new AssertionError("stale input should be rejected before the fit check");
                })).isInstanceOf(IllegalStateException.class).hasMessageContaining("Conversation changed");
        assertThat(service.listConversationMessages(sessionId)).noneMatch(row -> "summary".equals(row.content()));
    }

    @Test
    void failedPostCompactionFitRollsBackCutoffAndSummary() {
        var context = TestAppStateSupport.appStateContext(event -> {
        });
        AppStateService service = context.service();
        service.addOrReopenProject("Alpha", "workspace");
        long sessionId = service.loadViewData().activeSession().id();
        var turn = service.appendUserMessageAndPendingAssistant(sessionId, "first");
        service.completeAssistantMessage(sessionId, turn.assistantMessage().id(), "reply", List.of());
        List<AppStateRepository.ConversationMessageRow> expected = service
                .listIncludedCompletedConversationMessages(sessionId);
        TransactionTemplate transactionTemplate = new TransactionTemplate(
                new DataSourceTransactionManager(context.dataSource()));

        assertThatThrownBy(() -> transactionTemplate
                .executeWithoutResult(status -> service.compactConversationAtomically(sessionId, expected,
                        List.of(expected.getFirst().id()), "summary", rows -> {
                            throw new IllegalStateException("does not fit");
                        })))
                .hasMessage("does not fit");
        assertThat(service.listConversationMessages(sessionId)).noneMatch(row -> "summary".equals(row.content()));
        assertThat(service.listIncludedCompletedConversationMessages(sessionId)).containsExactlyElementsOf(expected);
    }

    @Test
    void toolHeavyConversationWithTooLargeSummarySourceStillFailsLoudly(@TempDir Path projectPath) {
        AppStateService service = TestAppStateSupport.appStateService();
        service.addOrReopenProject("Alpha", projectPath.toString());
        long sessionId = service.loadViewData().activeSession().id();

        var firstTurn = service.appendUserMessageAndPendingAssistant(sessionId, "first turn " + "u".repeat(200));
        var trace = new Persistence.ToolCallTraceInput("tool-call-1", "write_file",
                Map.of("path", "x.txt", "content", "hello"), true, "tool result " + "x".repeat(6000),
                Map.of("path", "x.txt"));
        service.appendToolCallTrace(sessionId, firstTurn.assistantMessage().id(), trace);
        service.completeAssistantMessage(sessionId, firstTurn.assistantMessage().id(),
                "assistant reply " + "a".repeat(200), List.of(trace));

        var secondTurn = service.appendUserMessageAndPendingAssistant(sessionId, "second turn " + "b".repeat(200));
        service.completeAssistantMessage(sessionId, secondTurn.assistantMessage().id(), "reply 2 " + "c".repeat(200),
                List.of());

        ContextCompactionService compactionService = new ContextCompactionService(service, failIfUsedFactory(), null,
                new SystemPromptComposer(SkillTestSupport.defaultComponents().renderer()),
                SkillTestSupport.defaultComponents().discovery());
        AgentDefinition agent = new AgentDefinition("plan", "Plan", "", "Summarize", AgentMode.AGENT, "test-model",
                ThinkingLevel.LOW, null, true, true, List.of("write_file"));
        ModelDefinition model = new ModelDefinition("test-model", "Test", "test", "test", false, true, 1200, 32, null,
                null, null, null, null, List.of("text"), List.of("text"));

        assertThatThrownBy(() -> compactionService.compactIfNeeded(sessionId, agent, model,
                agent.defaultThinkingLevel(), projectPath.toString(), null)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("too large to summarize").hasMessageContaining(model.id());
    }

    @Test
    void largeUpcomingUserTextCannotTriggerCompactionWithoutEligibleHistory(@TempDir Path projectPath) {
        AppStateService service = TestAppStateSupport.appStateService();
        service.addOrReopenProject("Alpha", projectPath.toString());
        long sessionId = service.loadViewData().activeSession().id();
        var compactionService = compactionService(service, failIfUsedFactory());
        AgentDefinition agent = testAgent();
        ModelDefinition model = testModel(5000);

        assertThatThrownBy(() -> compactionService.compactIfNeeded(sessionId, agent, model,
                agent.defaultThinkingLevel(), projectPath.toString(), "upcoming " + "x".repeat(20000)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("even before compaction");
        assertThat(service.listConversationMessages(sessionId)).noneMatch(row -> row.compactedThroughTurnId() != null);
    }

    @Test
    void incompleteToolExchangeFailsBeforeSummaryModelAndWrites(@TempDir Path projectPath) {
        var context = TestAppStateSupport.appStateContext(event -> {
        });
        AppStateService service = context.service();
        service.addOrReopenProject("Alpha", projectPath.toString());
        long sessionId = service.loadViewData().activeSession().id();
        var turn = service.appendUserMessageAndPendingAssistant(sessionId, "request " + "u".repeat(500));
        appendToolPair(service, sessionId, turn.assistantMessage().id(), "orphan", 18000);
        new JdbcTemplate(context.dataSource())
                .update("DELETE FROM conversation_messages WHERE session_id = ? AND role = 'tool'", sessionId);
        List<AppStateRepository.ConversationMessageRow> before = service.listConversationMessages(sessionId);
        List<AppStateRepository.ConversationMessageRow> beforeIncluded = service
                .listIncludedCompletedConversationMessages(sessionId);
        var compactionService = compactionService(service, failIfUsedFactory());
        AgentDefinition agent = testAgent();
        ModelDefinition model = testModel(5000);

        assertThatThrownBy(() -> compactionService.compactIfNeeded(sessionId, agent, model,
                agent.defaultThinkingLevel(), projectPath.toString(), null)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Incomplete tool exchange");
        assertThat(service.listConversationMessages(sessionId)).containsExactlyElementsOf(before);
        assertThat(service.listIncludedCompletedConversationMessages(sessionId))
                .containsExactlyElementsOf(beforeIncluded);
    }

    @Test
    void compactsActiveTurnToolPairsThenRecompactsAndPreservesPendingAssistant(@TempDir Path projectPath) {
        AppStateService service = TestAppStateSupport.appStateService();
        service.addOrReopenProject("Alpha", projectPath.toString());
        long sessionId = service.loadViewData().activeSession().id();
        var turn = service.appendUserMessageAndPendingAssistant(sessionId, "current request " + "u".repeat(1800));
        appendToolPair(service, sessionId, turn.assistantMessage().id(), "old-1", 1800);
        appendToolPair(service, sessionId, turn.assistantMessage().id(), "old-2", 1800);
        appendToolPair(service, sessionId, turn.assistantMessage().id(), "keep-1", 1800);
        appendToolPair(service, sessionId, turn.assistantMessage().id(), "keep-2", 1800);

        RecordingStreamingFactory factory = recordingStreamingFactory();
        ContextCompactionService compactionService = compactionService(service, factory);
        AgentDefinition agent = testAgent();
        ModelDefinition model = testModel(5000);
        compactionService
                .compactIfNeeded(sessionId, agent, model, agent.defaultThinkingLevel(), projectPath.toString(), null)
                .orElseThrow();
        List<Message> afterFirst = service.buildConversationHistory(sessionId);
        assertThat(afterFirst).extracting(Message::getRole).contains(Message.Role.USER, Message.Role.TOOL);
        assertThat(afterFirst.stream()
                .filter(message -> message.getRole() == Message.Role.USER && message.getContent() != null
                        && message.getContent().startsWith("Previous conversation summary:\n\n")))
                .hasSize(1);
        assertThat(service.listConversationMessages(sessionId))
                .anyMatch(row -> row.pending() && row.publicId().equals(turn.assistantMessage().id()));
        assertThat(afterFirst)
                .anyMatch(message -> message.getContent() != null && message.getContent().contains("keep"));

        appendToolPair(service, sessionId, turn.assistantMessage().id(), "latest-1", 500);
        appendToolPair(service, sessionId, turn.assistantMessage().id(), "latest-2", 500);
        appendToolPair(service, sessionId, turn.assistantMessage().id(), "latest-3", 500);
        compactionService
                .compactIfNeeded(sessionId, agent, model, agent.defaultThinkingLevel(), projectPath.toString(), null)
                .orElseThrow();
        List<Message> afterSecond = service.buildConversationHistory(sessionId);
        assertThat(afterSecond.stream()
                .filter(message -> message.getRole() == Message.Role.USER && message.getContent() != null
                        && message.getContent().startsWith("Previous conversation summary:\n\n")))
                .hasSize(1);
        assertThat(afterSecond.getFirst().getRole()).isEqualTo(Message.Role.USER);
        assertThat(factory.client.conversations).hasSize(2);
        assertThat(factory.client.conversations.get(1).getLast().getContent()).contains("streamed summary");
        assertThat(afterSecond.stream()
                .anyMatch(message -> message.getContent() != null && message.getContent().contains("latest"))).isTrue();

        service.completeAssistantMessage(sessionId, turn.assistantMessage().id(), "final response", List.of());
        List<Message> completed = service.buildConversationHistory(sessionId);
        assertThat(completed.stream().filter(message -> "final response".equals(message.getContent()))).hasSize(1);
        assertThat(completed.indexOf(completed.stream().filter(message -> "final response".equals(message.getContent()))
                .findFirst().orElseThrow())).isGreaterThan(
                        completed.indexOf(completed.stream().filter(message -> message.getRole() == Message.Role.TOOL)
                                .reduce((first, second) -> second).orElseThrow()));
        assertThat(service.listConversationMessages(sessionId))
                .anyMatch(row -> "final response".equals(row.content()) && row.showInChat());
    }

    @Test
    void compactsAllResultsInAssistantToolCallBatchTogether(@TempDir Path projectPath) {
        var context = TestAppStateSupport.appStateContext(event -> {
        });
        AppStateService service = context.service();
        service.addOrReopenProject("Alpha", projectPath.toString());
        long sessionId = service.loadViewData().activeSession().id();
        var turn = service.appendUserMessageAndPendingAssistant(sessionId, "request");
        appendToolPair(service, sessionId, turn.assistantMessage().id(), "batch-1", 1800);
        appendToolPair(service, sessionId, turn.assistantMessage().id(), "batch-2", 1800);
        appendToolPair(service, sessionId, turn.assistantMessage().id(), "keep-1", 1800);
        appendToolPair(service, sessionId, turn.assistantMessage().id(), "keep-2", 1800);
        List<AppStateRepository.ConversationMessageRow> rows = service.listConversationMessages(sessionId);
        var firstRequest = rows.stream().filter(row -> row.role().equals("assistant") && !row.pending()
                && !row.showInChat() && row.toolCallsJson() != null).findFirst().orElseThrow();
        var secondRequest = rows.stream().filter(row -> row.role().equals("assistant") && !row.pending()
                && !row.showInChat() && row.toolCallsJson() != null).skip(1).findFirst().orElseThrow();
        context.repository().updateMessageToolCalls(firstRequest.id(),
                """
                        [{"toolCallId":"call-batch-1","toolName":"write_file","arguments":{"path":"batch-1.txt","content":"%s"}},
                         {"toolCallId":"call-batch-2","toolName":"write_file","arguments":{"path":"batch-2.txt","content":"%s"}}]
                        """
                        .formatted("x".repeat(1800), "x".repeat(1800)));
        new JdbcTemplate(context.dataSource()).update("DELETE FROM conversation_messages WHERE id = ?",
                secondRequest.id());

        var factory = recordingStreamingFactory();
        compactionService(service, factory).compactIfNeeded(sessionId, testAgent(), testModel(5000), ThinkingLevel.LOW,
                projectPath.toString(), null).orElseThrow();

        String transcript = factory.client.conversations.getFirst().getLast().getContent();
        assertThat(transcript).contains("call-batch-1", "call-batch-2");
        List<AppStateRepository.ConversationMessageRow> after = service.listConversationMessages(sessionId);
        assertThat(after).filteredOn(row -> row.id() == firstRequest.id()).singleElement()
                .extracting(AppStateRepository.ConversationMessageRow::includeInModel).isEqualTo(false);
        assertThat(after).filteredOn(row -> row.role().equals("tool") && row.toolCallId().startsWith("call-batch"))
                .hasSize(2).allMatch(row -> !row.includeInModel());
        assertThat(after).filteredOn(row -> row.toolCallId() != null && row.toolCallId().startsWith("call-keep"))
                .hasSize(2).allMatch(AppStateRepository.ConversationMessageRow::includeInModel);
    }

    @Test
    void compactsSingleCompletedLargeTurn(@TempDir Path projectPath) {
        AppStateService service = TestAppStateSupport.appStateService();
        service.addOrReopenProject("Alpha", projectPath.toString());
        long sessionId = service.loadViewData().activeSession().id();
        var turn = service.appendUserMessageAndPendingAssistant(sessionId, "large request " + "u".repeat(9000));
        service.completeAssistantMessage(sessionId, turn.assistantMessage().id(), "large answer " + "a".repeat(9000),
                List.of());
        AgentDefinition agent = testAgent();
        var summary = compactionService(service, recordingStreamingFactory()).compactIfNeeded(sessionId, agent,
                testModel(5000), agent.defaultThinkingLevel(), projectPath.toString(), null).orElseThrow();
        assertThat(summary.text()).isEqualTo("streamed summary");
        List<Message> history = service.buildConversationHistory(sessionId);
        assertThat(history.getFirst().getRole()).isEqualTo(Message.Role.USER);
        assertThat(history.getFirst().getContent()).startsWith("Previous conversation summary:\n\n");
    }

    @ParameterizedTest
    @MethodSource("invalidToolCallIds")
    void invalidToolCallIdsFailBeforeModelOrWrites(String toolCallsJson, String expectedMessage,
            @TempDir Path projectPath) {
        var context = TestAppStateSupport.appStateContext(event -> {
        });
        AppStateService service = context.service();
        service.addOrReopenProject("Alpha", projectPath.toString());
        long sessionId = service.loadViewData().activeSession().id();
        var turn = service.appendUserMessageAndPendingAssistant(sessionId, "request");
        appendToolPair(service, sessionId, turn.assistantMessage().id(), "invalid", 18000);
        var request = service
                .listConversationMessages(sessionId).stream().filter(row -> row.role().equals("assistant")
                        && !row.pending() && !row.showInChat() && row.toolCallsJson() != null)
                .findFirst().orElseThrow();
        context.repository().updateMessageToolCalls(request.id(), toolCallsJson);
        List<AppStateRepository.ConversationMessageRow> before = service.listConversationMessages(sessionId);

        assertThatThrownBy(() -> compactionService(service, failIfUsedFactory()).compactIfNeeded(sessionId, testAgent(),
                testModel(5000), ThinkingLevel.LOW, projectPath.toString(), "x".repeat(20000)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining(expectedMessage);
        assertThat(service.listConversationMessages(sessionId)).containsExactlyElementsOf(before);
    }

    static Stream<Arguments> invalidToolCallIds() {
        return Stream.of(Arguments.of("[{\"toolName\":\"write_file\"}]", "Invalid tool call IDs"),
                Arguments.of("[{\"toolCallId\":\" \"}]", "Invalid tool call IDs"),
                Arguments.of("[{\"toolCallId\":\"dup\"},{\"toolCallId\":\"dup\"}]", "Invalid tool call IDs"),
                Arguments.of("{broken", "Failed to read tool call JSON"));
    }

    @Test
    void mismatchedToolExchangeFailsBeforeModelOrWrites(@TempDir Path projectPath) {
        var context = TestAppStateSupport.appStateContext(event -> {
        });
        AppStateService service = context.service();
        service.addOrReopenProject("Alpha", projectPath.toString());
        long sessionId = service.loadViewData().activeSession().id();
        var turn = service.appendUserMessageAndPendingAssistant(sessionId, "request");
        appendToolPair(service, sessionId, turn.assistantMessage().id(), "mismatch", 18000);
        var request = service
                .listConversationMessages(sessionId).stream().filter(row -> row.role().equals("assistant")
                        && !row.pending() && !row.showInChat() && row.toolCallsJson() != null)
                .findFirst().orElseThrow();
        context.repository().updateMessageToolCalls(request.id(),
                "[{\"toolCallId\":\"different\",\"toolName\":\"write_file\"}]");
        List<AppStateRepository.ConversationMessageRow> before = service.listConversationMessages(sessionId);

        assertThatThrownBy(() -> compactionService(service, failIfUsedFactory()).compactIfNeeded(sessionId, testAgent(),
                testModel(5000), ThinkingLevel.LOW, projectPath.toString(), "x".repeat(20000)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Mismatched tool exchange");
        assertThat(service.listConversationMessages(sessionId)).containsExactlyElementsOf(before);
    }

    @Test
    void orphanToolResultFailsBeforeModelOrWrites(@TempDir Path projectPath) {
        var context = TestAppStateSupport.appStateContext(event -> {
        });
        AppStateService service = context.service();
        service.addOrReopenProject("Alpha", projectPath.toString());
        long sessionId = service.loadViewData().activeSession().id();
        var turn = service.appendUserMessageAndPendingAssistant(sessionId, "request");
        appendToolPair(service, sessionId, turn.assistantMessage().id(), "orphan", 18000);
        var request = service
                .listConversationMessages(sessionId).stream().filter(row -> row.role().equals("assistant")
                        && !row.pending() && !row.showInChat() && row.toolCallsJson() != null)
                .findFirst().orElseThrow();
        new JdbcTemplate(context.dataSource()).update("DELETE FROM conversation_messages WHERE id = ?", request.id());
        List<AppStateRepository.ConversationMessageRow> before = service.listConversationMessages(sessionId);

        assertThatThrownBy(() -> compactionService(service, failIfUsedFactory()).compactIfNeeded(sessionId, testAgent(),
                testModel(5000), ThinkingLevel.LOW, projectPath.toString(), "x".repeat(20000)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Orphan tool result");
        assertThat(service.listConversationMessages(sessionId)).containsExactlyElementsOf(before);
    }

    @Test
    void summaryOnlyHistoryCannotMakeCompactionProgress(@TempDir Path projectPath) {
        AppStateService service = TestAppStateSupport.appStateService();
        service.addOrReopenProject("Alpha", projectPath.toString());
        long sessionId = service.loadViewData().activeSession().id();
        service.appendVisibleSystemMessage(sessionId, "old summary " + "s".repeat(18000), 0L);
        List<AppStateRepository.ConversationMessageRow> before = service.listConversationMessages(sessionId);

        assertThatThrownBy(() -> compactionService(service, failIfUsedFactory()).compactIfNeeded(sessionId, testAgent(),
                testModel(5000), ThinkingLevel.LOW, projectPath.toString(), null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("even before compaction");
        assertThat(service.listConversationMessages(sessionId)).containsExactlyElementsOf(before);
    }

    @Test
    void emptyModelRowIdSelectionIsANoOp() {
        var context = TestAppStateSupport.appStateContext(event -> {
        });
        AppStateService service = context.service();
        service.addOrReopenProject("Alpha", "workspace");
        long sessionId = service.loadViewData().activeSession().id();
        var turn = service.appendUserMessageAndPendingAssistant(sessionId, "request");
        service.completeAssistantMessage(sessionId, turn.assistantMessage().id(), "reply", List.of());
        List<AppStateRepository.ConversationMessageRow> before = service.listConversationMessages(sessionId);

        context.repository().updateConversationMessagesIncludeInModelByIds(sessionId, List.of(), false);
        assertThat(service.listConversationMessages(sessionId)).containsExactlyElementsOf(before);
    }

    private static void appendToolPair(AppStateService service, long sessionId, String assistantId, String id,
            int size) {
        var trace = new Persistence.ToolCallTraceInput("call-" + id, "write_file",
                Map.of("path", id + ".txt", "content", "x".repeat(size)), true, "result-" + id + " " + "r".repeat(size),
                Map.of("path", id + ".txt"));
        service.appendToolCallTrace(sessionId, assistantId, trace);
    }

    private static ContextCompactionService compactionService(AppStateService service,
            AgentModelClientFactory factory) {
        return new ContextCompactionService(service, factory, null,
                new SystemPromptComposer(SkillTestSupport.defaultComponents().renderer()),
                SkillTestSupport.defaultComponents().discovery());
    }

    private static AgentDefinition testAgent() {
        return new AgentDefinition("plan", "Plan", "", "Summarize", AgentMode.AGENT, "test-model", ThinkingLevel.LOW,
                null, true, true, List.of("write_file"));
    }

    private static ModelDefinition testModel(int contextTokens) {
        return new ModelDefinition("test-model", "Test", "test", "test", false, true, contextTokens, 32, null, null,
                null, null, null, List.of("text"), List.of("text"));
    }

    private static RecordingStreamingFactory recordingStreamingFactory() {
        return new RecordingStreamingFactory();
    }

    private static AgentModelClientFactory failIfUsedFactory() {
        AgentModelClient client = new AgentModelClient() {
            @Override
            public ModelResponse chat(List<Message> conversation, List<ToolDefinition> tools) {
                throw new AssertionError("context compaction should not reach the model");
            }

            @Override
            public ModelResponse chat(List<Message> conversation, List<ToolDefinition> tools,
                    AgentModelOptions options) {
                throw new AssertionError("context compaction should not reach the model");
            }

            @Override
            public ModelResponse chatStreaming(List<Message> conversation, List<ToolDefinition> tools,
                    AgentModelOptions options, Consumer<String> onDelta) {
                throw new AssertionError("context compaction should not reach the model");
            }
        };

        return new AgentModelClientFactory(null) {
            @Override
            public AgentModelClient getClient(String provider) {
                return client;
            }
        };
    }

    private static final class RecordingStreamingFactory extends AgentModelClientFactory {
        private final RecordingStreamingClient client = new RecordingStreamingClient();

        private RecordingStreamingFactory() {
            super(null);
        }

        @Override
        public AgentModelClient getClient(String provider) {
            return client;
        }
    }

    private static final class RecordingStreamingClient implements AgentModelClient {
        private final List<List<Message>> conversations = new ArrayList<>();
        private final List<List<ToolDefinition>> toolCalls = new ArrayList<>();
        private final List<AgentModelOptions> options = new ArrayList<>();
        private boolean streamed;

        @Override
        public ModelResponse chat(List<Message> conversation, List<ToolDefinition> tools) {
            throw new AssertionError("context compaction should use streaming");
        }

        @Override
        public ModelResponse chat(List<Message> conversation, List<ToolDefinition> tools, AgentModelOptions options) {
            throw new AssertionError("context compaction should use streaming");
        }

        @Override
        public ModelResponse chatStreaming(List<Message> conversation, List<ToolDefinition> tools,
                AgentModelOptions options, Consumer<String> onDelta) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            streamed = true;
            conversations.add(List.copyOf(conversation));
            toolCalls.add(List.copyOf(tools));
            this.options.add(options);
            onDelta.accept("streamed ");
            onDelta.accept("summary");
            return new ModelResponse("streamed summary", null, ModelResponseMetadata.empty(), null);
        }
    }
}
