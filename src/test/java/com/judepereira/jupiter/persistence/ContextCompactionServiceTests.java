package com.judepereira.jupiter.persistence;

import com.judepereira.jupiter.agent.catalog.AgentDefinition;
import com.judepereira.jupiter.agent.catalog.AgentMode;
import com.judepereira.jupiter.agent.catalog.ModelDefinition;
import com.judepereira.jupiter.agent.catalog.ThinkingLevel;
import com.judepereira.jupiter.agent.config.AgentProperties;
import com.judepereira.jupiter.agent.llm.AgentModelClient;
import com.judepereira.jupiter.agent.llm.AgentModelClientFactory;
import com.judepereira.jupiter.agent.llm.AgentModelOptions;
import com.judepereira.jupiter.agent.llm.dto.Message;
import com.judepereira.jupiter.agent.llm.dto.ModelResponse;
import com.judepereira.jupiter.agent.llm.dto.ModelResponseMetadata;
import com.judepereira.jupiter.agent.llm.dto.ToolDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContextCompactionServiceTests {

    @Test
    void compactionUsesStreamingForSummaryGeneration(@TempDir Path projectPath) {
        AppStateService service = TestAppStateSupport.appStateService();
        service.addOrReopenProject("Alpha", projectPath.toString());
        long sessionId = service.loadViewData().activeSession().id();

        for (int i = 1; i <= 8; i++) {
            var turn = service.appendUserMessageAndPendingAssistant(sessionId, "turn-" + i + " " + "u".repeat(15000));
            service.completeAssistantMessage(sessionId, turn.assistantMessage().id(), "reply-" + i + " " + "a".repeat(15000), List.of());
        }

        RecordingStreamingFactory factory = recordingStreamingFactory();
        ContextCompactionService compactionService = new ContextCompactionService(service, factory, null, new com.judepereira.jupiter.agent.harness.SystemPromptComposer());
        AgentDefinition agent = new AgentDefinition("plan", "Plan", "", "Summarize", AgentMode.AGENT, "test-model", ThinkingLevel.LOW, null, true, true,
                List.of("write_file"));
        ModelDefinition model = new ModelDefinition("test-model", "Test", "test", "test", false, true, 50000, 32, null, null, null);

        var summary = compactionService.compactIfNeeded(sessionId, agent, model, agent.defaultThinkingLevel(), projectPath.toString(), "next user " + "x".repeat(250))
                .orElseThrow();

        assertThat(summary.text()).isEqualTo("streamed summary");
        assertThat(factory.client.conversations).hasSize(1);
        assertThat(factory.client.streamed).isTrue();
    }

    @Test
    void toolHeavyConversationWithOnlyTwoTurnsFailsLoudInsteadOfSublistingTooFar(@TempDir Path projectPath) {
        AppStateService service = TestAppStateSupport.appStateService();
        service.addOrReopenProject("Alpha", projectPath.toString());
        long sessionId = service.loadViewData().activeSession().id();

        var firstTurn = service.appendUserMessageAndPendingAssistant(sessionId, "first turn " + "u".repeat(200));
        var trace = new Persistence.ToolCallTraceInput("tool-call-1", "write_file",
                Map.of("path", "x.txt", "content", "hello"), true, "tool result " + "x".repeat(6000), Map.of("path", "x.txt"));
        service.appendToolCallTrace(sessionId, firstTurn.assistantMessage().id(), trace);
        service.completeAssistantMessage(sessionId, firstTurn.assistantMessage().id(), "assistant reply " + "a".repeat(200), List.of(trace));

        var secondTurn = service.appendUserMessageAndPendingAssistant(sessionId, "second turn " + "b".repeat(200));
        service.completeAssistantMessage(sessionId, secondTurn.assistantMessage().id(), "reply 2 " + "c".repeat(200), List.of());

        ContextCompactionService compactionService = new ContextCompactionService(service, failIfUsedFactory(), null, new com.judepereira.jupiter.agent.harness.SystemPromptComposer());
        AgentDefinition agent = new AgentDefinition("plan", "Plan", "", "Summarize", AgentMode.AGENT, "test-model", ThinkingLevel.LOW, null, true, true,
                List.of("write_file"));
        ModelDefinition model = new ModelDefinition("test-model", "Test", "test", "test", false, true, 1200, 32, null, null, null);

        assertThatThrownBy(() -> compactionService.compactIfNeeded(sessionId, agent, model, agent.defaultThinkingLevel(), projectPath.toString(), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("even before compaction")
                .hasMessageContaining(model.id());
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
            public ModelResponse chat(List<Message> conversation, List<ToolDefinition> tools, AgentModelOptions options) {
                throw new AssertionError("context compaction should not reach the model");
            }

            @Override
            public ModelResponse chatStreaming(List<Message> conversation, List<ToolDefinition> tools, AgentModelOptions options,
                                               java.util.function.Consumer<String> onDelta) {
                throw new AssertionError("context compaction should not reach the model");
            }
        };

        return new AgentModelClientFactory(null, new AgentProperties()) {
            @Override
            public AgentModelClient getClient() {
                return client;
            }
        };
    }

    private static final class RecordingStreamingFactory extends AgentModelClientFactory {
        private final RecordingStreamingClient client = new RecordingStreamingClient();

        private RecordingStreamingFactory() {
            super(null, new AgentProperties());
        }

        @Override
        public AgentModelClient getClient() {
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
        public ModelResponse chatStreaming(List<Message> conversation, List<ToolDefinition> tools, AgentModelOptions options,
                                           java.util.function.Consumer<String> onDelta) {
            streamed = true;
            conversations.add(List.copyOf(conversation));
            toolCalls.add(List.copyOf(tools));
            this.options.add(options);
            onDelta.accept("streamed ");
            onDelta.accept("summary");
            return new ModelResponse("streamed summary", null, ModelResponseMetadata.empty());
        }
    }
}
