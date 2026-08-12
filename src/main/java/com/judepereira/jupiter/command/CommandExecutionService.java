package com.judepereira.jupiter.command;

import com.judepereira.jupiter.agent.harness.ToolCallTrace;
import com.judepereira.jupiter.agent.llm.AgentStreamListener;
import com.judepereira.jupiter.agent.tools.ToolExecutionContext;
import com.judepereira.jupiter.agent.tools.ToolExecutionResult;
import com.judepereira.jupiter.agent.tools.impl.RunCommandTool;
import com.judepereira.jupiter.persistence.AppStateService;
import com.judepereira.jupiter.persistence.Persistence.ToolCallTraceInput;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Log4j2
@Service
@RequiredArgsConstructor
public class CommandExecutionService {

    private final CommandCatalogService commandCatalogService;
    private final AppStateService appStateService;
    private final RunCommandTool runCommandTool;

    public ExecutionResult executeScriptCommand(long sessionId, String assistantPublicId, String commandId, String workspaceRoot, Map<String, String> environmentVariables, AgentStreamListener listener) throws Exception {
        CommandCatalogService.CommandDefinition command = commandCatalogService.getRequiredScript(commandId);

        Integer timeoutSeconds = command.timeoutSeconds();
        ToolExecutionContext context = new ToolExecutionContext(Path.of(workspaceRoot), false, true,
                timeoutSeconds == null ? 60 : timeoutSeconds, sessionId, null, null, assistantPublicId,
                environmentVariables, (eventName, payload) -> {
                    if (listener != null) {
                        listener.onToolCallProgress(assistantPublicId, "run_command", eventName, payload);
                    }
                });
        ToolCallTrace started = new ToolCallTrace(assistantPublicId, "run_command", toolArgs(command), false, "", Map.of());
        if (listener != null) {
            listener.onToolCallStarted(started);
        }

        ToolExecutionResult result = runCommandTool.execute(toolArgs(command), context);

        String fullOutput = result.getText() == null ? "" : result.getText();
        String finalText = summarizeOutput(fullOutput);
        ToolCallTrace trace = new ToolCallTrace(assistantPublicId, "run_command", toolArgs(command), result.isSuccess(), fullOutput, result.getMachine());
        ToolCallTraceInput traceInput = new ToolCallTraceInput(trace.getToolCallId(), trace.getToolName(), trace.getArgs(), trace.isSuccess(), trace.getTextSummary(), trace.getMachineSummary());
        appStateService.appendToolCallTrace(sessionId, assistantPublicId, traceInput);
        if (listener != null) {
            listener.onToolCallTrace(trace);
        }

        appStateService.completeAssistantMessage(sessionId, assistantPublicId, finalText, List.of(traceInput));
        return new ExecutionResult(result.isSuccess(), finalText, fullOutput, traceInput);
    }

    private String summarizeOutput(String output) {
        if (output == null || output.isBlank()) {
            return "command completed with no output";
        }
        List<String> lines = output.lines().toList();
        if (lines.isEmpty()) {
            return "command completed with no output";
        }
        int from = Math.max(0, lines.size() - 10);
        return String.join("\n", lines.subList(from, lines.size()));
    }

    private Map<String, Object> toolArgs(CommandCatalogService.CommandDefinition command) {
        Map<String, Object> args = new java.util.LinkedHashMap<>();
        args.put("command", command.body());
        args.put("workingDir", command.workingDir() == null ? "" : command.workingDir());
        if (command.timeoutSeconds() != null) {
            args.put("timeoutSeconds", command.timeoutSeconds());
        }
        return args;
    }

    public record ExecutionResult(boolean success, String finalText, String fullOutput, ToolCallTraceInput trace) {}
}
