package com.judepereira.jupiter.ui;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.judepereira.jupiter.agent.catalog.*;
import com.judepereira.jupiter.agent.config.AgentProperties;
import com.judepereira.jupiter.agent.harness.AgentTurnRequest;
import com.judepereira.jupiter.agent.harness.AgentTurnResult;
import com.judepereira.jupiter.agent.harness.CancellationToken;
import com.judepereira.jupiter.agent.harness.CodingAgentHarness;
import com.judepereira.jupiter.agent.harness.StreamCancelledException;
import com.judepereira.jupiter.agent.harness.ToolCallTrace;
import com.judepereira.jupiter.agent.llm.AgentStreamListener;
import com.judepereira.jupiter.agent.llm.dto.Message;
import com.judepereira.jupiter.agent.tools.impl.FileUtils;
import com.judepereira.jupiter.command.CommandStreamService;
import com.judepereira.jupiter.lifecycle.LifecycleHookService;
import com.judepereira.jupiter.persistence.AppStateService;
import com.judepereira.jupiter.persistence.ContextCompactionService;
import com.judepereira.jupiter.persistence.Persistence.*;
import com.judepereira.jupiter.security.ProcessEnvironmentSanitizer;
import com.judepereira.jupiter.ui.ChatPresentationService.ToolCallView;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.log4j.Log4j2;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Log4j2
@org.springframework.stereotype.Service
@lombok.RequiredArgsConstructor
public class ChatStreamRuntime {
    private static final ObjectMapper SseJson = new ObjectMapper();
    private final CodingAgentHarness harness;
    private final AgentProperties agentProperties;
    private final AppStateService appStateService;
    private final AgentDefinitionService agentDefinitionService;
    private final ModelCatalogService modelCatalogService;
    private final AgentModelResolutionService agentModelResolutionService;
    private final ContextCompactionService contextCompactionService;
    private final AgentPreferenceResolver agentPreferenceResolver;
    private final ModelPickerService modelPickerService;
    private final CommandStreamService commandStreamService;
    private final ActiveStreamRegistryService activeStreamRegistryService;
    private final ChatPresentationService chatPresentationService;
    private final ChatToolCallHtmlService chatToolCallHtmlService;
    private final LifecycleHookService lifecycleHookService;
    final ConcurrentMap<String, ActiveStream> activeStreams = new ConcurrentHashMap<>();

    public record Submission(QueuedChatTurn queuedTurn, ChatMessageView summary, String assistantId) {
    }

