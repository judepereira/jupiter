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
import com.judepereira.jupiter.agent.llm.dto.ToolSchema;
import com.judepereira.jupiter.agent.mcp.McpProjectMcpServerRuntimeManager;
import com.judepereira.jupiter.agent.mcp.McpProjectToolExecutor;
import com.judepereira.jupiter.agent.mcp.McpProjectToolSnapshot;
import com.judepereira.jupiter.agent.tools.AgentTool;
import com.judepereira.jupiter.agent.tools.ToolExecutionContext;
import com.judepereira.jupiter.agent.tools.ToolExecutionResult;
import com.judepereira.jupiter.agent.tools.ToolRegistry;
import com.judepereira.jupiter.agent.llm.dto.ToolParameter;
import com.judepereira.jupiter.persistence.AppStateService;
import com.judepereira.jupiter.testsupport.ModelCatalogTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CodingAgentHarnessMcpIntegrationTest {

    @Test
    void primaryWildcardReceivesMcpToolsAndExecutesPinnedSnapshot(@TempDir Path tmp) {
        RecordingModel model = new RecordingModel(List.of(
                new ModelResponse(null, new ToolCall(null, "mcp__project__alpha", Map.of()), com.judepereira.jupiter.agent.llm.dto.ModelResponseMetadata.empty()),
                new ModelResponse("done", null, com.judepereira.jupiter.agent.llm.dto.ModelResponseMetadata.empty())
        ));
        ToolRegistry registry = registry(recordingTool("list_files"), recordingTool("task"));
        FakeMcpManager mcpManager = new FakeMcpManager("mcp__project__alpha", "v1", "v2");
        AppStateService appStateService = appStateService(42L);

        CodingAgentHarness harness = new CodingAgentHarness(fakeFactory(model), registry, properties(tmp),
                new AgentDefinitionService(new ObjectMapper()), null, appStateService, null, mcpManager, new SystemPromptComposer());

        AgentTurnResult result = harness.runTurnStreaming(new AgentTurnRequest(
                "sys",
                List.of(new Message(Message.Role.USER, "use mcp", null, null)),
                tmp.toString(),
                "engineer",
                null,
                null,
                42L,
                null
        ), new com.judepereira.jupiter.agent.llm.AgentStreamListener() {
            private boolean switched;

            @Override
            public List<Message> onBeforeModelRequest(AgentTurnRequest request, List<Message> conversation) {
                if (!switched) {
                    switched = true;
                    mcpManager.version.set(2);
                }
                return conversation;
            }
        });

        assertThat(result.getFinalText()).isEqualTo("done");
        assertThat(model.capturedToolNames()).allSatisfy(names -> {
            assertThat(names).contains("list_files", "task", "mcp__project__alpha");
        });
        assertThat(result.getTraces()).singleElement().satisfies(trace -> {
            assertThat(trace.getToolName()).isEqualTo("mcp__project__alpha");
            assertThat(trace.isSuccess()).isTrue();
            assertThat(trace.getTextSummary()).isEqualTo("v1");
        });
        assertThat(mcpManager.snapshotCalls.get()).isEqualTo(1);
    }

    @Test
    void subagentWildcardReceivesMcpToolsButNotTask(@TempDir Path tmp) {
        RecordingModel model = new RecordingModel(List.of(
                new ModelResponse(null, new ToolCall(null, "mcp__project__alpha", Map.of()), com.judepereira.jupiter.agent.llm.dto.ModelResponseMetadata.empty()),
                new ModelResponse("done", null, com.judepereira.jupiter.agent.llm.dto.ModelResponseMetadata.empty())
        ));
        ToolRegistry registry = registry(recordingTool("list_files"), recordingTool("task"));
        FakeMcpManager mcpManager = new FakeMcpManager("mcp__project__alpha", "v1", "v2");
        AppStateService appStateService = appStateService(42L);

        CodingAgentHarness harness = new CodingAgentHarness(fakeFactory(model), registry, properties(tmp),
                new AgentDefinitionService(new ObjectMapper()), null, appStateService, null, mcpManager, new SystemPromptComposer());

        AgentTurnResult result = harness.runTurn(new AgentTurnRequest(
                "sys",
                List.of(new Message(Message.Role.USER, "use mcp", null, null)),
                tmp.toString(),
                "apprentice",
                null,
                null,
                42L,
                null
        ));

        assertThat(result.getFinalText()).isEqualTo("done");
        assertThat(model.capturedToolNames()).allSatisfy(names -> {
            assertThat(names).contains("list_files", "mcp__project__alpha");
            assertThat(names).doesNotContain("task");
        });
        assertThat(result.getTraces()).singleElement().satisfies(trace -> {
            assertThat(trace.getToolName()).isEqualTo("mcp__project__alpha");
            assertThat(trace.isSuccess()).isTrue();
            assertThat(trace.getTextSummary()).isEqualTo("v1");
        });
    }

    @Test
    void explicitBuiltInAllowListDoesNotExposeMcp(@TempDir Path tmp) {
        AgentDefinition agent = new AgentDefinition("custom", "Custom", "", "Custom system prompt", AgentMode.AGENT,
                "openai/gpt-5.5", ThinkingLevel.HIGH, null, false, false, List.of("list_files"));
        RecordingModel model = new RecordingModel(List.of(
                new ModelResponse(null, new ToolCall(null, "mcp__project__alpha", Map.of()), com.judepereira.jupiter.agent.llm.dto.ModelResponseMetadata.empty()),
                new ModelResponse("done", null, com.judepereira.jupiter.agent.llm.dto.ModelResponseMetadata.empty())
        ));
        ToolRegistry registry = registry(recordingTool("list_files"), recordingTool("task"));
        FakeMcpManager mcpManager = new FakeMcpManager("mcp__project__alpha", "v1", "v2");
        AppStateService appStateService = appStateService(42L);

        CodingAgentHarness harness = new CodingAgentHarness(fakeFactory(model), registry, properties(tmp),
                agentService(agent), null, appStateService, null, mcpManager, new SystemPromptComposer());

        AgentTurnResult result = harness.runTurn(new AgentTurnRequest(
                "sys",
                List.of(new Message(Message.Role.USER, "use mcp", null, null)),
                tmp.toString(),
                "custom",
                null,
                null,
                42L,
                null
        ));

        assertThat(result.getFinalText()).isEqualTo("done");
        assertThat(model.capturedToolNames().getFirst()).containsExactly("list_files");
        assertThat(result.getTraces()).singleElement().satisfies(trace -> {
            assertThat(trace.getToolName()).isEqualTo("mcp__project__alpha");
            assertThat(trace.isSuccess()).isFalse();
            assertThat(trace.getTextSummary()).contains("not allowed");
        });
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

    private static AppStateService appStateService(long projectId) {
        AppStateService appStateService = mock(AppStateService.class);
        when(appStateService.loadSessionProjectId(42L)).thenReturn(projectId);
        when(appStateService.loadSessionProjectEnvironmentVariables(42L)).thenReturn(Map.of());
        return appStateService;
    }

    private static ToolRegistry registry(RecordingTool... tools) {
        ToolRegistry registry = new ToolRegistry();
        for (RecordingTool tool : tools) {
            registry.register(tool);
        }
        return registry;
    }

    private static RecordingTool recordingTool(String name) {
        return new RecordingTool(name, new ToolDefinition(name, name + " tool", ToolSchema.object()),
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
            return executor.apply(args, context);
        }
    }

    private static final class FakeMcpManager extends McpProjectMcpServerRuntimeManager {
        private final String toolName;
        private final String v1;
        private final String v2;
        private final AtomicInteger snapshotCalls = new AtomicInteger();
        private final AtomicInteger version = new AtomicInteger(1);

        private FakeMcpManager(String toolName, String v1, String v2) {
            super(null, null, null);
            this.toolName = toolName;
            this.v1 = v1;
            this.v2 = v2;
        }

        @Override
        public McpProjectToolSnapshot snapshot(long projectId) {
            snapshotCalls.incrementAndGet();
            int current = version.get();
            return snapshotFor(current, projectId);
        }

        private McpProjectToolSnapshot snapshotFor(int currentVersion, long projectId) {
            String suffix = currentVersion == 1 ? v1 : v2;
            ToolDefinition definition = new ToolDefinition(toolName, "mcp tool", ToolSchema.object(ToolParameter.string("input", "input")));
            McpProjectToolExecutor executor = new McpProjectToolExecutor() {
                @Override
                public String modelToolName() {
                    return toolName;
                }

                @Override
                public String serverSlug() {
                    return "project";
                }

                @Override
                public String toolSlug() {
                    return "alpha";
                }

                @Override
                public ToolExecutionResult execute(Map<String, Object> args, ToolExecutionContext context) {
                    return new ToolExecutionResult(true, suffix, Map.of("version", currentVersion));
                }
            };
            return new McpProjectToolSnapshot(projectId, List.of(definition), Map.of(toolName, executor));
        }
    }
}
