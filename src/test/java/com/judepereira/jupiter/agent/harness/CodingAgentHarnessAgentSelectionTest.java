package com.judepereira.jupiter.agent.harness;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.judepereira.jupiter.agent.catalog.AgentDefinitionService;
import com.judepereira.jupiter.agent.catalog.AgentMode;
import com.judepereira.jupiter.agent.catalog.ThinkingLevel;
import com.judepereira.jupiter.agent.config.AgentProperties;
import com.judepereira.jupiter.agent.llm.AgentModelClient;
import com.judepereira.jupiter.agent.llm.AgentModelClientFactory;
import com.judepereira.jupiter.agent.llm.AgentModelOptions;
import com.judepereira.jupiter.agent.llm.dto.Message;
import com.judepereira.jupiter.agent.llm.dto.ModelResponse;
import com.judepereira.jupiter.agent.llm.dto.ToolDefinition;
import com.judepereira.jupiter.agent.llm.dto.ToolSchema;
import com.judepereira.jupiter.agent.tools.AgentTool;
import com.judepereira.jupiter.agent.tools.ToolExecutionContext;
import com.judepereira.jupiter.agent.tools.ToolExecutionResult;
import com.judepereira.jupiter.agent.tools.ToolRegistry;
import com.judepereira.jupiter.testsupport.SystemPromptTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.judepereira.jupiter.testsupport.ModelCatalogTestSupport;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;

public class CodingAgentHarnessAgentSelectionTest {

    @Test
    public void planExposesOnlyReadToolsAndBlocksMutatingToolCalls(@TempDir Path tmp) {
        RecordingTool listFiles = recordingTool("list_files");
        RecordingTool readFile = recordingTool("read_file");
        RecordingTool searchCode = recordingTool("search_code");
        RecordingTool writeFile = recordingTool("write_file");
        RecordingTool applyPatch = recordingTool("apply_patch");
        RecordingTool runCommand = recordingTool("run_command");

        RecordingModel model = new RecordingModel(List.of(
                new ModelResponse(null, new com.judepereira.jupiter.agent.llm.dto.ToolCall("write_file",
                        Map.of("path", "blocked.txt", "content", "nope"))),
                new ModelResponse("finished", null)
        ));

        AgentProperties props = properties(tmp, true, true);
        AgentDefinitionService agentDefinitions = new AgentDefinitionService(new ObjectMapper());
        CodingAgentHarness harness = new CodingAgentHarness(fakeFactory(model), registry(listFiles, readFile, searchCode, writeFile, applyPatch, runCommand), props,
                agentDefinitions, ModelCatalogTestSupport.modelCatalogService());

        AgentTurnResult result = harness.runTurn(new AgentTurnRequest(
                "You are Plan.",
                List.of(new Message(Message.Role.USER, "make changes")),
                tmp.toString(),
                "plan",
                "openai/gpt-5.5",
                ThinkingLevel.HIGH
        ));

        assertThat(result.getFinalText()).isEqualTo("finished");
        assertThat(model.capturedToolNames().get(0)).containsExactlyInAnyOrder("list_files", "read_file", "search_code");
        assertThat(model.capturedConversations().get(0).get(0).getContent())
                .satisfies(system -> assertSystemPrompt(system, agentDefinitions.getRequired("plan").systemPrompt(), tmp));
        assertThat(model.capturedOptions().get(0).apiModelId()).isEqualTo("gpt-5.5");
        assertThat(model.capturedOptions().get(0).thinkingLevel()).isEqualTo(ThinkingLevel.HIGH);
        assertThat(writeFile.executions).isZero();
        assertThat(applyPatch.executions).isZero();
        assertThat(runCommand.executions).isZero();
        assertThat(result.getTraces()).hasSize(1);
        assertThat(result.getTraces().get(0).getToolName()).isEqualTo("write_file");
        assertThat(result.getTraces().get(0).isSuccess()).isFalse();
        assertThat(tmp.resolve("blocked.txt")).doesNotExist();
    }

