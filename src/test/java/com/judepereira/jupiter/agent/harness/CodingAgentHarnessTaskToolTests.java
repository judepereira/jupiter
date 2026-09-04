package com.judepereira.jupiter.agent.harness;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.judepereira.jupiter.agent.catalog.AgentDefinition;
import com.judepereira.jupiter.agent.catalog.AgentDefinitionService;
import com.judepereira.jupiter.agent.catalog.AgentMode;
import com.judepereira.jupiter.agent.catalog.ThinkingLevel;
import com.judepereira.jupiter.agent.config.AgentProperties;
import com.judepereira.jupiter.agent.llm.AgentModelClient;
import com.judepereira.jupiter.agent.llm.AgentModelClientFactory;
import com.judepereira.jupiter.agent.llm.AgentModelOptions;
import com.judepereira.jupiter.agent.llm.dto.Message;
import com.judepereira.jupiter.agent.llm.dto.ModelResponse;
import com.judepereira.jupiter.agent.llm.dto.ToolCall;
import com.judepereira.jupiter.agent.llm.dto.ToolDefinition;
import com.judepereira.jupiter.agent.tools.AgentTool;
import com.judepereira.jupiter.agent.tools.ToolExecutionContext;
import com.judepereira.jupiter.agent.tools.ToolExecutionResult;
import com.judepereira.jupiter.agent.tools.ToolRegistry;
import com.judepereira.jupiter.testsupport.ModelCatalogTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;

public class CodingAgentHarnessTaskToolTests {

    @Test
    public void primaryAgentCanExposeTaskToolAndInvokeIt(@TempDir Path tmp) {
        AgentDefinition primary = new AgentDefinition("primary", "Primary", "", "Primary system prompt", AgentMode.AGENT,
                "openai/gpt-5.5", ThinkingLevel.HIGH, null, false, false, List.of("task"));

        RecordingTool taskTool = recordingTool("task");
        RecordingModel model = new RecordingModel(List.of(
                new ModelResponse(null, new ToolCall(null, "task", Map.of(
                        "agentId", "engineer",
                        "requestSummary", "Do the thing",
                        "task", "do the thing",
                        "expectedOutput", "done"
                )), com.judepereira.jupiter.agent.llm.dto.ModelResponseMetadata.empty()),
                new ModelResponse("primary complete", null, com.judepereira.jupiter.agent.llm.dto.ModelResponseMetadata.empty())
        ));

        CodingAgentHarness harness = new CodingAgentHarness(fakeFactory(model), registry(taskTool), properties(tmp), agentService(primary),
                ModelCatalogTestSupport.modelCatalogService(), null, null, null, new SystemPromptComposer());

        AgentTurnResult result = harness.runTurn(new AgentTurnRequest(
                "Primary system prompt",
                List.of(new Message(Message.Role.USER, "hello", null, null)),
                tmp.toString(),
                "primary",
                null,
                null
        , null, null));

        assertThat(model.capturedToolNames()).hasSize(2).allMatch(names -> names.contains("task"));
        assertThat(taskTool.executions).isEqualTo(1);
        assertThat(result.getFinalText()).isEqualTo("primary complete");
        assertThat(result.getTraces()).hasSize(1);
        assertThat(result.getTraces().get(0).getToolName()).isEqualTo("task");
        assertThat(result.getTraces().get(0).isSuccess()).isTrue();
    }

    @Test
    public void subagentDoesNotReceiveTaskToolAndBlockedTaskCallsFail(@TempDir Path tmp) {
        AgentDefinition subagent = new AgentDefinition("engineer", "Engineer", "", "Subagent system prompt", AgentMode.SUBAGENT,
                "openai/gpt-5.5", ThinkingLevel.MEDIUM, "low", true, true, List.of("task"));

        RecordingTool taskTool = recordingTool("task");
        RecordingModel model = new RecordingModel(List.of(
                new ModelResponse(null, new ToolCall(null, "task", Map.of(
                        "agentId", "explore",
                        "requestSummary", "Recursively call task",
                        "task", "recursively call task",
                        "expectedOutput", "never"
                )), com.judepereira.jupiter.agent.llm.dto.ModelResponseMetadata.empty()),
                new ModelResponse("subagent complete", null, com.judepereira.jupiter.agent.llm.dto.ModelResponseMetadata.empty())
        ));

        CodingAgentHarness harness = new CodingAgentHarness(fakeFactory(model), registry(taskTool), properties(tmp), agentService(subagent),
                ModelCatalogTestSupport.modelCatalogService(), null, null, null, new SystemPromptComposer());

        AgentTurnResult result = harness.runTurn(new AgentTurnRequest(
                "Subagent system prompt",
                List.of(new Message(Message.Role.USER, "hello", null, null)),
                tmp.toString(),
                "engineer",
                null,
                null
        , null, null));

        assertThat(model.capturedToolNames()).hasSize(2).allMatch(List::isEmpty);
        assertThat(taskTool.executions).isZero();
        assertThat(result.getFinalText()).isEqualTo("subagent complete");
        assertThat(result.getTraces()).hasSize(1);
        assertThat(result.getTraces().get(0).getToolName()).isEqualTo("task");
        assertThat(result.getTraces().get(0).isSuccess()).isFalse();
    }