    public Submission submit(long sessionId, String message, String agentId, String modelId, String thinkingLevel) {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("Message is required");
        }
        AgentDefinition agent = agentId == null || agentId.isBlank()
                ? agentDefinitionService.defaultAgent()
                : agentDefinitionService.getRequired(agentId);
        if (agentDefinitionService.listPrimaryAgents().stream().noneMatch(a -> a.id().equals(agent.id()))) {
            throw new IllegalArgumentException("Agent unavailable");
        }
        boolean implicitModel = modelId == null || modelId.isBlank();
        var preference = agentPreferenceResolver.resolve(agent, implicitModel ? null : modelId,
                thinkingLevel == null || thinkingLevel.isBlank() ? null : ThinkingLevel.fromValue(thinkingLevel),
                !implicitModel);
        var model = preference.model();
        if (modelPickerService != null && modelPickerService.listPickerModels().stream()
                .noneMatch(candidate -> candidate.id().equals(model.id()))) {
            throw new IllegalArgumentException(
                    "That model is no longer available. Choose an available model in Settings.");
        }
        String workspaceRoot = appStateService.loadSessionDetail(sessionId).workspaceRoot();
        String assistantId = UUID.randomUUID().toString();
        if (!activeStreamRegistryService.claim(assistantId, sessionId, workspaceRoot)) {
            throw new IllegalStateException("SESSION_BUSY: A chat stream is already active for the current session");
        }
        boolean persisted = false;
        try {
            var summary = contextCompactionService.compactIfNeeded(sessionId, agent, model, preference.thinkingLevel(),
                    workspaceRoot, message.trim());
            var metadata = new ChatMessageMetadata(agent.id(), agent.name(), model.id(),
                    preference.thinkingLevel().name(),
                    implicitModel && !model.id().equals(preference.preferredModelId())
                            ? preference.preferredModelId()
                            : null);
            var queued = appStateService.appendUserMessageAndPendingAssistant(sessionId, UUID.randomUUID().toString(),
                    assistantId, message.trim(), metadata);
            persisted = true;
            var cancellation = new CancellationToken();
            var request = new AgentTurnRequest(null,
                    new ArrayList<>(appStateService.buildConversationHistory(sessionId)), workspaceRoot, agent.id(),
                    implicitModel ? null : model.id(), preference.thinkingLevel(), sessionId, cancellation, preference);
            var active = ActiveStream.create(new PendingStream(sessionId, workspaceRoot, request), cancellation);
            activeStreams.put(assistantId, active);
            appStateService.publishWorkspaceRailRefresh();
            startActiveStream(assistantId, active, null);
            return new Submission(queued, summary.orElse(null), assistantId);
        } catch (RuntimeException e) {
            activeStreams.remove(assistantId);
            activeStreamRegistryService.unregister(assistantId);
            if (persisted) {
                appStateService.failAssistantMessage(sessionId, assistantId, "Failed to start agent execution");
            }
            throw e;
        }
    }
    public SseEmitter streamChat(String assistantId) {
        SseEmitter commandEmitter = commandStreamService.tryConnect(assistantId);
        if (commandEmitter != null) {
            return commandEmitter;
        }

        SseEmitter emitter = new SseEmitter(0L);
        ActiveStream active = activeStreams.get(assistantId);
        if (active == null) {
            sendPersistedTerminalState(assistantId, emitter);
            return emitter;
        }

        synchronized (active) {
            if (active.finished().get()) {
                sendPersistedTerminalState(assistantId, emitter);
                return emitter;
            }
            attachEmitter(active, emitter);
            String accumulated = active.accumulatedText().get().toString();
            if (!accumulated.isEmpty()) {
                sendEventToEmitter(active, assistantId, emitter, "delta", Map.of("text", accumulated));
            }
            sendToolCallSnapshot(active, assistantId, emitter);
        }
        return emitter;
    }

    private void sendPersistedTerminalState(String assistantId, SseEmitter emitter) {
        try {
            for (var session : appStateService.loadViewData().sessions()) {
                ChatMessageView message;
                try {
                    message = appStateService.loadAssistantMessageDetails(session.id(), assistantId);
                } catch (RuntimeException ignored) {
                    continue;
                }
                if (message.pending()) {
                    continue;
                }
                long sessionId = session.id();

                String event = message.completedTs() == null
                        ? "done"
                        : message.text() != null && message.text().startsWith("Agent execution failed:")
                                ? "error"
                                : "done";
                Map<String, Object> payload = event.equals("error")
                        ? Map.of("message", message.text(), "completedTs", message.completedTs())
                        : Map.of("text", message.text() == null ? "" : message.text(), "completedTs",
                                message.completedTs());
                emitter.send(SseEmitter.event().name(event).data(SseJson.writeValueAsString(payload)));
                if (chatToolCallHtmlService != null) {
                    emitter.send(SseEmitter.event().name("tool_call_html").data(
                            SseJson.writeValueAsString(chatToolCallHtmlService.hostSnapshot(sessionId, assistantId))));
                }
                emitter.complete();
                return;
            }
            emitter.send(
                    SseEmitter.event().name("error").data(SseJson.writeValueAsString(Map.of("message", "no_job"))));
        } catch (Exception ignored) {
            try {
                emitter.send(
                        SseEmitter.event().name("error").data(SseJson.writeValueAsString(Map.of("message", "no_job"))));
            } catch (Exception ignoredAgain) {
            }
        }
        emitter.complete();
    }

    void startActiveStream(String assistantId, ActiveStream active, SseEmitter emitter) {
        try {
            Thread runner = Thread.startVirtualThread(() -> runActiveStream(assistantId, active));
            active.runner().set(runner);
        } catch (Exception e) {
            active.started().set(false);
            listenerStartFailed(active, assistantId, e, emitter);
        } catch (Throwable t) {
            active.started().set(false);
            listenerStartFailed(active, assistantId, new RuntimeException(t), emitter);
        }
    }

    private void runActiveStream(String assistantId, ActiveStream active) {
        PendingStream pending = active.pendingStream();
        AtomicBoolean completed = active.completed();
        StringBuilder accumulated = active.accumulatedText().get();

        AgentStreamListener listener = new AgentStreamListener() {
            @Override
            public void onModelResolved(String preferredModelId, ModelDefinition actualModel) {
                appStateService.updateAssistantModelMetadata(pending.sessionId(), assistantId, actualModel.id(),
                        preferredModelId);
            }

            @Override
            public void onTextDelta(String delta) {
                try {
                    if (delta == null) {
                        return;
                    }
                    active.cancellationToken().throwIfCancelled();
                    accumulated.append(delta);
                    appStateService.updateStreamingAssistantText(pending.sessionId(), assistantId,
                            accumulated.toString());
                    broadcastEvent(active, assistantId, "delta", Map.of("text", delta));
                } catch (Exception e) {
                    onError(e);
                }
            }

            @Override
            public void onStatus(String status) {
                broadcastEvent(active, assistantId, "status", Map.of("status", status));
            }

            @Override
            public void onToolCallStarted(ToolCallTrace trace) {
                try {
                    if (trace != null) {
                        appStateService.startToolCallTrace(pending.sessionId(), assistantId,
                                toToolCallTraceInput(trace));
                        if (chatToolCallHtmlService != null) {
                            broadcastToolCallHtml(active, assistantId,
                                    chatToolCallHtmlService.toolStarted(pending.sessionId(), assistantId));
                        }
                    }
                    broadcastEvent(active, assistantId, "tool_call_started", trace);
                } catch (Exception e) {
                    onError(e);
                }
            }

            @Override
            public void onToolCallProgress(String toolCallId, String toolName, String eventName, Object payload) {
                broadcastEvent(active, assistantId, "tool_call_progress", Map.of("toolCallId", toolCallId, "toolName",
                        toolName, "eventName", eventName, "payload", payload));
                if ("subagent_started".equals(eventName) && chatToolCallHtmlService != null) {
                    broadcastToolCallHtml(active, assistantId,
                            chatToolCallHtmlService.subagentStarted(pending.sessionId(), assistantId, toolCallId));
                }
            }

            @Override
            public void onToolCallTrace(ToolCallTrace trace) {
                try {
                    ToolCallView v = chatPresentationService.toToolCallView(appStateService
                            .appendToolCallTrace(pending.sessionId(), assistantId, toToolCallTraceInput(trace)));
                    if (chatToolCallHtmlService != null) {
                        broadcastToolCallHtml(active, assistantId, chatToolCallHtmlService
                                .toolCompleted(pending.sessionId(), assistantId, trace.getToolCallId()));
                    }
                    broadcastEvent(active, assistantId, "tool_call", v);
                } catch (Exception e) {
                    onError(e);
                }
            }

            @Override
            public List<Message> onBeforeModelRequest(AgentTurnRequest currentRequest, List<Message> conversation) {
                active.cancellationToken().throwIfCancelled();
                if (currentRequest.getSessionId() == null) {
                    return conversation;
                }

                AgentDefinition requestAgent = resolveRequestAgent(currentRequest);
                ModelDefinition requestModel = resolveRequestModel(currentRequest, requestAgent);
                ThinkingLevel requestThinking = currentRequest.getPreferenceSnapshot() != null
                        ? currentRequest.getPreferenceSnapshot().thinkingLevel()
                        : (currentRequest.getThinkingLevel() != null
                                ? currentRequest.getThinkingLevel()
                                : (requestAgent == null ? null : requestAgent.defaultThinkingLevel()));

                Optional<ChatMessageView> summary = contextCompactionService.compactIfNeeded(
                        currentRequest.getSessionId(), requestAgent, requestModel, requestThinking,
                        pending.workspaceRoot(), null);
                if (summary.isEmpty()) {
                    return conversation;
                }

                broadcastEvent(active, assistantId, "context_compaction", summary.get());

                return appStateService.buildConversationHistory(currentRequest.getSessionId());
            }

            @Override
            public void onComplete(AgentTurnResult result) {
                if (completed.get()) {
                    return;
                }
                try {
                    String finalText = result.getFinalText() == null ? "" : result.getFinalText();
                    List<ToolCallTraceInput> traces = result.getTraces() == null
                            ? List.of()
                            : result.getTraces().stream().map(ChatStreamRuntime.this::toToolCallTraceInput).toList();
                    ChatMessageView completedMessage = appStateService.completeAssistantMessage(pending.sessionId(),
                            assistantId, finalText, traces);
                    dispatchLifecycleHook(LifecycleHookService.LifecycleEvent.ASSISTANT_COMPLETED, pending.sessionId());
                    processChangedFiles(result, pending.sessionId(), pending.workspaceRoot());
                    finalizeStreamSuccess(active, assistantId, completedMessage, completed);
                } catch (Exception e) {
                    if (!completed.get()) {
                        onError(e);
                    } else {
                        log.error("Failed after assistant stream became terminal: {}", assistantId, e);
                    }
                }
            }

            @Override
            public void onError(Exception e) {
                try {
                    if (completed.get()) {
                        return;
                    }
                    if (e instanceof StreamCancelledException) {
                        active.cancellationToken().cancel();
                        Thread runner = active.runner().getAndSet(null);
                        if (runner != null) {
                            runner.interrupt();
                        }
                        ChatMessageView stoppedMessage = appStateService.stopAssistantMessage(pending.sessionId(),
                                assistantId, accumulated.toString());
                        finalizeStreamStopped(active, assistantId, stoppedMessage, completed);
                        return;
                    }
                    String normalizedMessage = normalizeProviderErrorMessage(e);
                    ChatMessageView failedMessage = appStateService.failAssistantMessage(pending.sessionId(),
                            assistantId, "Agent execution failed: " + normalizedMessage);
                    dispatchLifecycleHook(LifecycleHookService.LifecycleEvent.ASSISTANT_ERRORED, pending.sessionId());
                    log.error("Execution failure!", e);
                    finalizeStreamError(active, assistantId, normalizedMessage, failedMessage, e, completed);
                } catch (Exception failure) {
                    log.error("Failed to finalize assistant stream {}", assistantId, failure);
                    active.finished().set(true);
                    activeStreams.remove(assistantId, active);
                    activeStreamRegistryService.unregister(assistantId);
                    completeEmitters(active);
                }
            }
        };

        try {
            AgentTurnResult result = harness.runTurnStreaming(pending.request(), listener);
            if (!completed.get()) {
                listener.onComplete(result);
            }
        } catch (StreamCancelledException e) {
            listener.onError(e);
        } catch (Exception e) {
            listener.onError(e);
        }
    }

    private void listenerStartFailed(ActiveStream active, String assistantId, Exception e, SseEmitter emitter) {
        try {
            String normalizedMessage = normalizeProviderErrorMessage(e);
            ChatMessageView failedMessage = appStateService.failAssistantMessage(active.pendingStream().sessionId(),
                    assistantId, "Agent execution failed: " + normalizedMessage);
            dispatchLifecycleHook(LifecycleHookService.LifecycleEvent.ASSISTANT_ERRORED,
                    active.pendingStream().sessionId());
            log.error("Execution failure!", e);
            broadcastToolCallHostSnapshot(active, assistantId);
            broadcastEvent(active, assistantId, "error",
                    Map.of("message", normalizedMessage, "completedTs", failedMessage.completedTs()));
        } catch (Exception ignored) {
        } finally {
            active.finished().set(true);
            activeStreams.remove(assistantId, active);
            activeStreamRegistryService.unregister(assistantId);
            appStateService.publishWorkspaceRailRefresh();
            completeEmitters(active);
            if (emitter != null) {
                detachEmitter(active, emitter);
            }
        }
    }

    private void attachEmitter(ActiveStream active, SseEmitter emitter) {
        active.emitters().add(emitter);
        emitter.onCompletion(() -> detachEmitter(active, emitter));
        emitter.onTimeout(() -> detachEmitter(active, emitter));
        emitter.onError(ignored -> detachEmitter(active, emitter));
    }

    private void detachEmitter(ActiveStream active, SseEmitter emitter) {
        active.emitters().remove(emitter);
    }

    private void sendToolCallSnapshot(ActiveStream active, String assistantId, SseEmitter emitter) {
        if (chatToolCallHtmlService == null) {
            return;
        }
        try {
            sendEventToEmitter(active, assistantId, emitter, "tool_call_html",
                    chatToolCallHtmlService.hostSnapshot(active.pendingStream().sessionId(), assistantId));
        } catch (Exception e) {
            log.error("Failed to send tool-call snapshot", e);
        }
    }

    private void broadcastToolCallHtml(ActiveStream active, String assistantId, List<DomPatch> patches) {
        if (chatToolCallHtmlService != null && !patches.isEmpty()) {
            broadcastEvent(active, assistantId, "tool_call_html", patches);
        }
    }

    private void dispatchLifecycleHook(LifecycleHookService.LifecycleEvent event, long sessionId) {
        if (lifecycleHookService == null) {
            return;
        }
        try {
            lifecycleHookService.dispatch(event, sessionId);
        } catch (Throwable e) {
            log.warn("Failed to dispatch lifecycle hook: event={}, sessionId={}", event, sessionId, e);
        }
    }

    private void broadcastToolCallHostSnapshot(ActiveStream active, String assistantId) {
        if (chatToolCallHtmlService != null) {
            broadcastToolCallHtml(active, assistantId,
                    chatToolCallHtmlService.hostSnapshot(active.pendingStream().sessionId(), assistantId));
        }
    }

    private void broadcastEvent(ActiveStream active, String assistantId, String name, Object payload) {
        for (SseEmitter emitter : active.emitters()) {
            sendEventToEmitter(active, assistantId, emitter, name, payload);
        }
    }

    private void sendEventToEmitter(ActiveStream active, String assistantId, SseEmitter emitter, String name,
            Object payload) {
        try {
            emitter.send(SseEmitter.event().name(name).data(SseJson.writeValueAsString(payload)));
        } catch (Exception e) {
            log.debug("Dropping disconnected SSE subscriber for assistant {}", assistantId, e);
            detachEmitter(active, emitter);
        }
    }

    private void finalizeStreamSuccess(ActiveStream active, String assistantId, ChatMessageView completedMessage,
            AtomicBoolean completed) {
        if (!completed.compareAndSet(false, true)) {
            return;
        }

        synchronized (active) {
            active.finished().set(true);
        }
        activeStreams.remove(assistantId, active);
        activeStreamRegistryService.unregister(assistantId);
        broadcastToolCallHostSnapshot(active, assistantId);
        broadcastEvent(active, assistantId, "done",
                Map.of("text", completedMessage.text(), "completedTs", completedMessage.completedTs()));
        completeEmitters(active);
        appStateService.publishWorkspaceRailRefresh();
    }

    private void finalizeStreamError(ActiveStream active, String assistantId, String normalizedMessage,
            ChatMessageView failedMessage, Exception e, AtomicBoolean completed) {
        if (!completed.compareAndSet(false, true)) {
            return;
        }

        active.finished().set(true);
        activeStreams.remove(assistantId, active);
        activeStreamRegistryService.unregister(assistantId);
        broadcastToolCallHostSnapshot(active, assistantId);
        broadcastEvent(active, assistantId, "error",
                Map.of("message", normalizedMessage, "completedTs", failedMessage.completedTs()));
        completeEmitters(active);
        appStateService.publishWorkspaceRailRefresh();
    }

    private void finalizeStreamStopped(ActiveStream active, String assistantId, ChatMessageView stoppedMessage,
            AtomicBoolean completed) {
        if (!completed.compareAndSet(false, true)) {
            return;
        }

        active.finished().set(true);
        activeStreams.remove(assistantId, active);
        activeStreamRegistryService.unregister(assistantId);
        broadcastToolCallHostSnapshot(active, assistantId);
        broadcastEvent(active, assistantId, "stopped",
                Map.of("message", stoppedMessage.text(), "completedTs", stoppedMessage.completedTs()));
        completeEmitters(active);
        appStateService.publishWorkspaceRailRefresh();
    }

    void stopActiveStream(String assistantId, ActiveStream active) {
        if (!active.completed().compareAndSet(false, true)) {
            return;
        }
        active.cancellationToken().cancel();
        Thread runner = active.runner().getAndSet(null);
        if (runner != null) {
            runner.interrupt();
        }
        try {
            ChatMessageView stoppedMessage = appStateService.stopAssistantMessage(active.pendingStream().sessionId(),
                    assistantId, active.accumulatedText().get().toString());
            active.finished().set(true);
            activeStreams.remove(assistantId, active);
            activeStreamRegistryService.unregister(assistantId);
            broadcastToolCallHostSnapshot(active, assistantId);
            broadcastEvent(active, assistantId, "stopped",
                    Map.of("message", stoppedMessage.text(), "completedTs", stoppedMessage.completedTs()));
            completeEmitters(active);
            appStateService.publishWorkspaceRailRefresh();
        } catch (Exception e) {
            log.error("Failed to stop assistant stream", e);
        }
    }

    private void completeEmitters(ActiveStream active) {
        for (SseEmitter emitter : active.emitters()) {
            try {
                emitter.complete();
            } catch (Exception ignored) {
            }
        }
        active.emitters().clear();
    }

    private String safeComputeDiff(String workspaceRoot, String relativePath) {
        final int MAX_CHARS = 16_000;
        Path root = Path.of(workspaceRoot);
        Path resolved;
        try {
            resolved = FileUtils.resolveWorkspacePath(root, relativePath);
        } catch (Exception e) {
            return "(invalid path: " + relativePath + ")";
        }

        try {
            Path relForGit = FileUtils.relativizeWorkspacePath(root, resolved);
            ProcessBuilder pb = new ProcessBuilder("git", "diff", "--", relForGit.toString());
            ProcessEnvironmentSanitizer.sanitize(pb);
            pb.directory(new File(root.toAbsolutePath().normalize().toString()));
            Process p = pb.start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    out.append(line).append('\n');
                    if (out.length() > MAX_CHARS) {
                        break;
                    }
                }
            }
            p.waitFor();
            String gitDiff = out.toString().trim();
            if (!gitDiff.isBlank()) {
                return gitDiff.length() > MAX_CHARS ? gitDiff.substring(0, MAX_CHARS) : gitDiff;
            }
        } catch (Exception ignored) {
        }

        try {
            if (!Files.exists(resolved)) {
                return "(no diff available)";
            }
            String content = Files.readString(resolved);
            if (content.length() > MAX_CHARS) {
                content = content.substring(0, MAX_CHARS) + "\n... (truncated)";
            }
            return "+++ " + resolved + "\n" + content;
        } catch (Exception e) {
            return "(error reading file: " + e.getMessage() + ")";
        }
    }

    private void processChangedFiles(AgentTurnResult result, long sessionId, String workspaceRoot) {
        var mutatingTools = Set.of("write_file", "apply_patch");
        List<ToolCallTrace> traces = result.getTraces() == null ? List.of() : result.getTraces();
        Set<String> seen = new HashSet<>();
        List<ChangedFileDraft> drafts = new ArrayList<>();

        for (ToolCallTrace t : traces) {
            if (t.getToolName() == null || !mutatingTools.contains(t.getToolName()) || !t.isSuccess()) {
                continue;
            }

            Object mp = t.getMachineSummary() == null ? null : t.getMachineSummary().get("path");
            String path = mp instanceof String ? (String) mp : null;
            if (path == null || path.isBlank()) {
                continue;
            }

            try {
                Path root = Path.of(workspaceRoot);
                Path resolved = FileUtils.resolveWorkspacePath(root, path);
                Path rel = FileUtils.relativizeWorkspacePath(root, resolved);
                String relStr = rel.toString();
                if (seen.add(relStr)) {
                    drafts.add(new ChangedFileDraft(relStr, safeComputeDiff(workspaceRoot, relStr)));
                }
            } catch (Exception ignored) {
            }
        }

        if (!drafts.isEmpty()) {
            appStateService.addChangedFilesToSession(sessionId, drafts);
        }
    }

    private AgentDefinition resolveRequestAgent(AgentTurnRequest request) {
        if (request.getAgentId() == null || request.getAgentId().isBlank()) {
            return agentDefinitionService.defaultAgent();
        }
        return agentDefinitionService.getRequired(request.getAgentId());
    }

    private ModelDefinition resolveRequestModel(AgentTurnRequest request, AgentDefinition agent) {
        if (request.getPreferenceSnapshot() != null) {
            return request.getPreferenceSnapshot().model();
        }
        String requestedModelId = request.getModelId();
        if (requestedModelId == null || requestedModelId.isBlank()) {
            return agent == null
                    ? modelCatalogService.getRequired(agentProperties.getModel())
                    : agentModelResolutionService.resolve(agent).model();
        }
        return modelCatalogService.getRequired(requestedModelId);
    }

    private ToolCallTraceInput toToolCallTraceInput(ToolCallTrace trace) {
        return new ToolCallTraceInput(trace.getToolCallId(), trace.getToolName(), trace.getArgs(), trace.isSuccess(),
                trace.getTextSummary(), trace.getMachineSummary());
    }

    private static final String CHATGPT_SESSION_EXPIRED_MESSAGE = "Your ChatGPT/OpenAI session has expired. Please sign in again.";

    private String normalizeProviderErrorMessage(Exception e) {
        if (e == null) {
            return "error";
        }

        String fallbackMessage = plainMessage(e.getMessage());
        String providerMessage = null;

        for (Throwable current = e; current != null; current = current.getCause()) {
            if (isAuthenticationExceptionWithTokenExpired(current)) {
                return CHATGPT_SESSION_EXPIRED_MESSAGE;
            }

            String message = current.getMessage();
            if (message == null || message.isBlank()) {
                continue;
            }

            String rawJson = extractJsonObject(message);
            if (rawJson == null) {
                continue;
            }

            try {
                ProviderErrorPayload payload = SseJson.readValue(rawJson, ProviderErrorPayload.class);
                if (isTokenExpired(payload)) {
                    return CHATGPT_SESSION_EXPIRED_MESSAGE;
                }

                String errorMessage = payload.error() == null ? null : payload.error().message();
                if (errorMessage != null && !errorMessage.isBlank()) {
                    providerMessage = errorMessage;
                }
            } catch (Exception ignored) {
            }
        }

        if (providerMessage != null) {
            return providerMessage;
        }

        return fallbackMessage == null || fallbackMessage.isBlank() ? "error" : fallbackMessage;
    }

    private boolean isAuthenticationExceptionWithTokenExpired(Throwable throwable) {
        if (throwable == null) {
            return false;
        }
        String simpleName = throwable.getClass().getSimpleName();
        if (!"AuthenticationException".equals(simpleName)) {
            return false;
        }
        return containsTokenExpired(throwable);
    }

    private boolean containsTokenExpired(Throwable throwable) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message != null && message.contains("token_expired")) {
                return true;
            }
        }
        return false;
    }

    private boolean isTokenExpired(ProviderErrorPayload payload) {
        if (payload == null) {
            return false;
        }
        return isTokenExpired(payload.error()) || isTokenExpired(payload.detail());
    }

    private boolean isTokenExpired(ProviderError error) {
        return error != null && "token_expired".equals(error.code());
    }

    private boolean isTokenExpired(ProviderDetail detail) {
        return detail != null && "token_expired".equals(detail.code());
    }

    private String plainMessage(String message) {
        return message == null || message.isBlank() || extractJsonObject(message) != null ? null : message;
    }

    private String extractJsonObject(String message) {
        int start = message.indexOf('{');
        int end = message.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        return message.substring(start, end + 1);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ProviderErrorPayload(ProviderError error, ProviderDetail detail) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ProviderError(String message, String code) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ProviderDetail(String message, String code) {
    }

    record PendingStream(long sessionId, String workspaceRoot, AgentTurnRequest request) {
    }

    record ActiveStream(PendingStream pendingStream, CopyOnWriteArrayList<SseEmitter> emitters, AtomicBoolean started,
            AtomicBoolean finished, AtomicBoolean completed, AtomicReference<Thread> runner,
            CancellationToken cancellationToken, AtomicReference<StringBuilder> accumulatedText) {
        static ActiveStream create(PendingStream pendingStream, CancellationToken cancellationToken) {
            return new ActiveStream(pendingStream, new CopyOnWriteArrayList<>(), new AtomicBoolean(false),
                    new AtomicBoolean(false), new AtomicBoolean(false), new AtomicReference<>(), cancellationToken,
                    new AtomicReference<>(new StringBuilder()));
        }
    }
}
