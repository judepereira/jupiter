package com.judepereira.jupiter.agent.task;

import com.judepereira.jupiter.agent.catalog.AgentDefinition;
import com.judepereira.jupiter.agent.catalog.AgentDefinitionService;
import com.judepereira.jupiter.agent.catalog.AgentMode;
import com.judepereira.jupiter.agent.harness.AgentTurnRequest;
import com.judepereira.jupiter.agent.harness.AgentTurnResult;
import com.judepereira.jupiter.agent.harness.CancellationToken;
import com.judepereira.jupiter.agent.harness.CodingAgentHarness;
import com.judepereira.jupiter.agent.harness.StreamCancelledException;
import com.judepereira.jupiter.agent.harness.ToolCallTrace;
import com.judepereira.jupiter.agent.llm.AgentStreamListener;
import com.judepereira.jupiter.agent.tools.impl.FileUtils;
import com.judepereira.jupiter.persistence.AppStateService;
import com.judepereira.jupiter.persistence.Persistence.ChatMessageMetadata;
import com.judepereira.jupiter.persistence.Persistence.ChangedFileDraft;
import com.judepereira.jupiter.persistence.Persistence.ToolCallTraceInput;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@RequiredArgsConstructor
public class SubagentTaskService {

    private static final int DIFF_PREVIEW_LIMIT = 20_000;

    private final AppStateService appStateService;
    private final AgentDefinitionService agentDefinitionService;
    private final ObjectProvider<CodingAgentHarness> harnessProvider;

    public SubagentTaskResult runTask(SubagentTaskRequest request) {
        return runTask(request, SubagentTaskStreamListener.noop());
    }