    @Test
    public void engineerExposesFullToolSetAndUsesWriteAndCommandPermissions(@TempDir Path tmp) {
        RecordingTool listFiles = recordingTool("list_files");
        RecordingTool readFile = recordingTool("read_file");
        RecordingTool searchCode = recordingTool("search_code");
        RecordingTool writeFile = recordingTool("write_file");
        RecordingTool applyPatch = recordingTool("apply_patch");
        RecordingTool runCommand = recordingTool("run_command", (args, context) -> new ToolExecutionResult(
                true,
                "ran",
                Map.of("allowWrite", context.isAllowWrite(), "allowCommand", context.isAllowCommand())
        ));

        RecordingModel model = new RecordingModel(List.of(
                new ModelResponse(null, new com.judepereira.jupiter.agent.llm.dto.ToolCall("run_command",
                        Map.of("command", "echo hi"))),
                new ModelResponse("done", null)
        ));

        AgentProperties props = properties(tmp, false, false);
        AgentDefinitionService agentDefinitions = new AgentDefinitionService(new ObjectMapper());
        CodingAgentHarness harness = new CodingAgentHarness(fakeFactory(model), registry(listFiles, readFile, searchCode, writeFile, applyPatch, runCommand), props,
                agentDefinitions, ModelCatalogTestSupport.modelCatalogService());

        AgentTurnResult result = harness.runTurn(new AgentTurnRequest(
                "You are Engineer.",
                List.of(new Message(Message.Role.USER, "run a command")),
                tmp.toString(),
                "engineer",
                null,
                null
        ));

        assertThat(result.getFinalText()).isEqualTo("done");
        assertThat(model.capturedToolNames().get(0)).containsExactlyInAnyOrder(
                "list_files", "read_file", "search_code", "write_file", "apply_patch", "run_command");
        assertThat(model.capturedConversations().get(0).get(0).getContent())
                .satisfies(system -> assertSystemPrompt(system, agentDefinitions.getRequired("engineer").systemPrompt(), tmp));
        assertThat(model.capturedOptions().get(0).modelId()).isEqualTo("openai/gpt-5.5");
        assertThat(model.capturedOptions().get(0).apiModelId()).isEqualTo("gpt-5.5");
        assertThat(model.capturedOptions().get(0).thinkingLevel()).isEqualTo(ThinkingLevel.HIGH);
        assertThat(runCommand.executions).isEqualTo(1);
        assertThat(runCommand.lastContext).isNotNull();
        assertThat(runCommand.lastContext.isAllowWrite()).isTrue();
        assertThat(runCommand.lastContext.isAllowCommand()).isTrue();
        assertThat(result.getTraces()).hasSize(1);
        assertThat(result.getTraces().get(0).getToolName()).isEqualTo("run_command");
        assertThat(result.getTraces().get(0).isSuccess()).isTrue();
    }

    private static AgentModelClientFactory fakeFactory(AgentModelClient client) {
        return new AgentModelClientFactory(null, new AgentProperties()) {
            @Override
            public AgentModelClient getClient() {
                return client;
            }
        };
    }

    private static AgentProperties properties(Path workspaceRoot, boolean allowWrite, boolean allowCommand) {
        AgentProperties props = new AgentProperties();
        props.setWorkspaceRoot(workspaceRoot.toString());
        props.setMaxIterations(3);
        props.getTooling().setAllowWrite(allowWrite);
        props.getTooling().setAllowCommand(allowCommand);
        return props;
    }

    private static ToolRegistry registry(RecordingTool... tools) {
        ToolRegistry registry = new ToolRegistry();
        for (RecordingTool tool : tools) {
            registry.register(tool);
        }
        return registry;
    }

    private static RecordingTool recordingTool(String name) {
        return recordingTool(name, (args, context) -> new ToolExecutionResult(
                true,
                name + " executed",
                Map.of("allowWrite", context.isAllowWrite(), "allowCommand", context.isAllowCommand())
        ));
    }

    private static RecordingTool recordingTool(String name, BiFunction<Map<String, Object>, ToolExecutionContext, ToolExecutionResult> executor) {
        return new RecordingTool(name, new ToolDefinition(name, name + " tool", ToolSchema.object()), executor);
    }

    private static void assertSystemPrompt(String actual, String appendage, Path workspaceRoot) {
        assertThat(actual).isEqualTo(SystemPromptTestSupport.composeExpected(appendage, workspaceRoot));
    }

    private static final class RecordingModel implements AgentModelClient {
        private final List<ModelResponse> responses;
        private final List<List<Message>> capturedConversations = new ArrayList<>();
        private final List<List<ToolDefinition>> capturedToolDefinitions = new ArrayList<>();
        private final List<AgentModelOptions> capturedOptions = new ArrayList<>();
        private int index;

        private RecordingModel(List<ModelResponse> responses) {
            this.responses = List.copyOf(responses);
        }

        @Override
        public ModelResponse chat(List<Message> conversation, List<ToolDefinition> tools) {
            return next(conversation, tools, null);
        }

        @Override
        public ModelResponse chatStreaming(List<Message> conversation, List<ToolDefinition> tools, AgentModelOptions options,
                                           java.util.function.Consumer<String> onDelta) {
            return next(conversation, tools, options);
        }

        private ModelResponse next(List<Message> conversation, List<ToolDefinition> tools, AgentModelOptions options) {
            capturedConversations.add(List.copyOf(conversation));
            capturedToolDefinitions.add(List.copyOf(tools));
            capturedOptions.add(options);
            if (index >= responses.size()) {
                return new ModelResponse("", null);
            }
            return responses.get(index++);
        }

        private List<List<Message>> capturedConversations() {
            return capturedConversations;
        }

        private List<List<String>> capturedToolNames() {
            return capturedToolDefinitions.stream().map(defs -> defs.stream().map(ToolDefinition::getName).toList()).toList();
        }

        private List<AgentModelOptions> capturedOptions() {
            return capturedOptions;
        }
    }

    private static final class RecordingTool implements AgentTool {
        private final String name;
        private final ToolDefinition definition;
        private final BiFunction<Map<String, Object>, ToolExecutionContext, ToolExecutionResult> executor;
        private int executions;
        private ToolExecutionContext lastContext;

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
            lastContext = context;
            return executor.apply(args, context);
        }
    }
}
