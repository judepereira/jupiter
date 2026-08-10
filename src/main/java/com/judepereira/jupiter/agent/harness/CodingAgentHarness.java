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
import com.judepereira.jupiter.agent.llm.dto.Message;
import com.judepereira.jupiter.agent.llm.dto.ModelResponse;
import com.judepereira.jupiter.agent.llm.dto.ToolCall;
import com.judepereira.jupiter.agent.llm.dto.ToolDefinition;
import com.judepereira.jupiter.agent.tools.ToolExecutionContext;
import com.judepereira.jupiter.agent.tools.ToolExecutionResult;
import com.judepereira.jupiter.agent.tools.ToolProgressSink;
import com.judepereira.jupiter.agent.tools.ToolRegistry;
import com.judepereira.jupiter.persistence.AppStateService;
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
    private final SystemPromptComposer systemPromptComposer;

    public CodingAgentHarness(AgentModelClientFactory modelFactory, ToolRegistry registry, AgentProperties props) {
        this(modelFactory, registry, props, null, null, null, new SystemPromptComposer());
    }

    public CodingAgentHarness(AgentModelClientFactory modelFactory, ToolRegistry registry, AgentProperties props,
                              AgentDefinitionService agentDefinitionService, ModelCatalogService modelCatalogService) {
        this(modelFactory, registry, props, agentDefinitionService, modelCatalogService, null, new SystemPromptComposer());
    }

    public CodingAgentHarness(AgentModelClientFactory modelFactory, ToolRegistry registry, AgentProperties props,
                              AgentDefinitionService agentDefinitionService, ModelCatalogService modelCatalogService,
                              AppStateService appStateService) {
        this(modelFactory, registry, props, agentDefinitionService, modelCatalogService, appStateService, new SystemPromptComposer());
    }

    @Autowired
    public CodingAgentHarness(AgentModelClientFactory modelFactory, ToolRegistry registry, AgentProperties props,
                              AgentDefinitionService agentDefinitionService, ModelCatalogService modelCatalogService,
                              AppStateService appStateService, SystemPromptComposer systemPromptComposer) {
        this.modelFactory = modelFactory;
        this.registry = registry;
        this.props = props;
        this.agentDefinitionService = agentDefinitionService;
        this.modelCatalogService = modelCatalogService;
        this.appStateService = appStateService;
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
                ToolProgressSink.noop());

        Set<String> allowedTools = resolveAllowedTools(agent);
        List<ToolDefinition> defs = resolveToolDefinitions(allowedTools);

        StringBuilder accumulated = new StringBuilder();

        try {
            for (int i = 0; i < max; i++) {
                List<Message> preparedConversation = listener.onBeforeModelRequest(request, List.copyOf(convo));
                if (preparedConversation == null) {
                    throw new IllegalStateException("Listener returned null conversation before model request");
                }
                convo = new ArrayList<>(seedConversation(systemPrompt, preparedConversation));

                ModelResponse resp = model.chatStreaming(convo, defs, modelOptions, delta -> {
                    // forward and accumulate every non-null delta, including whitespace-only chunks
                    if (delta != null) {
                        accumulated.append(delta);
                        listener.onTextDelta(delta);
                    }
                });

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

                    convo.add(new Message(Message.Role.ASSISTANT, null,
                            List.of(new ToolCall(toolCallId, resolvedToolName, args))));

                    if (toolName == null || toolName.isBlank()) {
                        String toolMsg = "[tool_error] Tool call missing tool name";
                        convo.add(new Message(Message.Role.TOOL, toolMsg, toolCallId));
                        ToolCallTrace trace = new ToolCallTrace(toolCallId, resolvedToolName, args, false, toolMsg,
                                Map.of("error", "tool name missing"));
                        traces.add(trace);
                        listener.onToolCallTrace(trace);
                        listener.onStatus("tool_error:missing_tool_name");
                        continue;
                    }
                    if (!allowedTools.contains(toolName)) {
                        String toolMsg = "[tool_error] Tool not allowed for selected agent: " + toolName;
                        convo.add(new Message(Message.Role.TOOL, toolMsg, toolCallId));
                        ToolCallTrace trace = new ToolCallTrace(toolCallId, toolName, args, false, toolMsg,
                                Map.of("error", "tool not allowed"));
                        traces.add(trace);
                        listener.onToolCallTrace(trace);
                        listener.onStatus("tool_error:not_allowed:" + toolName);
                        continue;
                    }
                    listener.onStatus("calling_tool:" + toolName);
                    listener.onToolCallStarted(new ToolCallTrace(toolCallId, toolName, args, false, "", Map.of()));
                    // execute tool
                    try {
                        ToolExecutionContext execCtx = new ToolExecutionContext(execCtxTemplate.getWorkspaceRoot(),
                                execCtxTemplate.isAllowWrite(),
                                execCtxTemplate.isAllowCommand(),
                                execCtxTemplate.getCommandTimeoutSeconds(),
                                execCtxTemplate.getSessionId(),
                                execCtxTemplate.getAgentId(),
                                execCtxTemplate.getAgentMode(),
                                toolCallId,
                                execCtxTemplate.getEnvironmentVariables(),
                                (eventName, payload) -> listener.onToolCallProgress(toolCallId, toolName, eventName, payload));
                        ToolExecutionResult result = registry.executeByName(toolName, args, execCtx);
                        String toolText = result.getText() == null ? "" : result.getText();
                        convo.add(new Message(Message.Role.TOOL, toolText, toolCallId));
                        ToolCallTrace trace = new ToolCallTrace(toolCallId, toolName, args, result.isSuccess(), result.getText(), result.getMachine());
                        traces.add(trace);
                        listener.onToolCallTrace(trace);
                        listener.onStatus("tool_result:" + toolName);
                    } catch (IllegalArgumentException e) {
                        // unknown tool
                        String toolMsg = "[tool_error] Unknown tool: " + toolName;
                        convo.add(new Message(Message.Role.TOOL, toolMsg, toolCallId));
                        ToolCallTrace trace = new ToolCallTrace(toolCallId, toolName, args, false, toolMsg, Map.of("error", e.getMessage()));
                        traces.add(trace);
                        listener.onToolCallTrace(trace);
                        listener.onStatus("tool_error:" + toolName);
                    } catch (Exception e) {
                        String toolMsg = "[tool_error] " + e.getMessage();
                        convo.add(new Message(Message.Role.TOOL, toolMsg, toolCallId));
                        ToolCallTrace trace = new ToolCallTrace(toolCallId, toolName, args, false, toolMsg, Map.of("exception", e.toString()));
                        traces.add(trace);
                        listener.onToolCallTrace(trace);
                        listener.onStatus("tool_exception:" + toolName);
                    }
                    // continue loop
                } else if (assistantText != null && !assistantText.isBlank()) {
                    // final assistant text
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
        } catch (Exception e) {
            listener.onError(e);
            throw e;
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
            return List.of(new Message(Message.Role.SYSTEM, systemPrompt));
        }
        Message first = conversation.getFirst();
        if (first.getRole() == Message.Role.SYSTEM) {
            if (!systemPrompt.equals(first.getContent())) {
                throw new IllegalStateException("Conversation already contains a different system prompt");
            }
            return conversation;
        }
        List<Message> seeded = new ArrayList<>(conversation.size() + 1);
        seeded.add(new Message(Message.Role.SYSTEM, systemPrompt));
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

    private List<ToolDefinition> resolveToolDefinitions(Set<String> allowedTools) {
        if (allowedTools == null) {
            return registry.all().values().stream().map(tool -> tool.definition()).collect(Collectors.toList());
        }
        return registry.all().values().stream()
                .filter(tool -> allowedTools.contains(tool.name()))
                .map(tool -> tool.definition())
                .collect(Collectors.toList());
    }

    private Map<String, String> resolveEnvironmentVariables(Long sessionId) {
        if (sessionId == null || appStateService == null) {
            return Map.of();
        }
        return appStateService.loadSessionProjectEnvironmentVariables(sessionId);
    }
}