    public SubagentTaskResult runTask(SubagentTaskRequest request, SubagentTaskStreamListener listener) {
        if (request == null) {
            throw new IllegalStateException("Subagent task request is required");
        }
        if (request.parentSessionId() == null) {
            throw new IllegalStateException("Parent session id is required");
        }
        if (request.parentToolCallId() == null || request.parentToolCallId().isBlank()) {
            throw new IllegalStateException("Parent tool call id is required");
        }
        if (request.workspaceRoot() == null || request.workspaceRoot().isBlank()) {
            throw new IllegalStateException("Workspace root is required");
        }
        if (request.subagentAgentId() == null || request.subagentAgentId().isBlank()) {
            throw new IllegalStateException("Subagent id is required");
        }
        if (request.requestSummary() == null || request.requestSummary().isBlank()) {
            throw new IllegalStateException("Request summary is required");
        }
        if (request.task() == null || request.task().isBlank()) {
            throw new IllegalStateException("Task instruction is required");
        }
        if (request.expectedOutput() == null || request.expectedOutput().isBlank()) {
            throw new IllegalStateException("Expected output is required");
        }

        AgentDefinition subagent = agentDefinitionService.getRequired(request.subagentAgentId());
        if (subagent.mode() != AgentMode.SUBAGENT) {
            throw new IllegalStateException("Target agent is not a subagent: " + subagent.id());
        }

        SubagentTaskStreamListener sink = listener == null ? SubagentTaskStreamListener.noop() : listener;
        long childSessionId = appStateService.createHiddenSubagentSession(request.parentSessionId(), request.parentToolCallId(), subagent);
        sink.onStarted(new SubagentTaskStarted(childSessionId, request.parentSessionId(), request.parentToolCallId(), subagent.id(), subagent.name(), request.requestSummary(), request.task()));

        String userPrompt = buildUserPrompt(request.task(), request.expectedOutput());
        ChatMessageMetadata assistantMetadata = new ChatMessageMetadata(subagent.id(), subagent.name(), subagent.defaultModel(), subagent.defaultThinkingLevel().name());
        var queued = appStateService.appendUserMessageAndPendingAssistant(childSessionId, null, null, userPrompt, assistantMetadata);
        String assistantPublicId = queued.assistantMessage().id();

        List<ToolCallTrace> traces = new ArrayList<>();
        Set<String> changedPaths = new LinkedHashSet<>();
        AtomicBoolean closed = new AtomicBoolean(false);

        try {
            CodingAgentHarness harness = harnessProvider.getObject();
            AgentTurnRequest childRequest = new AgentTurnRequest(subagent.systemPrompt(), appStateService.buildConversationHistory(childSessionId),
                    request.workspaceRoot(), subagent.id(), subagent.defaultModel(), subagent.defaultThinkingLevel(), childSessionId, request.cancellationToken());

            AgentTurnResult result = harness.runTurnStreaming(childRequest, new AgentStreamListener() {
                private final StringBuilder accumulated = new StringBuilder();

                @Override
                public void onTextDelta(String delta) {
                    if (delta == null) {
                        return;
                    }
                    throwIfCancelled(request.cancellationToken());
                    accumulated.append(delta);
                    appStateService.updateStreamingAssistantText(childSessionId, assistantPublicId, accumulated.toString());
                    sink.onTextDelta(new SubagentTaskTextDelta(childSessionId, request.parentToolCallId(), subagent.id(), subagent.name(), delta));
                }

                @Override
                public void onToolCallTrace(ToolCallTrace trace) {
                    traces.add(trace);
                    appStateService.appendToolCallTrace(childSessionId, assistantPublicId, toTraceInput(trace));
                    sink.onToolCall(new SubagentTaskToolCall(childSessionId, request.parentToolCallId(), subagent.id(), subagent.name(), trace.getToolCallId(), trace.getToolName(), trace.isSuccess(),
                            trace.getArgs() == null ? "" : trace.getArgs().toString(), trace.getTextSummary() == null ? "" : trace.getTextSummary(), trace.getMachineSummary()));
                    if (isMutating(trace)) {
                        String path = extractChangedPath(trace);
                        if (path != null && !path.isBlank()) {
                            changedPaths.add(path);
                        }
                    }
                }

                @Override
                public void onComplete(AgentTurnResult result) {
                    String finalText = result.getFinalText() == null ? "" : result.getFinalText();
                    appStateService.completeAssistantMessage(childSessionId, assistantPublicId, finalText,
                            result.getTraces() == null ? List.of() : result.getTraces().stream().map(SubagentTaskService.this::toTraceInput).toList());
                    sink.onComplete(new SubagentTaskCompleted(childSessionId, request.parentToolCallId(), subagent.id(), subagent.name(), finalText));
                    closed.set(true);
                }

                @Override
                public void onError(Exception e) {
                    if (e instanceof StreamCancelledException) {
                        appStateService.stopAssistantMessage(childSessionId, assistantPublicId, accumulated.toString());
                        sink.onError(new SubagentTaskError(childSessionId, request.parentToolCallId(), subagent.id(), subagent.name(), "Action Interrupted"));
                        closed.set(true);
                        return;
                    }
                    String message = e == null ? "Unknown subagent error" : e.getMessage();
                    appStateService.failAssistantMessage(childSessionId, assistantPublicId, message == null ? "Unknown subagent error" : message);
                    sink.onError(new SubagentTaskError(childSessionId, request.parentToolCallId(), subagent.id(), subagent.name(), message));
                    closed.set(true);
                }
            });

            List<ChangedFileDraft> drafts = buildChangedFileDrafts(request.workspaceRoot(), changedPaths);
            persistChangedFiles(childSessionId, request.parentSessionId(), drafts);
            return new SubagentTaskResult(true, childSessionId, subagent.id(), subagent.name(), result.getFinalText(), drafts, traces, null);
        } catch (Exception e) {
            String message = e instanceof StreamCancelledException ? "Action Interrupted" : (e.getMessage() == null ? e.toString() : e.getMessage());
            if (!closed.get()) {
                try {
                    if (e instanceof StreamCancelledException) {
                        appStateService.stopAssistantMessage(childSessionId, assistantPublicId, "");
                    } else {
                        appStateService.failAssistantMessage(childSessionId, assistantPublicId, message);
                    }
                } catch (Exception ignored) {
                }
                sink.onError(new SubagentTaskError(childSessionId, request.parentToolCallId(), subagent.id(), subagent.name(), message));
            }
            List<ChangedFileDraft> drafts = buildChangedFileDrafts(request.workspaceRoot(), changedPaths);
            persistChangedFiles(childSessionId, request.parentSessionId(), drafts);
            return new SubagentTaskResult(false, childSessionId, subagent.id(), subagent.name(), message, drafts, traces, message);
        }
    }

    private void persistChangedFiles(long childSessionId, long parentSessionId, List<ChangedFileDraft> drafts) {
        if (drafts.isEmpty()) {
            return;
        }
        appStateService.addChangedFilesToSession(childSessionId, drafts);
        appStateService.addChangedFilesToSession(parentSessionId, drafts);
    }

    private static void throwIfCancelled(CancellationToken cancellationToken) {
        if (cancellationToken != null) {
            cancellationToken.throwIfCancelled();
        }
    }

    private List<ChangedFileDraft> buildChangedFileDrafts(String workspaceRoot, Set<String> changedPaths) {
        if (changedPaths.isEmpty()) {
            return List.of();
        }
        return changedPaths.stream().map(path -> new ChangedFileDraft(path, computeDiff(workspaceRoot, path))).toList();
    }

