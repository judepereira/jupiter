package com.judepereira.jupiter.agent.harness;

import com.judepereira.jupiter.agent.catalog.AgentDefinition;
import com.judepereira.jupiter.agent.catalog.AgentDefinitionService;
import com.judepereira.jupiter.agent.catalog.AgentMode;
import com.judepereira.jupiter.agent.catalog.ModelCatalogService;
import com.judepereira.jupiter.agent.catalog.ModelDefinition;
import com.judepereira.jupiter.agent.catalog.ThinkingLevel;
import com.judepereira.jupiter.agent.config.AgentProperties;
import com.judepereira.jupiter.agent.llm.AgentModelOptions;
import com.judepereira.jupiter.agent.llm.AgentModelClient;
import com.judepereira.jupiter.agent.llm.AgentStreamListener;
import com.judepereira.jupiter.agent.llm.AgentModelClientFactory;
import com.judepereira.jupiter.agent.mcp.McpProjectMcpServerRuntimeManager;
import com.judepereira.jupiter.agent.mcp.McpProjectToolExecutor;
import com.judepereira.jupiter.agent.mcp.McpProjectToolSnapshot;
import com.judepereira.jupiter.agent.llm.dto.Message;
import com.judepereira.jupiter.agent.llm.dto.ModelResponse;
import com.judepereira.jupiter.agent.llm.dto.ToolCall;
import com.judepereira.jupiter.agent.llm.dto.ToolDefinition;
import com.judepereira.jupiter.agent.tools.ToolExecutionContext;
import com.judepereira.jupiter.agent.tools.ToolExecutionResult;
import com.judepereira.jupiter.agent.harness.StreamCancelledException;
import com.judepereira.jupiter.agent.tools.ToolProgressSink;
import com.judepereira.jupiter.agent.tools.ToolRegistry;
import com.judepereira.jupiter.persistence.AppStateService;
import com.judepereira.jupiter.persistence.TokenUsageService;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class CodingAgentHarness {

    private final AgentModelClientFactory modelFactory;
    private final ToolRegistry registry;
    private final AgentProperties props;
    private final AgentDefinitionService agentDefinitionService;
    private final ModelCatalogService modelCatalogService;
    private final AppStateService appStateService;
    private final TokenUsageService tokenUsageService;
    private final McpProjectMcpServerRuntimeManager mcpRuntimeManager;
    private final SystemPromptComposer systemPromptComposer;




    @Autowired
    public CodingAgentHarness(AgentModelClientFactory modelFactory, ToolRegistry registry, AgentProperties props,
                              AgentDefinitionService agentDefinitionService, ModelCatalogService modelCatalogService,
                              AppStateService appStateService, TokenUsageService tokenUsageService,
                              McpProjectMcpServerRuntimeManager mcpRuntimeManager,
                              SystemPromptComposer systemPromptComposer) {
        this.modelFactory = modelFactory;
        this.registry = registry;
        this.props = props;
        this.agentDefinitionService = agentDefinitionService;
        this.modelCatalogService = modelCatalogService;
        this.appStateService = appStateService;
        this.tokenUsageService = tokenUsageService;
        this.mcpRuntimeManager = mcpRuntimeManager;
        this.systemPromptComposer = systemPromptComposer;
    }

    public AgentTurnResult runTurn(AgentTurnRequest request) {
        return runTurnStreaming(request, new AgentStreamListener() {});
    }

    public AgentTurnResult runTurnStreaming(AgentTurnRequest request, AgentStreamListener listener) {
        AgentModelClient model = modelFactory.getClient();

        AgentDefinition agent = resolveAgent(request);
        ModelDefinition selectedModel = resolveModel(request, agent);
        ThinkingLevel thinkingLevel = resolveThinkingLevel(request, agent);
        AgentModelOptions modelOptions = selectedModel == null ? null : new AgentModelOptions(
                selectedModel.id(), selectedModel.apiModelId(), thinkingLevel, selectedModel.supportsReasoning(),
                agent == null ? null : agent.textVerbosity());

        String systemPrompt = resolveSystemPrompt(request, agent);
        List<Message> convo = new ArrayList<>(seedConversation(systemPrompt, request.getConversationHistory()));

        List<ToolCallTrace> traces = new ArrayList<>();

        int max = props.getMaxIterations();
        String workspaceRoot = request.getWorkspaceRoot() == null || request.getWorkspaceRoot().isBlank()
                ? props.getWorkspaceRoot()
                : request.getWorkspaceRoot();
        Map<String, String> environmentVariables = resolveEnvironmentVariables(request.getSessionId());
        ToolExecutionContext execCtxTemplate = new ToolExecutionContext(Path.of(workspaceRoot),
                agent != null ? agent.allowWrite() : props.getTooling().isAllowWrite(),
                agent != null ? agent.allowCommand() : props.getTooling().isAllowCommand(),
                props.getCommandTimeoutSeconds(),
                request.getSessionId(),
                request.getAgentId(),
                agent == null ? null : agent.mode(),
                null,
                environmentVariables,
                ToolProgressSink.noop(), null);

        long projectId = resolveProjectId(request.getSessionId());
        McpProjectToolSnapshot mcpSnapshot = resolveMcpSnapshot(projectId);

        Set<String> allowedTools = resolveAllowedTools(agent);
        List<ToolDefinition> defs = resolveToolDefinitions(allowedTools, mcpSnapshot);

        StringBuilder accumulated = new StringBuilder();
        CancellationToken cancellationToken = request.getCancellationToken();

        try {
            for (int i = 0; i < max; i++) {
                throwIfCancelled(cancellationToken);
                List<Message> preparedConversation = listener.onBeforeModelRequest(request, List.copyOf(convo));
                if (preparedConversation == null) {
                    throw new IllegalStateException("Listener returned null conversation before model request");
                }
                convo = new ArrayList<>(seedConversation(systemPrompt, preparedConversation));

                ModelResponse resp = model.chatStreaming(convo, defs, modelOptions, delta -> {
                    throwIfCancelled(cancellationToken);
                    if (delta != null) {
                        accumulated.append(delta);
                        listener.onTextDelta(delta);
                    }
                });
                if (tokenUsageService != null && appStateService != null && request.getSessionId() != null) {
                    String usageModelKey = modelOptions == null ? request.getModelId() : modelOptions.modelId();
                    if (usageModelKey == null || usageModelKey.isBlank()) {
                        usageModelKey = props.getModel();
                    }
                    tokenUsageService.recordModelResponse(request.getSessionId(), usageModelKey, "harness", resp);
                }

                ToolCall call = resp.getToolCall();
                String assistantText = resp.getAssistantText();

                if (assistantText == null || assistantText.isEmpty()) {
                    // When using OpenAI through the codex backend, this field is always empty. However,
                    // we know that the streaming request is completed when the call above returns, so we're
                    // good to assume that the assistantText is the accumulated text itself.
                    assistantText = accumulated.toString();
                }

                if (call != null) {
                    String toolName = call.getToolName();
                    String resolvedToolName = (toolName == null || toolName.isBlank()) ? "(missing_tool_name)" : toolName;
                    Map<String, Object> args = call.getArguments() == null ? Map.of() : call.getArguments();
                    String toolCallId = normalizeToolCallId(call.getToolCallId(), i, 0);

                    convo.add(new Message(Message.Role.ASSISTANT, null, null,
                            List.of(new ToolCall(toolCallId, resolvedToolName, args))));

                    if (toolName == null || toolName.isBlank()) {
                        String toolMsg = "[tool_error] Tool call missing tool name";
                        convo.add(new Message(Message.Role.TOOL, toolMsg, toolCallId, null));
                        ToolCallTrace trace = new ToolCallTrace(toolCallId, resolvedToolName, args, false, toolMsg,
                                Map.of("error", "tool name missing"));
                        traces.add(trace);
                        listener.onToolCallTrace(trace);
                        listener.onStatus("tool_error:missing_tool_name");
                        continue;
                    }
                    if (!isToolAllowed(toolName, allowedTools)) {
                        String toolMsg = "[tool_error] Tool not allowed for selected agent: " + toolName;
                        convo.add(new Message(Message.Role.TOOL, toolMsg, toolCallId, null));
                        ToolCallTrace trace = new ToolCallTrace(toolCallId, toolName, args, false, toolMsg,
                                Map.of("error", "tool not allowed"));
                        traces.add(trace);
                        listener.onToolCallTrace(trace);
                        listener.onStatus("tool_error:not_allowed:" + toolName);
                        continue;
                    }
                    listener.onStatus("calling_tool:" + toolName);
                    listener.onToolCallStarted(new ToolCallTrace(toolCallId, toolName, args, false, "", Map.of()));
                    try {
                        throwIfCancelled(cancellationToken);
                        ToolExecutionContext execCtx = new ToolExecutionContext(execCtxTemplate.getWorkspaceRoot(),
                                execCtxTemplate.isAllowWrite(),
                                execCtxTemplate.isAllowCommand(),
                                execCtxTemplate.getCommandTimeoutSeconds(),
                                execCtxTemplate.getSessionId(),
                                execCtxTemplate.getAgentId(),
                                execCtxTemplate.getAgentMode(),
                                toolCallId,
                                execCtxTemplate.getEnvironmentVariables(),
                                (eventName, payload) -> listener.onToolCallProgress(toolCallId, toolName, eventName, payload),
                                cancellationToken);
                        ToolExecutionResult result = executeTool(toolName, args, execCtx, mcpSnapshot);
                        String toolText = result.getText() == null ? "" : result.getText();
                        convo.add(new Message(Message.Role.TOOL, toolText, toolCallId, null));
                        ToolCallTrace trace = new ToolCallTrace(toolCallId, toolName, args, result.isSuccess(), result.getText(), result.getMachine());
                        traces.add(trace);
                        listener.onToolCallTrace(trace);
                        listener.onStatus("tool_result:" + toolName);
                    } catch (StreamCancelledException e) {
                        throw e;
                    } catch (IllegalArgumentException e) {
                        String toolMsg = "[tool_error] Unknown tool: " + toolName;
                        convo.add(new Message(Message.Role.TOOL, toolMsg, toolCallId, null));
                        ToolCallTrace trace = new ToolCallTrace(toolCallId, toolName, args, false, toolMsg, Map.of("error", e.getMessage()));
                        traces.add(trace);
                        listener.onToolCallTrace(trace);
                        listener.onStatus("tool_error:" + toolName);
                    } catch (Exception e) {
                        String toolMsg = "[tool_error] " + e.getMessage();
                        convo.add(new Message(Message.Role.TOOL, toolMsg, toolCallId, null));
                        ToolCallTrace trace = new ToolCallTrace(toolCallId, toolName, args, false, toolMsg, Map.of("exception", e.toString()));
                        traces.add(trace);
                        listener.onToolCallTrace(trace);
                        listener.onStatus("tool_exception:" + toolName);
                    }
                    // continue loop
                } else if (assistantText != null && !assistantText.isBlank()) {
                    throwIfCancelled(cancellationToken);
                    AgentTurnResult result = new AgentTurnResult(accumulated.length() == 0 ? assistantText : accumulated.toString(), traces);
                    listener.onComplete(result);
                    return result;
                } else {
                    // no tool call and no text; continue
                }
            }

            // max iterations reached
            String fallback = "Agent reached max iterations without producing a final answer.";
            AgentTurnResult result = new AgentTurnResult(fallback, traces);
            listener.onComplete(result);
            return result;
        } catch (StreamCancelledException e) {
            throw e;
        } catch (Exception e) {
            listener.onError(e);
            throw e;
        }
    }

    private static void throwIfCancelled(CancellationToken cancellationToken) {
        if (cancellationToken != null) {
            cancellationToken.throwIfCancelled();
        }
    }

    private static String normalizeToolCallId(String toolCallId, int iteration, int toolIndex) {
        if (toolCallId != null && !toolCallId.isBlank()) {
            return toolCallId;
        }
        return "tool-" + iteration + "-" + toolIndex;
    }

    private AgentDefinition resolveAgent(AgentTurnRequest request) {
        if (agentDefinitionService == null) {
            return null;
        }
        if (request.getAgentId() == null || request.getAgentId().isBlank()) {
            return agentDefinitionService.defaultAgent();
        }
        return agentDefinitionService.getRequired(request.getAgentId());
    }

    private ModelDefinition resolveModel(AgentTurnRequest request, AgentDefinition agent) {
        if (modelCatalogService == null) {
            return null;
        }
        String requestedModelId = request.getModelId();
        if (requestedModelId == null || requestedModelId.isBlank()) {
            requestedModelId = agent == null ? props.getModel() : agent.defaultModel();
        }
        return modelCatalogService.getRequired(requestedModelId);
    }

    private ThinkingLevel resolveThinkingLevel(AgentTurnRequest request, AgentDefinition agent) {
        if (request.getThinkingLevel() != null) {
            return request.getThinkingLevel();
        }
        return agent == null ? null : agent.defaultThinkingLevel();
    }

    private String resolveSystemPrompt(AgentTurnRequest request, AgentDefinition agent) {
        if (agent == null) {
            String workspaceRoot = request.getWorkspaceRoot() == null || request.getWorkspaceRoot().isBlank()
                    ? props.getWorkspaceRoot()
                    : request.getWorkspaceRoot();
            return systemPromptComposer.compose(request.getSystemPrompt(), workspaceRoot);
        }
        String workspaceRoot = request.getWorkspaceRoot() == null || request.getWorkspaceRoot().isBlank()
                ? props.getWorkspaceRoot()
                : request.getWorkspaceRoot();
        return systemPromptComposer.composeForAgent(agent, workspaceRoot);
    }

    private static List<Message> seedConversation(String systemPrompt, List<Message> conversation) {
        if (conversation.isEmpty()) {
            return List.of(new Message(Message.Role.SYSTEM, systemPrompt, null, null));
        }
        Message first = conversation.getFirst();
        if (first.getRole() == Message.Role.SYSTEM) {
            if (!systemPrompt.equals(first.getContent())) {
                throw new IllegalStateException("Conversation already contains a different system prompt");
            }
            return conversation;
        }
        List<Message> seeded = new ArrayList<>(conversation.size() + 1);
        seeded.add(new Message(Message.Role.SYSTEM, systemPrompt, null, null));
        seeded.addAll(conversation);
        return seeded;
    }

    private Set<String> resolveAllowedTools(AgentDefinition agent) {
        if (agent == null) {
            return registry.all().keySet().stream().filter(tool -> !"task".equals(tool)).collect(Collectors.toCollection(HashSet::new));
        }
        Set<String> allowed = new HashSet<>(agent.allowedTools());
        if (agent.mode() == AgentMode.SUBAGENT) {
            allowed.remove("task");
        }
        return allowed;
    }

    private List<ToolDefinition> resolveToolDefinitions(Set<String> allowedTools, McpProjectToolSnapshot mcpSnapshot) {
        List<ToolDefinition> builtIns = registry.all().values().stream()
                .filter(tool -> allowedTools != null && allowedTools.contains(tool.name()))
                .map(tool -> tool.definition())
                .collect(Collectors.toCollection(ArrayList::new));
        if (!allowsMcpTools(allowedTools)) {
            return builtIns;
        }
        if (mcpSnapshot == null) {
            return builtIns;
        }
        List<ToolDefinition> defs = new ArrayList<>(builtIns.size() + mcpSnapshot.toolDefinitions().size());
        defs.addAll(builtIns);
        defs.addAll(mcpSnapshot.toolDefinitions());
        return defs;
    }

    private ToolExecutionResult executeTool(String toolName, Map<String, Object> args, ToolExecutionContext context,
                                           McpProjectToolSnapshot mcpSnapshot) throws Exception {
        if (registry.get(toolName) != null) {
            return registry.executeByName(toolName, args, context);
        }
        if (mcpSnapshot != null) {
            McpProjectToolExecutor executor = mcpSnapshot.executors().get(toolName);
            if (executor != null) {
                return executor.execute(args, context);
            }
        }
        throw new IllegalArgumentException("Unknown tool: " + toolName);
    }

    private boolean isToolAllowed(String toolName, Set<String> allowedTools) {
        if (allowedTools == null) {
            return false;
        }
        if (allowedTools.contains(toolName)) {
            return true;
        }
        return isMcpTool(toolName) && allowsMcpTools(allowedTools);
    }

    private boolean isMcpTool(String toolName) {
        return toolName != null && toolName.startsWith("mcp__");
    }

    private boolean allowsMcpTools(Set<String> allowedTools) {
        return allowedTools != null && (allowedTools.contains("mcp:*") || allowedTools.contains("*"));
    }

    private long resolveProjectId(Long sessionId) {
        if (sessionId == null || appStateService == null) {
            return -1L;
        }
        return appStateService.loadSessionProjectId(sessionId);
    }

    private Map<String, String> resolveEnvironmentVariables(Long sessionId) {
        if (sessionId == null || appStateService == null) {
            return Map.of();
        }
        return appStateService.loadSessionProjectEnvironmentVariables(sessionId);
    }

    private McpProjectToolSnapshot resolveMcpSnapshot(long projectId) {
        if (projectId < 0 || mcpRuntimeManager == null) {
            return new McpProjectToolSnapshot(projectId, List.of(), Map.of());
        }
        return mcpRuntimeManager.snapshot(projectId);
    }
}
