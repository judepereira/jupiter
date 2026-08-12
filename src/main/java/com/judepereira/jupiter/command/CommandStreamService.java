package com.judepereira.jupiter.command;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.judepereira.jupiter.agent.harness.ToolCallTrace;
import com.judepereira.jupiter.agent.tools.ToolExecutionContext;
import com.judepereira.jupiter.agent.tools.ToolExecutionResult;
import com.judepereira.jupiter.agent.tools.impl.RunCommandTool;
import com.judepereira.jupiter.persistence.AppStateService;
import com.judepereira.jupiter.persistence.Persistence.ToolCallTraceInput;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

@Log4j2
@Service
@RequiredArgsConstructor
public class CommandStreamService {

    private static final ObjectMapper SSE_JSON = new ObjectMapper();

    private final CommandCatalogService commandCatalogService;
    private final AppStateService appStateService;
    private final RunCommandTool runCommandTool;

    private final ConcurrentMap<String, ActiveCommandStream> activeStreams = new ConcurrentHashMap<>();

    public void queue(long sessionId, String assistantId, String commandId, String workspaceRoot, Map<String, String> environmentVariables) {
        activeStreams.put(assistantId, new ActiveCommandStream(new PendingCommand(sessionId, assistantId, commandId, workspaceRoot,
                environmentVariables == null ? Map.of() : Map.copyOf(environmentVariables))));
    }

    public SseEmitter tryConnect(String assistantId) {
        ActiveCommandStream active = activeStreams.get(assistantId);
        if (active == null || active.finished().get()) {
            return null;
        }

        SseEmitter emitter = new SseEmitter(0L);
        attachEmitter(active, emitter, assistantId);
        if (active.started().compareAndSet(false, true)) {
            startActiveStream(assistantId, active);
        }
        return emitter;
    }

    private void startActiveStream(String assistantId, ActiveCommandStream active) {
        try {
            Thread.startVirtualThread(() -> runActiveStream(assistantId, active));
        } catch (Throwable t) {
            active.started().set(false);
            Exception e = t instanceof Exception exception ? exception : new RuntimeException(t);
            fail(active, assistantId, e);
        }
    }

    private void runActiveStream(String assistantId, ActiveCommandStream active) {
        PendingCommand pending = active.pendingCommand();
        AtomicBoolean completed = new AtomicBoolean(false);
        CommandCatalogService.CommandDefinition command = commandCatalogService.getRequiredScript(pending.commandId());

        try {
            Integer timeoutSeconds = command.timeoutSeconds();
            ToolExecutionContext context = new ToolExecutionContext(Path.of(pending.workspaceRoot()), false, true,
                    timeoutSeconds == null ? 60 : timeoutSeconds, pending.sessionId(), null, null, assistantId,
                    pending.environmentVariables(), (eventName, payload) -> broadcastEvent(active, assistantId, "tool_call_progress", Map.of(
                            "toolCallId", assistantId,
                            "toolName", "run_command",
                            "eventName", eventName,
                            "payload", payload
                    )));
            broadcastEvent(active, assistantId, "tool_call_started", new ToolCallTrace(assistantId, "run_command", toolArgs(command), false, "", Map.of()));

            ToolExecutionResult result = runCommandTool.execute(toolArgs(command), context);

            String fullOutput = result.getText() == null ? "" : result.getText();
            ToolCallTrace trace = new ToolCallTrace(assistantId, "run_command", toolArgs(command), result.isSuccess(), fullOutput, result.getMachine());
            ToolCallTraceInput traceInput = new ToolCallTraceInput(trace.getToolCallId(), trace.getToolName(), trace.getArgs(), trace.isSuccess(), trace.getTextSummary(), trace.getMachineSummary());
            var storedCall = appStateService.appendToolCallTrace(pending.sessionId(), assistantId, traceInput);
            broadcastEvent(active, assistantId, "tool_call", storedCall);

            String finalText = summarizeOutput(fullOutput);
            var completedMessage = appStateService.completeAssistantMessage(pending.sessionId(), assistantId, finalText, List.of(traceInput));
            broadcastEvent(active, assistantId, "done", Map.of("text", completedMessage.text(), "toolCalls", completedMessage.toolCalls(), "assistantMessageId", assistantId));
            finish(active, assistantId, completed);
        } catch (Exception e) {
            fail(active, assistantId, e);
        }
    }

    private String summarizeOutput(String output) {
        if (output == null || output.isBlank()) {
            return "command completed with no output";
        }
        List<String> lines = output.lines().filter(line -> line != null && !line.isBlank()).toList();
        if (lines.isEmpty()) {
            return "command completed with no output";
        }
        int from = Math.max(0, lines.size() - 10);
        return String.join("\n", lines.subList(from, lines.size()));
    }

    private void fail(ActiveCommandStream active, String assistantId, Exception e) {
        try {
            appStateService.failAssistantMessage(active.pendingCommand().sessionId(), assistantId, "Command execution failed: " + normalizeMessage(e));
        } catch (Exception ignored) {
        }
        broadcastEvent(active, assistantId, "error", Map.of("message", normalizeMessage(e)));
        finish(active, assistantId, new AtomicBoolean());
        log.error("Command stream failed for assistant {}", assistantId, e);
    }

    private String normalizeMessage(Exception e) {
        String message = e == null ? null : e.getMessage();
        return message == null || message.isBlank() ? "error" : message;
    }

    private void finish(ActiveCommandStream active, String assistantId, AtomicBoolean completed) {
        if (!completed.compareAndSet(false, true)) {
            return;
        }
        active.finished().set(true);
        activeStreams.remove(assistantId, active);
        completeEmitters(active);
    }

    private void attachEmitter(ActiveCommandStream active, SseEmitter emitter, String assistantId) {
        active.emitters().add(emitter);
        emitter.onCompletion(() -> detachEmitter(active, emitter));
        emitter.onTimeout(() -> detachEmitter(active, emitter));
        emitter.onError(ignored -> detachEmitter(active, emitter));
    }

    private void detachEmitter(ActiveCommandStream active, SseEmitter emitter) {
        active.emitters().remove(emitter);
    }

    private void broadcastEvent(ActiveCommandStream active, String assistantId, String name, Object payload) {
        for (SseEmitter emitter : active.emitters()) {
            try {
                emitter.send(SseEmitter.event().name(name).data(SSE_JSON.writeValueAsString(payload)));
            } catch (Exception e) {
                log.debug("Dropping disconnected SSE subscriber for assistant {}", assistantId, e);
                detachEmitter(active, emitter);
            }
        }
    }

    private void completeEmitters(ActiveCommandStream active) {
        for (SseEmitter emitter : active.emitters()) {
            try {
                emitter.complete();
            } catch (Exception ignored) {
            }
        }
        active.emitters().clear();
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

    private record PendingCommand(long sessionId, String assistantId, String commandId, String workspaceRoot, Map<String, String> environmentVariables) {}

    private record ActiveCommandStream(PendingCommand pendingCommand, CopyOnWriteArrayList<SseEmitter> emitters, AtomicBoolean started,
                                       AtomicBoolean finished) {
        private ActiveCommandStream(PendingCommand pendingCommand) {
            this(pendingCommand, new CopyOnWriteArrayList<>(), new AtomicBoolean(false), new AtomicBoolean(false));
        }
    }
}