    private static boolean isMutating(ToolCallTrace trace) {
        return trace != null && trace.isSuccess() && trace.getToolName() != null && Set.of("write_file", "apply_patch").contains(trace.getToolName());
    }

    private static String extractChangedPath(ToolCallTrace trace) {
        if (trace == null || trace.getMachineSummary() == null) {
            return null;
        }
        Object path = trace.getMachineSummary().get("path");
        return path instanceof String ? (String) path : null;
    }

    private static String buildUserPrompt(String task, String expectedOutput) {
        return "Primary task:\n" + task + "\n\nExpected output:\n" + expectedOutput;
    }

    private static String computeDiff(String workspaceRoot, String relativePath) {
        Path root = Path.of(workspaceRoot);
        Path resolved;
        try {
            resolved = FileUtils.resolveWorkspacePath(root, relativePath);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to resolve changed file path: " + relativePath, e);
        }

        String gitDiff = gitDiff(root, relativePath);
        if (gitDiff != null && !gitDiff.isBlank()) {
            return gitDiff.length() > DIFF_PREVIEW_LIMIT ? gitDiff.substring(0, DIFF_PREVIEW_LIMIT) : gitDiff;
        }

        try {
            if (!Files.exists(resolved)) {
                return "(no diff available)";
            }
            String content = Files.readString(resolved, StandardCharsets.UTF_8);
            if (content.length() > DIFF_PREVIEW_LIMIT) {
                content = content.substring(0, DIFF_PREVIEW_LIMIT) + "\n... (truncated)";
            }
            return "+++ " + relativePath + "\n" + content;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to compute changed file diff for: " + relativePath, e);
        }
    }

    private static String gitDiff(Path workspaceRoot, String relativePath) {
        try {
            Process process = new ProcessBuilder("git", "diff", "HEAD", "--", relativePath)
                    .directory(workspaceRoot.toFile())
                    .start();
            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            int exitCode = process.waitFor();
            if (exitCode == 0) {
                return stdout.stripTrailing();
            }
            if (!stderr.isBlank() && stdout.isBlank()) {
                return "";
            }
            return stdout.stripTrailing();
        } catch (Exception e) {
            return "";
        }
    }

    private com.judepereira.jupiter.persistence.Persistence.ToolCallTraceInput toTraceInput(ToolCallTrace trace) {
        return new ToolCallTraceInput(trace.getToolCallId(), trace.getToolName(), trace.getArgs(), trace.isSuccess(),
                trace.getTextSummary(), trace.getMachineSummary());
    }

    public record SubagentTaskRequest(Long parentSessionId, String parentToolCallId, String workspaceRoot, String subagentAgentId,
                                      String requestSummary, String task, String expectedOutput, CancellationToken cancellationToken) {
        public SubagentTaskRequest(Long parentSessionId, String parentToolCallId, String workspaceRoot, String subagentAgentId,
                                   String requestSummary, String task, String expectedOutput) {
            this(parentSessionId, parentToolCallId, workspaceRoot, subagentAgentId, requestSummary, task, expectedOutput, null);
        }
    }

    public record SubagentTaskResult(boolean success, long childSessionId, String subagentAgentId, String subagentAgentName, String finalText,
                                     List<ChangedFileDraft> changedFiles, List<ToolCallTrace> traces, String errorText) {
    }

    public interface SubagentTaskStreamListener {
        default void onStarted(SubagentTaskStarted event) {
        }

        default void onTextDelta(SubagentTaskTextDelta event) {
        }

        default void onToolCall(SubagentTaskToolCall event) {
        }

        default void onComplete(SubagentTaskCompleted event) {
        }

        default void onError(SubagentTaskError event) {
        }

        static SubagentTaskStreamListener noop() {
            return new SubagentTaskStreamListener() {
            };
        }
    }

    public record SubagentTaskStarted(long childSessionId, long parentSessionId, String parentToolCallId, String subagentAgentId, String subagentAgentName, String requestSummary, String task) {
    }

    public record SubagentTaskTextDelta(long childSessionId, String parentToolCallId, String subagentAgentId, String subagentAgentName, String delta) {
    }

    public record SubagentTaskToolCall(long childSessionId, String parentToolCallId, String subagentAgentId, String subagentAgentName, String toolCallId, String toolName,
                                       boolean success, String inputPreview, String outputPreview, Map<String, Object> machineSummary) {
    }

    public record SubagentTaskCompleted(long childSessionId, String parentToolCallId, String subagentAgentId, String subagentAgentName, String finalText) {
    }

    public record SubagentTaskError(long childSessionId, String parentToolCallId, String subagentAgentId, String subagentAgentName, String errorText) {
    }
}