    private static AgentModelClientFactory fakeFactory(AgentModelClient client) {
        return new AgentModelClientFactory(null, new AgentProperties()) {
            @Override
            public AgentModelClient getClient() {
                return client;
            }
        };
    }

    private static AgentProperties properties(Path workspaceRoot) {
        AgentProperties props = new AgentProperties();
        props.setWorkspaceRoot(workspaceRoot.toString());
        props.setMaxIterations(3);
        return props;
    }

    private static ToolRegistry registry(RecordingTool taskTool) {
        ToolRegistry registry = new ToolRegistry();
        registry.register(taskTool);
        return registry;
    }

    private static RecordingTool recordingTool(String name) {
        return new RecordingTool(name, new ToolDefinition(name, name + " tool", com.judepereira.jupiter.agent.llm.dto.ToolSchema.object()),
                (args, context) -> new ToolExecutionResult(true, name + " executed", Map.of()));
    }

    private static AgentDefinitionService agentService(AgentDefinition agent) {
        return new AgentDefinitionService(new ObjectMapper()) {
            @Override
            public List<AgentDefinition> list() {
                return List.of(agent);
            }

            @Override
            public List<AgentDefinition> listPrimaryAgents() {
                return agent.mode() == AgentMode.AGENT ? List.of(agent) : List.of();
            }

            @Override
            public List<AgentDefinition> listSubagents() {
                return agent.mode() == AgentMode.SUBAGENT ? List.of(agent) : List.of();
            }

            @Override
            public AgentDefinition defaultAgent() {
                return agent;
            }

            @Override
            public AgentDefinition getRequired(String id) {
                return agent;
            }
        };
    }

    private static final class RecordingModel implements AgentModelClient {
        private final List<ModelResponse> responses;
        private final List<List<Message>> capturedConversations = new ArrayList<>();
        private final List<List<ToolDefinition>> capturedToolDefinitions = new ArrayList<>();
        private int index;

        private RecordingModel(List<ModelResponse> responses) {
            this.responses = List.copyOf(responses);
        }

        @Override
        public ModelResponse chat(List<Message> conversation, List<ToolDefinition> tools) {
            return next(conversation, tools);
        }

        @Override
        public ModelResponse chatStreaming(List<Message> conversation, List<ToolDefinition> tools, AgentModelOptions options,
                                           java.util.function.Consumer<String> onDelta) {
            return next(conversation, tools);
        }

        private ModelResponse next(List<Message> conversation, List<ToolDefinition> tools) {
            capturedConversations.add(List.copyOf(conversation));
            capturedToolDefinitions.add(List.copyOf(tools));
            if (index >= responses.size()) {
                return new ModelResponse("", null, com.judepereira.jupiter.agent.llm.dto.ModelResponseMetadata.empty());
            }
            return responses.get(index++);
        }

        private List<List<String>> capturedToolNames() {
            return capturedToolDefinitions.stream().map(defs -> defs.stream().map(ToolDefinition::getName).toList()).toList();
        }
    }

    private static final class RecordingTool implements AgentTool {
        private final String name;
        private final ToolDefinition definition;
        private final BiFunction<Map<String, Object>, ToolExecutionContext, ToolExecutionResult> executor;
        private int executions;

        private RecordingTool(String name, ToolDefinition definition,
                              BiFunction<Map<String, Object>, ToolExecutionContext, ToolExecutionResult> executor) {
            this.name = name;
            this.definition = definition;
            this.executor = executor;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public ToolDefinition definition() {
            return definition;
        }

        @Override
        public ToolExecutionResult execute(Map<String, Object> args, ToolExecutionContext context) {
            executions++;
            return executor.apply(args, context);
        }
    }
}
