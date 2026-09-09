package com.judepereira.jupiter.agent.tools.impl;

import com.judepereira.jupiter.agent.catalog.AgentDefinitionService;
import com.judepereira.jupiter.agent.catalog.AgentMode;
import com.judepereira.jupiter.agent.llm.dto.ToolDefinition;
import com.judepereira.jupiter.agent.llm.dto.ToolSchema;
import com.judepereira.jupiter.agent.task.SubagentTaskService;
import com.judepereira.jupiter.agent.tools.AgentTool;
import com.judepereira.jupiter.agent.tools.ToolExecutionContext;
import com.judepereira.jupiter.agent.tools.ToolExecutionResult;
import lombok.RequiredArgsConstructor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.judepereira.jupiter.agent.llm.dto.ToolParameter.string;

@RequiredArgsConstructor
public class TaskTool implements AgentTool {

    private final AgentDefinitionService agentDefinitionService;
    private final SubagentTaskService subagentTaskService;

    @Override
    public String name() {
        return "task";
    }

    @Override
    public ToolDefinition definition() {
        return ToolDefinition.builtIn("task", buildDescription(), ToolSchema.object(
                string("agentId", "subagent id to run"),
                string("requestSummary", "concise summary of the request for UI display"),
                string("task", "task instruction for the subagent"),
                string("expectedOutput", "what the primary expects back")
        ).required("agentId", "requestSummary", "task", "expectedOutput"));
    }

    @Override
    public ToolExecutionResult execute(Map<String, Object> args, ToolExecutionContext context) {
        if (context == null || context.getAgentMode() != AgentMode.AGENT) {
            return failure("task tool is only available to primary agents");
        }
        if (context.getSessionId() == null) {
            return failure("task tool requires a session id");
        }
        if (context.getToolCallId() == null || context.getToolCallId().isBlank()) {
            return failure("task tool requires a tool call id");
        }

        String agentId = stringArg(args, "agentId");
        String requestSummary = stringArg(args, "requestSummary");
        String task = stringArg(args, "task");
        String expectedOutput = stringArg(args, "expectedOutput");
        if (agentId == null || requestSummary == null || task == null || expectedOutput == null) {
            return failure("missing required task tool arguments");
        }

        try {
            var result = subagentTaskService.runTask(new SubagentTaskService.SubagentTaskRequest(
                    context.getSessionId(),
                    context.getToolCallId(),
                    context.getWorkspaceRoot().toString(),
                    agentId,
                    requestSummary,
                    task,
                    expectedOutput,
                    context.getCancellationToken()
            ), new SubagentTaskService.SubagentTaskStreamListener() {
                @Override
                public void onStarted(SubagentTaskService.SubagentTaskStarted event) {
                    context.getProgressSink().emit("subagent_started", event);
                }

                @Override
                public void onTextDelta(SubagentTaskService.SubagentTaskTextDelta event) {
                    context.getProgressSink().emit("subagent_delta", event);
                }

                @Override
                public void onToolCall(SubagentTaskService.SubagentTaskToolCall event) {
                    context.getProgressSink().emit("subagent_tool_call", event);
                }

                @Override
                public void onComplete(SubagentTaskService.SubagentTaskCompleted event) {
                    context.getProgressSink().emit("subagent_done", event);
                }

                @Override
                public void onError(SubagentTaskService.SubagentTaskError event) {
                    context.getProgressSink().emit("subagent_error", event);
                }
            });

            Map<String, Object> machine = new LinkedHashMap<>();
            machine.put("subagentSessionId", result.childSessionId());
            machine.put("subagentAgentId", result.subagentAgentId());
            machine.put("subagentAgentName", result.subagentAgentName());
            machine.put("changedFiles", result.changedFiles());
            machine.put("success", result.success());
            if (result.errorText() != null) {
                machine.put("error", result.errorText());
            }
            return new ToolExecutionResult(result.success(), result.finalText() == null ? "" : result.finalText(), machine);
        } catch (Exception e) {
            return failure(e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    private String buildDescription() {
        List<String> subagents = agentDefinitionService.listSubagents().stream()
                .map(agent -> agent.name() + " (" + agent.id() + ") - " + agent.description())
                .toList();
        return subagents.isEmpty()
                ? "Run a hidden subagent task. No subagents are available."
                : "Run a hidden subagent task. Available subagents: " + String.join("; ", subagents);
    }

    private static String stringArg(Map<String, Object> args, String name) {
        Object value = args == null ? null : args.get(name);
        return value instanceof String s ? s : null;
    }

    private static ToolExecutionResult failure(String message) {
        return new ToolExecutionResult(false, "[tool_error] " + message, Map.of());
    }
}
