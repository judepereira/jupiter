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
import com.judepereira.jupiter.agent.mcp.McpProjectMcpServerRuntimeManager;
import com.judepereira.jupiter.agent.mcp.McpRuntimeEvents;
import com.judepereira.jupiter.agent.tools.impl.FileUtils;
import com.judepereira.jupiter.command.CommandStreamService;
import com.judepereira.jupiter.git.GitAutoUpdateService;
import com.judepereira.jupiter.lifecycle.LifecycleHookService;
import com.judepereira.jupiter.openai.oauth.OpenAiOAuthService;
import com.judepereira.jupiter.persistence.AppStateService;
import com.judepereira.jupiter.persistence.ContextCompactionService;
import com.judepereira.jupiter.persistence.TokenUsageService;
import com.judepereira.jupiter.persistence.GitWorktreeException;
import com.judepereira.jupiter.persistence.InvalidGitBranchNameException;
import com.judepereira.jupiter.persistence.Persistence.*;
import com.judepereira.jupiter.terminal.TerminalHandle;
import com.judepereira.jupiter.terminal.TerminalManager;
import com.judepereira.jupiter.terminal.TerminalPanelState;
import com.judepereira.jupiter.terminal.TerminalStateService;
import com.judepereira.jupiter.ui.balloon.SystemBalloonService;
import com.judepereira.jupiter.ui.rail.WorkspaceRailRefreshService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.judepereira.jupiter.ui.ChatPresentationService.ChatMessage;
import com.judepereira.jupiter.ui.ChatPresentationService.ToolCallView;

@Log4j2
@Controller
public class UiController {

    private static final ObjectMapper SseJson = new ObjectMapper();
    private static final String DEFAULT_APP_VERSION = "0.0.1-SNAPSHOT";

    private final CodingAgentHarness harness;
    private final AgentProperties agentProperties;
    private final AppStateService appStateService;
    private final AgentDefinitionService agentDefinitionService;
    private final ModelCatalogService modelCatalogService;
    private final ContextCompactionService contextCompactionService;
    private final TokenUsageService tokenUsageService;
    private final CommandStreamService commandStreamService;
    private final McpProjectMcpServerRuntimeManager mcpRuntimeManager;
    private final TerminalManager terminalManager;
    private final TerminalStateService terminalStateService;
    private final SystemBalloonService systemBalloonService;
    private final WorkspaceRailRefreshService workspaceRailRefreshService;
    private final ActiveStreamRegistryService activeStreamRegistryService;
    private final OpenAiOAuthService openAiOAuthService;
    private final ChatPresentationService chatPresentationService;
    private final ChatToolCallHtmlService chatToolCallHtmlService;
    private final LifecycleHookService lifecycleHookService;
    private final GitAutoUpdateService gitAutoUpdateService;
    private final String appVersion;

    private final ConcurrentMap<String, ActiveStream> activeStreams = new ConcurrentHashMap<>();

    @Autowired
    public UiController(CodingAgentHarness harness, AgentProperties agentProperties, AppStateService appStateService,
                        AgentDefinitionService agentDefinitionService, ModelCatalogService modelCatalogService,
                        SystemBalloonService systemBalloonService, WorkspaceRailRefreshService workspaceRailRefreshService,
                        ActiveStreamRegistryService activeStreamRegistryService,
                        TerminalManager terminalManager,
                        TerminalStateService terminalStateService, OpenAiOAuthService openAiOAuthService,
                        ContextCompactionService contextCompactionService, TokenUsageService tokenUsageService,
                        CommandStreamService commandStreamService,
                        McpProjectMcpServerRuntimeManager mcpRuntimeManager, ChatPresentationService chatPresentationService,
                        ChatToolCallHtmlService chatToolCallHtmlService, LifecycleHookService lifecycleHookService,
                        GitAutoUpdateService gitAutoUpdateService,
                        @Value("${app.version:" + DEFAULT_APP_VERSION + "}") String appVersion) {
        this.harness = harness;
        this.agentProperties = agentProperties;
        this.appStateService = appStateService;
        this.agentDefinitionService = agentDefinitionService;
        this.modelCatalogService = modelCatalogService;
        this.contextCompactionService = contextCompactionService;
        this.tokenUsageService = tokenUsageService;
        this.commandStreamService = commandStreamService;
        this.mcpRuntimeManager = mcpRuntimeManager;
        this.systemBalloonService = systemBalloonService;
        this.activeStreamRegistryService = activeStreamRegistryService;
        this.terminalManager = terminalManager;
        this.terminalStateService = terminalStateService;
        this.workspaceRailRefreshService = workspaceRailRefreshService;
        this.openAiOAuthService = openAiOAuthService;
        this.chatPresentationService = chatPresentationService;
        this.chatToolCallHtmlService = chatToolCallHtmlService;
        this.lifecycleHookService = lifecycleHookService;
        this.gitAutoUpdateService = gitAutoUpdateService;
        this.appVersion = appVersion;
    }

    @GetMapping("/")
    public String index(Model model) {
        AppStateView view = appStateService.loadViewData();
        populateChatControlsModel(model, activeChatSelection(view));
        populateProjectModel(model, view);
        populateSessionModel(model, view);
        return "index";
    }

    public String sendMessage(@RequestParam("message") String message,
                              Model model,
                              HttpServletRequest request) {
        return sendMessage(message, null, null, null, model, request);
    }

    @PostMapping("/ui/chat/send")
    public String sendMessage(@RequestParam("message") String message,
                              @RequestParam(value = "agentId", required = false) String agentId,
                              @RequestParam(value = "modelId", required = false) String modelId,
                              @RequestParam(value = "thinkingLevel", required = false) String thinkingLevel,
                              Model model,
                              HttpServletRequest request) {
        List<ChatMessage> newChatMessages = new ArrayList<>();
        AppStateView view = appStateService.loadViewData();
        SessionView session = null;
        boolean shellRefresh = false;
        ChatSelection selected = resolveChatSelection(agentId, modelId, thinkingLevel);

        if (message != null && !message.isBlank()) {
            if (view.activeSession() != null && activeStreamRegistryService.hasActiveStreamForSession(view.activeSession().id())) {
                throw new IllegalStateException("A chat stream is already active for the current session");
            }
            shellRefresh = view.activeSession() == null;
            if (shellRefresh) {
                session = appStateService.ensureChatSession(agentProperties.getWorkspaceRoot());
                view = appStateService.loadViewData();
            } else {
                session = view.activeSession();
            }
            SessionDetailView sessionDetail = view.activeSessionDetail();
            String workspaceRoot = sessionDetail.workspaceRoot();
            String user = message.trim();
            String assistantId = UUID.randomUUID().toString();
            String userId = UUID.randomUUID().toString();
            ChatMessageMetadata metadata = new ChatMessageMetadata(selected.selectedAgent().id(), selected.selectedAgent().name(), selected.selectedModel().id(), selected.selectedThinking().name());
            Optional<ChatMessageView> summaryMessage = contextCompactionService.compactIfNeeded(session.id(), selected.selectedAgent(), selected.selectedModel(),
                    selected.selectedThinking(), workspaceRoot, user);
            summaryMessage.ifPresent(summary -> newChatMessages.add(toChatMessage(summary)));
            QueuedChatTurn queued = appStateService.appendUserMessageAndPendingAssistant(session.id(), userId, assistantId, user, metadata);
            newChatMessages.add(toChatMessage(queued.userMessage()));
            newChatMessages.add(toChatMessage(queued.assistantMessage()));

            List<Message> conversationHistory = new ArrayList<>(appStateService.buildConversationHistory(session.id()));
            CancellationToken cancellationToken = new CancellationToken();
            ActiveStream activeStream = ActiveStream.create(new PendingStream(session.id(), workspaceRoot,
                    new AgentTurnRequest(null, conversationHistory, workspaceRoot,
                            selected.selectedAgent().id(), selected.selectedModel().id(), selected.selectedThinking(), session.id(), cancellationToken)), cancellationToken);
            activeStreams.put(assistantId, activeStream);
            try {
                activeStreamRegistryService.register(assistantId, session.id(), workspaceRoot);
                appStateService.publishWorkspaceRailRefresh();
            } catch (Exception e) {
                activeStreams.remove(assistantId, activeStream);
                activeStreamRegistryService.unregister(assistantId);
                throw e instanceof RuntimeException runtime ? runtime : new IllegalStateException("Failed to queue active stream", e);
            }

            view = appStateService.loadViewData();
        }

        populateChatControlsModel(model, selected);
        populateProjectModel(model, view);
        populateSessionModel(model, view);
        model.addAttribute("newChatMessages", List.copyOf(newChatMessages));
        model.addAttribute("pendingStreamBaseUrl", "/ui/chat/stream");
        model.addAttribute("subagentView", false);
        boolean hasPending = view.activeSessionDetail() != null && view.activeSessionDetail().chatMessages().stream().anyMatch(ChatMessageView::pending);
        model.addAttribute("hasPending", hasPending);
        model.addAttribute("shellRefresh", shellRefresh);
        model.addAttribute("includeChatContainer", false);
        model.addAttribute("reviewOob", shellRefresh || (view.activeSessionDetail() != null && !hasPending && view.activeSessionDetail().reviewPanelOpen()));
        return "fragments/chat-response :: response";
    }

    @GetMapping("/ui/review/file/{id}")
    public String loadFile(@PathVariable("id") int id, Model model) {
        AppStateView view = appStateService.loadViewData();
        if (view.activeSession() != null && view.activeSessionDetail() != null) {
            ChangedFileView found = view.activeSessionDetail().changedFiles().stream().filter(f -> f.id() != null && f.id() == id).findFirst().orElse(null);
            if (found != null) {
                appStateService.selectSessionChangedFile(view.activeSession().id(), id);
                view = appStateService.loadViewData();
            }
        }
        populateProjectModel(model, view);
        populateSessionModel(model, view);
        model.addAttribute("reviewOob", false);
        return "fragments/review :: panel";
    }

    public String loadFile(ReviewSource source, String key, Model model) {
        return loadReviewFile(source, key, false, model);
    }

    @GetMapping("/ui/review/file")
    public String loadFile(@RequestParam("source") ReviewSource source,
                           @RequestParam("key") String key,
                           @RequestParam(value = "close", defaultValue = "false") boolean close,
                           Model model) {
        return loadReviewFile(source, key, close, model);
    }

    private String loadReviewFile(ReviewSource source, String key, boolean close, Model model) {
        AppStateView view = appStateService.loadViewData();
        ChangedFileView selectedFile = null;
        if (view.activeSession() != null) {
            long sessionId = view.activeSession().id();
            if (close) {
                if (source == ReviewSource.SESSION) {
                    appStateService.clearSessionChangedFileSelection(sessionId);
                }
            } else if (source == ReviewSource.GIT) {
                selectedFile = appStateService.selectGitChangedFile(sessionId, key.startsWith("git:") ? key.substring(4) : key);
                appStateService.switchReviewSource(sessionId, source);
            } else {
                appStateService.selectSessionChangedFile(sessionId, Integer.parseInt(key.startsWith("session:") ? key.substring(8) : key));
                view = appStateService.loadViewData();
                selectedFile = view.activeSessionDetail().selectedFile();
            }
            view = appStateService.loadViewData();
        }
        populateProjectModel(model, view);
        populateSessionModel(model, view);
        if (selectedFile != null) {
            model.addAttribute("selectedFile", toChangedFile(selectedFile));
        }
        model.addAttribute("reviewOob", false);
        return "fragments/review :: panel";
    }

    @GetMapping("/ui/chat/stream/{assistantId}")
    public SseEmitter streamChat(@PathVariable("assistantId") String assistantId) {
        SseEmitter commandEmitter = commandStreamService.tryConnect(assistantId);
        if (commandEmitter != null) {
            return commandEmitter;
        }

        SseEmitter emitter = new SseEmitter(0L);
        ActiveStream active = activeStreams.get(assistantId);
        if (active == null || active.finished().get()) {
            try {
                emitter.send(SseEmitter.event().name("error").data(SseJson.writeValueAsString(Map.of("message", "no_job"))));
            } catch (Exception ignored) {
            }
            emitter.complete();
            return emitter;
        }

        attachEmitter(active, emitter);
        sendToolCallSnapshot(active, assistantId, emitter);
        if (active.started().compareAndSet(false, true)) {
            startActiveStream(assistantId, active, emitter);
        }

        return emitter;
    }

    private void startActiveStream(String assistantId, ActiveStream active, SseEmitter emitter) {
        try {
            Thread runner = Thread.startVirtualThread(() -> runActiveStream(assistantId, active));
            active.runner().set(runner);
        } catch (Throwable t) {
            active.started().set(false);
            Exception e = t instanceof Exception exception ? exception : new RuntimeException(t);
            listenerStartFailed(active, assistantId, e, emitter);
        }
    }

    private void runActiveStream(String assistantId, ActiveStream active) {
        PendingStream pending = active.pendingStream();
        AtomicBoolean completed = active.completed();
        StringBuilder accumulated = active.accumulatedText().get();

        AgentStreamListener listener = new AgentStreamListener() {
            @Override
            public void onTextDelta(String delta) {
                try {
                    if (delta == null) {
                        return;
                    }
                    active.cancellationToken().throwIfCancelled();
                    accumulated.append(delta);
                    appStateService.updateStreamingAssistantText(pending.sessionId(), assistantId, accumulated.toString());
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
                        appStateService.startToolCallTrace(pending.sessionId(), assistantId, toToolCallTraceInput(trace));
                        if (chatToolCallHtmlService != null) {
                            broadcastToolCallHtml(active, assistantId, chatToolCallHtmlService.toolStarted(pending.sessionId(), assistantId));
                        }
                    }
                    broadcastEvent(active, assistantId, "tool_call_started", trace);
                } catch (Exception e) {
                    onError(e);
                }
            }

            @Override
            public void onToolCallProgress(String toolCallId, String toolName, String eventName, Object payload) {
                broadcastEvent(active, assistantId, "tool_call_progress", Map.of(
                        "toolCallId", toolCallId,
                        "toolName", toolName,
                        "eventName", eventName,
                        "payload", payload
                ));
                if ("subagent_started".equals(eventName) && chatToolCallHtmlService != null) {
                    broadcastToolCallHtml(active, assistantId,
                            chatToolCallHtmlService.subagentStarted(pending.sessionId(), assistantId, toolCallId));
                }
            }

            @Override
            public void onToolCallTrace(ToolCallTrace trace) {
                try {
                    ToolCallView v = chatPresentationService.toToolCallView(appStateService.appendToolCallTrace(pending.sessionId(), assistantId, toToolCallTraceInput(trace)));
                    if (chatToolCallHtmlService != null) {
                        broadcastToolCallHtml(active, assistantId, chatToolCallHtmlService.toolCompleted(pending.sessionId(), assistantId, trace.getToolCallId()));
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
                ThinkingLevel requestThinking = currentRequest.getThinkingLevel() != null
                        ? currentRequest.getThinkingLevel()
                        : (requestAgent == null ? null : requestAgent.defaultThinkingLevel());

                Optional<ChatMessageView> summary = contextCompactionService.compactIfNeeded(currentRequest.getSessionId(), requestAgent,
                        requestModel, requestThinking, pending.workspaceRoot(), null);
                if (summary.isEmpty()) {
                    return conversation;
                }

                broadcastEvent(active, assistantId, "context_compaction", summary.get());

                return appStateService.buildConversationHistory(currentRequest.getSessionId());
            }

            @Override
            public void onComplete(AgentTurnResult result) {
                try {
                    String finalText = result.getFinalText() == null ? "" : result.getFinalText();
                    List<ToolCallTraceInput> traces = result.getTraces() == null ? List.of() : result.getTraces().stream().map(UiController.this::toToolCallTraceInput).toList();
                    ChatMessageView completedMessage = appStateService.completeAssistantMessage(pending.sessionId(), assistantId, finalText, traces);
                    dispatchLifecycleHook(LifecycleHookService.LifecycleEvent.ASSISTANT_COMPLETED, pending.sessionId());
                    processChangedFiles(result, pending.sessionId(), pending.workspaceRoot());
                    finalizeStreamSuccess(active, assistantId, completedMessage, completed);
                } catch (Exception e) {
                    onError(e);
                }
            }

            @Override
            public void onError(Exception e) {
                try {
                    if (e instanceof StreamCancelledException) {
                        active.cancellationToken().cancel();
                        Thread runner = active.runner().getAndSet(null);
                        if (runner != null) {
                            runner.interrupt();
                        }
                        ChatMessageView stoppedMessage = appStateService.stopAssistantMessage(pending.sessionId(), assistantId, accumulated.toString());
                        finalizeStreamStopped(active, assistantId, stoppedMessage, completed);
                        return;
                    }
                    String normalizedMessage = normalizeProviderErrorMessage(e);
                    ChatMessageView failedMessage = appStateService.failAssistantMessage(pending.sessionId(), assistantId, "Agent execution failed: " + normalizedMessage);
                    dispatchLifecycleHook(LifecycleHookService.LifecycleEvent.ASSISTANT_ERRORED, pending.sessionId());
                    log.error("Execution failure!", e);
                    finalizeStreamError(active, assistantId, normalizedMessage, failedMessage, e, completed);
                } catch (Exception ignored) {
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
            ChatMessageView failedMessage = appStateService.failAssistantMessage(active.pendingStream().sessionId(), assistantId, "Agent execution failed: " + normalizedMessage);
            dispatchLifecycleHook(LifecycleHookService.LifecycleEvent.ASSISTANT_ERRORED, active.pendingStream().sessionId());
            log.error("Execution failure!", e);
            broadcastToolCallHostSnapshot(active, assistantId);
            broadcastEvent(active, assistantId, "error", Map.of("message", normalizedMessage, "completedTs", failedMessage.completedTs()));
        } catch (Exception ignored) {
        } finally {
            active.finished().set(true);
            activeStreams.remove(assistantId, active);
            activeStreamRegistryService.unregister(assistantId);
            appStateService.publishWorkspaceRailRefresh();
            completeEmitters(active);
            detachEmitter(active, emitter);
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

    private void sendEventToEmitter(ActiveStream active, String assistantId, SseEmitter emitter, String name, Object payload) {
        try {
            emitter.send(SseEmitter.event().name(name).data(SseJson.writeValueAsString(payload)));
        } catch (Exception e) {
            log.debug("Dropping disconnected SSE subscriber for assistant {}", assistantId, e);
            detachEmitter(active, emitter);
        }
    }

    private void finalizeStreamSuccess(ActiveStream active, String assistantId, ChatMessageView completedMessage, AtomicBoolean completed) {
        if (!completed.compareAndSet(false, true)) {
            return;
        }

        active.finished().set(true);
        activeStreams.remove(assistantId, active);
        activeStreamRegistryService.unregister(assistantId);
        broadcastToolCallHostSnapshot(active, assistantId);
        broadcastEvent(active, assistantId, "done", Map.of("text", completedMessage.text(), "completedTs", completedMessage.completedTs()));
        completeEmitters(active);
        appStateService.publishWorkspaceRailRefresh();
    }

    private void finalizeStreamError(ActiveStream active, String assistantId, String normalizedMessage, ChatMessageView failedMessage, Exception e, AtomicBoolean completed) {
        if (!completed.compareAndSet(false, true)) {
            return;
        }

        active.finished().set(true);
        activeStreams.remove(assistantId, active);
        activeStreamRegistryService.unregister(assistantId);
        broadcastToolCallHostSnapshot(active, assistantId);
        broadcastEvent(active, assistantId, "error", Map.of("message", normalizedMessage, "completedTs", failedMessage.completedTs()));
        completeEmitters(active);
        appStateService.publishWorkspaceRailRefresh();
    }

    private void finalizeStreamStopped(ActiveStream active, String assistantId, ChatMessageView stoppedMessage, AtomicBoolean completed) {
        if (!completed.compareAndSet(false, true)) {
            return;
        }

        active.finished().set(true);
        activeStreams.remove(assistantId, active);
        activeStreamRegistryService.unregister(assistantId);
        broadcastToolCallHostSnapshot(active, assistantId);
        broadcastEvent(active, assistantId, "stopped", Map.of("message", stoppedMessage.text(), "completedTs", stoppedMessage.completedTs()));
        completeEmitters(active);
        appStateService.publishWorkspaceRailRefresh();
    }

    private void stopActiveStream(String assistantId, ActiveStream active) {
        if (!active.completed().compareAndSet(false, true)) {
            return;
        }
        active.cancellationToken().cancel();
        Thread runner = active.runner().getAndSet(null);
        if (runner != null) {
            runner.interrupt();
        }
        try {
            ChatMessageView stoppedMessage = appStateService.stopAssistantMessage(active.pendingStream().sessionId(), assistantId, active.accumulatedText().get().toString());
            active.finished().set(true);
            activeStreams.remove(assistantId, active);
            activeStreamRegistryService.unregister(assistantId);
            broadcastToolCallHostSnapshot(active, assistantId);
            broadcastEvent(active, assistantId, "stopped", Map.of("message", stoppedMessage.text(), "completedTs", stoppedMessage.completedTs()));
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

    @PostMapping("/ui/chat/stop")
    public String stopChat(@RequestParam(value = "assistantId", required = false) String assistantId, Model model) {
        AppStateView view = appStateService.loadViewData();
        SessionView session = view.activeSession();
        if (session == null) {
            populateChatControlsModel(model, activeChatSelection(view));
            populateProjectModel(model, view);
            populateSessionModel(model, view);
            return "fragments/chat :: chat";
        }

        String targetAssistantId = assistantId;
        ActiveStream active = targetAssistantId == null || targetAssistantId.isBlank() ? null : activeStreams.get(targetAssistantId);
        if (active != null && active.pendingStream().sessionId() != session.id()) {
            throw new IllegalStateException("Assistant stream does not belong to the active session");
        }
        if (active == null) {
            active = activeStreams.values().stream().filter(s -> s.pendingStream().sessionId() == session.id()).findFirst().orElse(null);
        }
        if (active == null) {
            if (targetAssistantId != null && !targetAssistantId.isBlank()) {
                Long streamSessionId = activeStreamRegistryService.sessionIdForAssistantId(targetAssistantId).orElse(null);
                if (streamSessionId != null && streamSessionId != session.id()) {
                    throw new IllegalStateException("Assistant stream does not belong to the active session");
                }
                commandStreamService.stop(targetAssistantId);
            } else {
                activeStreamRegistryService.assistantIdForSession(session.id()).ifPresent(commandStreamService::stop);
            }
            view = appStateService.loadViewData();
            populateChatControlsModel(model, activeChatSelection(view));
            populateProjectModel(model, view);
            populateSessionModel(model, view);
            return "fragments/chat :: chat";
        }

        if (targetAssistantId == null || targetAssistantId.isBlank()) {
            ActiveStream targetActive = active;
            targetAssistantId = activeStreams.entrySet().stream()
                    .filter(entry -> entry.getValue() == targetActive)
                    .map(Map.Entry::getKey)
                    .findFirst()
                    .orElseThrow();
        }
        stopActiveStream(targetAssistantId, active);
        view = appStateService.loadViewData();
        populateChatControlsModel(model, activeChatSelection(view));
        populateProjectModel(model, view);
        populateSessionModel(model, view);
        return "fragments/chat :: chat";
    }

    @GetMapping("/ui/chat/tool-call/{assistantPublicId}/{toolCallId}")
    @ResponseBody
    public String loadToolCallGroup(@PathVariable String assistantPublicId, @PathVariable String toolCallId) {
        return chatToolCallHtmlService.lazyGroup(assistantPublicId, toolCallId);
    }

    @GetMapping("/ui/chat/image/{sessionId}/{toolCallId}")
    ResponseEntity<byte[]> streamDisplayImage(@PathVariable long sessionId, @PathVariable String toolCallId) throws Exception {
        AppStateService.DisplayImageView view = appStateService.loadDisplayImageView(sessionId, toolCallId);
        Path workspace = Path.of(view.workspaceRoot());
        Path resolved = FileUtils.resolveWorkspacePath(workspace, view.path());
        if (!Files.exists(resolved) || !Files.isRegularFile(resolved)) {
            throw new IllegalStateException("Image file not found: " + view.path());
        }
        String mediaType = FileUtils.resolveAllowedImageMediaType(Files.probeContentType(resolved), view.path());
        if (mediaType == null) {
            throw new IllegalStateException("Unsupported image type: " + view.path());
        }
        byte[] bytes = Files.readAllBytes(resolved);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, mediaType)
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(bytes);
    }

    @GetMapping("/ui/chat/primary")
    public String loadPrimaryChat(Model model) {
        AppStateView view = appStateService.loadViewData();
        if (view.activeSession() == null || view.activeSessionDetail() == null) {
            throw new IllegalStateException("No active primary session");
        }

        populateChatControlsModel(model, activeChatSelection(view));
        model.addAttribute("activeSession", toSession(view.activeSession()));
        model.addAttribute("chatDraft", view.activeSessionDetail().chatDraft());
        populateChatModel(model, view.activeSessionDetail().chatMessages(), false, null, null, null);
        model.addAttribute("forkSessionId", view.activeSession().id());
        return "fragments/chat :: chat";
    }

    @GetMapping("/ui/chat/subagent/{sessionId}")
    public String loadSubagentChat(@PathVariable long sessionId, Model model) {
        AppStateView view = appStateService.loadViewData();
        if (view.activeSession() == null) {
            throw new IllegalStateException("No active primary session");
        }

        SubagentSessionDetailView subagent = appStateService.loadSubagentSessionDetail(sessionId);
        if (subagent.parentSessionId() == null || subagent.parentSessionId() != view.activeSession().id()) {
            throw new IllegalStateException("Subagent session does not belong to the active primary session: " + sessionId);
        }

        populateChatControlsModel(model, defaultChatSelection());
        populateChatModel(model, subagent.sessionDetail().chatMessages(), true, subagent.subagentAgentName(), subagent.subagentAgentId(), sessionId);
        return "fragments/chat :: chat";
    }

    @PostMapping("/ui/chat/fork/{assistantPublicId}")
    public String forkPrimaryChat(@PathVariable String assistantPublicId, Model model) {
        AppStateView before = appStateService.loadViewData();
        if (before.activeSession() == null) {
            throw new IllegalStateException("No active primary session");
        }
        appStateService.forkPrimarySessionAtAssistantMessage(before.activeSession().id(), assistantPublicId);
        AppStateView view = appStateService.loadViewData();
        populateProjectModel(model, view);
        populateSessionModel(model, view);
        populateShellUpdates(model, view);
        return "fragments/projects :: shellUpdates";
    }

    @GetMapping("/ui/system-balloons/stream")
    public SseEmitter systemBalloonStream(@RequestParam(value = "shellId", required = false) String shellId) {
        SseEmitter emitter = systemBalloonService.connect();
        if (systemBalloonService.markShellInitialized(shellId)) {
            sendInitialMcpFailureBalloons(emitter);
        }
        return emitter;
    }

    @GetMapping("/ui/workspaces/rail/stream")
    public SseEmitter workspaceRailStream() {
        return workspaceRailRefreshService.connect();
    }

    @PostMapping("/ui/review/toggle")
    public String toggleReview(Model model) {
        AppStateView view = appStateService.loadViewData();
        if (view.activeSession() != null) {
            appStateService.toggleReviewPanel(view.activeSession().id());
            view = appStateService.loadViewData();
        }
        populateProjectModel(model, view);
        populateSessionModel(model, view);
        model.addAttribute("reviewOob", false);
        return "fragments/review :: panel";
    }

    @PostMapping("/ui/review/source")
    public String switchReviewSource(@RequestParam("source") ReviewSource source, Model model) {
        AppStateView view = appStateService.loadViewData();
        if (view.activeSession() != null) {
            appStateService.switchReviewSource(view.activeSession().id(), source);
            view = appStateService.loadViewData();
        }
        populateProjectModel(model, view);
        populateSessionModel(model, view);
        model.addAttribute("reviewOob", false);
        return "fragments/review :: panel";
    }

    @PostMapping("/ui/panel/review")
    public String openReviewPanel(Model model) {
        AppStateView view = currentViewWithSessionIfNeeded(false);
        if (view.activeSession() != null && (view.activeSessionDetail() == null || !view.activeSessionDetail().reviewPanelOpen())) {
            appStateService.toggleReviewPanel(view.activeSession().id());
            view = appStateService.loadViewData();
        }
        populateProjectModel(model, view);
        populateSessionModel(model, view);
        model.addAttribute("reviewOob", false);
        return "fragments/review :: panel";
    }

    @PostMapping("/ui/panel/terminal")
    public String openTerminalPanel(Model model) {
        AppStateView view = appStateService.loadViewData();
        if (view.activeWorkspace() != null) {
            TerminalPanelState state = terminalStateService.snapshot(view.activeWorkspace().id());
            if (state.bottomPanelOpen()) {
                terminalStateService.closeTerminalPane(view.activeWorkspace().id());
            } else {
                if (state.terminalTabs().isEmpty()) {
                    TerminalHandle terminal = terminalManager.createTerminal(view.activeWorkspace().path(), activeProjectEnvironmentVariables(view));
                    terminalStateService.registerTerminal(view.activeWorkspace().id(), terminal);
                }
                terminalStateService.openTerminalPane(view.activeWorkspace().id());
            }
            view = appStateService.loadViewData();
        }
        populateProjectModel(model, view);
        populateSessionModel(model, view);
        return "fragments/terminal :: panel";
    }

    @PostMapping("/ui/terminal/new")
    public String newTerminal(Model model) {
        AppStateView view = appStateService.loadViewData();
        if (view.activeWorkspace() != null) {
            TerminalHandle terminal = terminalManager.createTerminal(view.activeWorkspace().path(), activeProjectEnvironmentVariables(view));
            terminalStateService.registerTerminal(view.activeWorkspace().id(), terminal);
            terminalStateService.openTerminalPane(view.activeWorkspace().id());
            view = appStateService.loadViewData();
        }
        populateProjectModel(model, view);
        populateSessionModel(model, view);
        return "fragments/terminal :: panel";
    }

    @PostMapping("/ui/terminal/{id}/activate")
    public String activateTerminal(@PathVariable String id, Model model) {
        AppStateView view = appStateService.loadViewData();
        if (view.activeWorkspace() != null) {
            terminalStateService.activateTerminal(view.activeWorkspace().id(), id);
            terminalStateService.openTerminalPane(view.activeWorkspace().id());
            view = appStateService.loadViewData();
        }
        populateProjectModel(model, view);
        populateSessionModel(model, view);
        return "fragments/terminal :: panel";
    }

    @PostMapping("/ui/terminal/{id}/close")
    public String closeTerminal(@PathVariable String id, Model model) {
        AppStateView view = appStateService.loadViewData();
        if (view.activeWorkspace() != null) {
            terminalStateService.closeTerminal(view.activeWorkspace().id(), id);
            terminalManager.closeTerminal(id);
            view = appStateService.loadViewData();
        }
        populateProjectModel(model, view);
        populateSessionModel(model, view);
        return "fragments/terminal :: panel";
    }

    @GetMapping("/ui/panel/{name}")
    public String panelPlaceholder(@PathVariable String name, Model model) {
        model.addAttribute("panelName", name);
        return "fragments/panel :: panel";
    }

    @GetMapping("/ui/projects/new")
    public String newProjectModal(Model model) {
        Path home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
        model.addAttribute("currentPath", home.toString());
        model.addAttribute("selectedPath", home.toString());
        model.addAttribute("startPath", home.toString());
        model.addAttribute("selectedName", directoryDisplayName(home));
        model.addAttribute("directoryEntries", listDirectoryEntries(home));
        AppStateView view = appStateService.loadViewData();
        populateProjectModel(model, view);
        return "fragments/projects :: modal";
    }

    @GetMapping("/ui/workspaces/new")
    public String newWorkspaceModal(Model model) {
        AppStateView view = appStateService.loadViewData();
        populateProjectModel(model, view);
        model.addAttribute("branchName", "");
        model.addAttribute("branchMode", "create");
        model.addAttribute("createBranch", true);
        return view.activeProject() == null ? "fragments/projects :: modalClose" : "fragments/projects :: workspaceModal";
    }

    @GetMapping("/ui/projects/directory")
    public String listDirectory(@RequestParam("path") String path, Model model) {
        Path current = Path.of(path).toAbsolutePath().normalize();
        model.addAttribute("directoryEntries", listDirectoryEntries(current));
        model.addAttribute("name", directoryDisplayName(current));
        model.addAttribute("path", current.toString());
        model.addAttribute("selectedPath", current.toString());
        model.addAttribute("selectedName", directoryDisplayName(current));
        model.addAttribute("expanded", true);
        return "fragments/directory-list :: nodeResponse";
    }

    @GetMapping("/ui/projects/directory/collapse")
    public String collapseDirectory(@RequestParam("path") String path, Model model) {
        Path current = Path.of(path).toAbsolutePath().normalize();
        model.addAttribute("name", directoryDisplayName(current));
        model.addAttribute("path", current.toString());
        model.addAttribute("selectedPath", current.toString());
        model.addAttribute("selectedName", directoryDisplayName(current));
        model.addAttribute("expanded", false);
        return "fragments/directory-list :: nodeResponse";
    }

    @GetMapping("/ui/projects/modal/close")
    public String closeProjectModal() {
        return "fragments/projects :: modalClose";
    }

    @GetMapping("/ui/settings")
    public String settingsModal(Model model) {
        AppStateView view = appStateService.loadViewData();
        populateProjectModel(model, view);
        model.addAttribute("lifecycleHookSettings", appStateService.loadLifecycleHookSettings());
        model.addAttribute("autoGitUpdateEnabled", appStateService.loadAutoGitUpdateEnabled());
        model.addAttribute("openAiOAuthView", openAiOAuthService.currentView());
        return "fragments/projects :: settingsModal";
    }

    @GetMapping("/ui/settings/usage")
    public String settingsUsage(@RequestParam(name = "range", defaultValue = "24h") String range, Model model) {
        AppStateView view = appStateService.loadViewData();
        if (view.activeProject() == null) {
            return "fragments/projects :: usageEmpty";
        }
        long hours = switch (range) {
            case "7d" -> 24L * 7;
            case "30d" -> 24L * 30;
            case "60d" -> 24L * 60;
            default -> 24;
        };
        Instant to = Instant.now().truncatedTo(ChronoUnit.HOURS).plus(1, ChronoUnit.HOURS);
        Instant from = to.minus(hours, ChronoUnit.HOURS);
        List<UsagePoint> points = tokenUsageService.findProjectHourlyUsage(view.activeProject().id(), from, to).stream()
                .map(row -> new UsagePoint(row.hourStartUtc().toString(), resolveModelLabel(row.modelKey()), row.modelKey(),
                        row.requestCount(), row.inputTokenCount(), row.outputTokenCount(), row.totalTokenCount()))
                .toList();
        model.addAttribute("usageRange", range.equals("7d") || range.equals("30d") || range.equals("60d") ? range : "24h");
        try {
            model.addAttribute("usageJson", SseJson.writeValueAsString(points));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize usage data", e);
        }
        return "fragments/projects :: settingsUsage";
    }

    @PostMapping("/ui/settings/hooks/apply")
    public String applyLifecycleHookSettings(
            @RequestParam(name = "assistantCompletedScript", required = false) String assistantCompletedScript,
            @RequestParam(name = "assistantErroredScript", required = false) String assistantErroredScript,
            @RequestParam(name = "subagentCompletedScript", required = false) String subagentCompletedScript,
            @RequestParam("timeoutSeconds") int timeoutSeconds,
            Model model) {
        appStateService.updateLifecycleHookSettings(new LifecycleHookSettings(assistantCompletedScript,
                assistantErroredScript, subagentCompletedScript, timeoutSeconds));
        return "fragments/projects :: modalClose";
    }

    @PostMapping("/ui/settings/auto-git-update/apply")
    public String applyAutoGitUpdateSettings(@RequestParam(name = "enabled", defaultValue = "false") boolean enabled) {
        appStateService.updateAutoGitUpdateEnabled(enabled);
        return "fragments/projects :: modalClose";
    }

    @PostMapping("/ui/settings/apply")
    public String applySettings(@RequestParam("workspaceInitCommands") String workspaceInitCommands,
                                @RequestParam(name = "environmentVariableNames", required = false) List<String> environmentVariableNames,
                                @RequestParam(name = "environmentVariableValues", required = false) List<String> environmentVariableValues,
                                Model model) {
        return applySettingsInternal(workspaceInitCommands, environmentVariableNames, environmentVariableValues, model);
    }

    @PostMapping("/ui/settings/mcp/apply")
    public String applyMcpSettings(@RequestParam("mcpCatalogJson") String mcpCatalogJson, Model model) {
        return applyMcpSettingsInternal(mcpCatalogJson, model);
    }

    String applySettingsInternal(String workspaceInitCommands,
                                 List<String> environmentVariableNames,
                                 List<String> environmentVariableValues,
                                 Model model) {
        AppStateView view = appStateService.loadViewData();
        if (view.activeProject() == null) {
            return "fragments/projects :: modalClose";
        }

        List<ProjectEnvironmentVariable> environmentVariables = new ArrayList<>();
        int count = Math.max(environmentVariableNames == null ? 0 : environmentVariableNames.size(), environmentVariableValues == null ? 0 : environmentVariableValues.size());
        for (int i = 0; i < count; i++) {
            String name = environmentVariableNames != null && i < environmentVariableNames.size() ? environmentVariableNames.get(i) : null;
            String value = environmentVariableValues != null && i < environmentVariableValues.size() ? environmentVariableValues.get(i) : null;
            environmentVariables.add(new ProjectEnvironmentVariable(name, value));
        }

        appStateService.updateProjectWorkspaceInitCommands(view.activeProject().id(), workspaceInitCommands);
        appStateService.updateProjectEnvironmentVariables(view.activeProject().id(), environmentVariables);
        reloadMcpRuntimeForProject(view.activeProject().id());
        return "fragments/projects :: modalClose";
    }

    String applyMcpSettingsInternal(String mcpCatalogJson, Model model) {
        AppStateView view = appStateService.loadViewData();
        if (view.activeProject() == null) {
            return "fragments/projects :: modalClose";
        }

        McpCatalogPayload payload;
        try {
            payload = SseJson.readValue(mcpCatalogJson, McpCatalogPayload.class);
        } catch (Exception e) {
            throw new IllegalStateException("Invalid MCP catalog payload", e);
        }

        List<McpServerView> currentServers = appStateService.listMcpServers();
        Map<Long, McpServerView> currentById = new LinkedHashMap<>();
        for (McpServerView server : currentServers) {
            currentById.put(server.id(), server);
        }

        Set<Long> affectedProjects = new LinkedHashSet<>();
        Set<Long> payloadServerIds = payload.serverIds();
        List<McpServerPayload> servers = payload.servers() == null ? List.of() : payload.servers();
        for (McpServerPayload server : servers) {
            List<McpServerHeader> headers = server.headers() == null ? List.of() : server.headers().stream().map(header -> new McpServerHeader(header.name(), header.value())).toList();
            List<Long> exposedProjectIds = server.exposedProjectIds() == null ? List.of() : List.copyOf(server.exposedProjectIds());
            McpServerView saved;
            if (server.id() != null && currentById.containsKey(server.id())) {
                McpServerView current = currentById.get(server.id());
                affectedProjects.addAll(current.exposedProjectIds());
                saved = appStateService.updateMcpServer(server.id(), server.name(), server.url(), server.enabled(), headers, exposedProjectIds);
            } else {
                saved = appStateService.createMcpServer(server.name(), server.url(), server.enabled(), headers, exposedProjectIds);
            }
            affectedProjects.addAll(saved.exposedProjectIds());
        }

        for (McpServerView server : currentServers) {
            if (payloadServerIds.contains(server.id())) {
                continue;
            }
            affectedProjects.addAll(server.exposedProjectIds());
            appStateService.deleteMcpServer(server.id());
        }

        affectedProjects.forEach(this::reloadMcpRuntimeForProject);
        return "fragments/projects :: modalClose";
    }

    private void reloadMcpRuntimeForProject(long projectId) {
        if (mcpRuntimeManager != null) {
            mcpRuntimeManager.reloadProject(projectId);
        }
    }

    @PostMapping("/ui/settings/openai/start")
    public String startOpenAiOAuth(Model model) {
        model.addAttribute("openAiOAuthView", openAiOAuthService.startDeviceAuthorization());
        return "fragments/projects :: openaiOAuthSection";
    }

    @PostMapping("/ui/settings/openai/logout")
    public String logoutOpenAiOAuth(Model model) {
        model.addAttribute("openAiOAuthView", openAiOAuthService.resetConnectionState());
        return "fragments/projects :: openaiOAuthSection";
    }

    @GetMapping("/ui/settings/openai/status")
    public String openAiOAuthStatus(Model model) {
        model.addAttribute("openAiOAuthView", openAiOAuthService.pollCurrentDeviceAuthorization());
        return "fragments/projects :: openaiOAuthSection";
    }

    @PostMapping("/ui/workspaces/active/git/pull")
    public String pullActiveWorkspace(Model model) {
        AppStateView view = appStateService.loadViewData();
        if (view.activeWorkspace() == null) {
            systemBalloonService.publishWarning("Git Pull", "No active workspace is selected.");
            populateProjectModel(model, view);
            return "fragments/projects :: topbar";
        }

        GitAutoUpdateService.UpdateResult result = gitAutoUpdateService.updateWorkspaceManually(view.activeWorkspace().id());
        switch (result.status()) {
            case UPDATED -> systemBalloonService.publishSuccess("Git Pull", "Updated workspace \"" + view.activeWorkspace().name() + "\".");
            case UP_TO_DATE -> systemBalloonService.publishSuccess("Git Pull", "Workspace \"" + view.activeWorkspace().name() + "\" is already up to date.");
            case SKIPPED -> systemBalloonService.publishWarning("Git Pull", result.message());
            case FAILED -> systemBalloonService.publishError("Git Pull", result.message());
        }
        view = appStateService.loadViewData();
        populateProjectModel(model, view);
        populateSessionModel(model, view);
        populateShellUpdates(model, view);
        return "fragments/projects :: shellUpdates";
    }

    @PostMapping("/ui/projects/add")
    public String addProject(@RequestParam("name") String name, @RequestParam("path") String path, Model model) {
        appStateService.addOrReopenProject(name, Path.of(path).toAbsolutePath().normalize().toString());
        AppStateView view = appStateService.loadViewData();
        populateProjectModel(model, view);
        populateSessionModel(model, view);
        populateShellUpdates(model, view);
        return "fragments/projects :: shellUpdates";
    }

    @PostMapping("/ui/workspaces/add")
    public String addWorkspace(@RequestParam("branchName") String branchName,
                               @RequestParam(name = "branchMode", defaultValue = "create") String branchMode,
                               Model model) {
        AppStateView view = appStateService.loadViewData();
        String trimmedBranchName = branchName.trim();
        BranchMode parsedBranchMode = BranchMode.fromValue(branchMode);
        boolean createBranch = parsedBranchMode == BranchMode.CREATE;

        try {
            appStateService.createWorkspace(view.activeProject().id(), trimmedBranchName, createBranch);
        } catch (InvalidGitBranchNameException e) {
            if (createBranch) {
                String gitOutput = e.gitOutput();
                String body = e.getMessage();
                if (gitOutput != null && !gitOutput.isBlank()) {
                    body += "\n\n" + gitOutput;
                }
                systemBalloonService.publishError("Invalid Branch Name", body);
                populateProjectModel(model, view);
                populateSessionModel(model, view);
                model.addAttribute("branchName", trimmedBranchName);
                model.addAttribute("branchMode", parsedBranchMode.value());
                model.addAttribute("createBranch", true);
                model.addAttribute("modalOob", true);
                return "fragments/projects :: workspaceModal";
            }
            throw e;
        } catch (GitWorktreeException e) {
            if (!createBranch) {
                String gitOutput = e.lastGitOutputLines();
                if (gitOutput == null || gitOutput.isBlank()) {
                    gitOutput = e.getMessage();
                }
                String body = "Could not check out existing Git branch \"" + trimmedBranchName + "\".\n\n" + gitOutput;
                systemBalloonService.publishError("Checkout Failed", body);
                populateProjectModel(model, view);
                populateSessionModel(model, view);
                model.addAttribute("branchName", trimmedBranchName);
                model.addAttribute("branchMode", parsedBranchMode.value());
                model.addAttribute("createBranch", false);
                model.addAttribute("modalOob", true);
                return "fragments/projects :: workspaceModal";
            }
            throw e;
        }

        view = appStateService.loadViewData();
        String workspaceInitCommands = view.activeProject().workspaceInitCommands();
        if (workspaceInitCommands != null && !workspaceInitCommands.isBlank()) {
            TerminalHandle terminal = terminalManager.createTerminal(view.activeWorkspace().path(), "Workspace Init", activeProjectEnvironmentVariables(view));
            terminalStateService.registerTerminal(view.activeWorkspace().id(), terminal);
            terminalStateService.openTerminalPane(view.activeWorkspace().id());
            terminalManager.write(terminal.id(), workspaceInitCommands.endsWith("\n") ? workspaceInitCommands : workspaceInitCommands + "\n");
        }
        populateProjectModel(model, view);
        populateSessionModel(model, view);
        populateShellUpdates(model, view);
        return "fragments/projects :: shellUpdates";
    }

    @PostMapping("/ui/projects/{projectId}/activate")
    public String activateProject(@PathVariable long projectId, Model model) {
        appStateService.activateProject(projectId);
        AppStateView view = appStateService.loadViewData();
        populateProjectModel(model, view);
        populateSessionModel(model, view);
        populateShellUpdates(model, view);
        return "fragments/projects :: shellUpdates";
    }

    @PostMapping("/ui/projects/{projectId}/close")
    public String closeProject(@PathVariable long projectId, Model model) {
        appStateService.closeProject(projectId);
        AppStateView view = appStateService.loadViewData();
        populateProjectModel(model, view);
        populateSessionModel(model, view);
        populateShellUpdates(model, view);
        return "fragments/projects :: shellUpdates";
    }

    @PostMapping("/ui/workspaces/{workspaceId}/activate")
    public String activateWorkspace(@PathVariable long workspaceId, Model model) {
        appStateService.activateWorkspace(workspaceId);
        AppStateView view = appStateService.loadViewData();
        populateProjectModel(model, view);
        populateSessionModel(model, view);
        populateShellUpdates(model, view);
        return "fragments/projects :: shellUpdates";
    }

    @PostMapping("/ui/workspaces/{workspaceId}/collapse")
    public String collapseWorkspace(@PathVariable long workspaceId, Model model) {
        appStateService.collapseWorkspace(workspaceId);
        AppStateView view = appStateService.loadViewData();
        populateProjectModel(model, view);
        populateSessionModel(model, view);
        populateShellUpdates(model, view);
        return "fragments/projects :: shellUpdates";
    }

    @PostMapping("/ui/workspaces/{workspaceId}/close")
    public String closeWorkspace(@PathVariable long workspaceId, Model model) {
        AppStateView view = appStateService.loadViewData();
        AppStateService.WorkspaceCloseInspection inspection = appStateService.inspectWorkspaceClose(workspaceId);
        if (inspection.uncommittedChanges() || inspection.unpushedCommits()) {
            populateProjectModel(model, view);
            populateSessionModel(model, view);
            populateWorkspaceCloseModel(model, inspection);
            return "fragments/projects :: workspaceCloseModal";
        }

        appStateService.removeWorkspaceWorktree(workspaceId, false);
        appStateService.closeWorkspace(workspaceId);
        view = appStateService.loadViewData();
        populateProjectModel(model, view);
        populateSessionModel(model, view);
        populateShellUpdates(model, view);
        return "fragments/projects :: shellUpdates";
    }

    @PostMapping("/ui/workspaces/{workspaceId}/close/confirm")
    public String confirmWorkspaceClose(@PathVariable long workspaceId, Model model) {
        appStateService.removeWorkspaceWorktree(workspaceId, true);
        appStateService.closeWorkspace(workspaceId);
        AppStateView view = appStateService.loadViewData();
        populateProjectModel(model, view);
        populateSessionModel(model, view);
        populateShellUpdates(model, view);
        return "fragments/projects :: shellUpdates";
    }

    @PostMapping("/ui/sessions/{sessionId}/activate")
    public String activateSession(@PathVariable long sessionId, Model model) {
        appStateService.activateSession(sessionId);
        AppStateView view = appStateService.loadViewData();
        populateProjectModel(model, view);
        populateSessionModel(model, view);
        populateShellUpdates(model, view);
        return "fragments/projects :: shellUpdates";
    }

    @PostMapping("/ui/sessions/{sessionId}/close")
    public String closeSession(@PathVariable long sessionId, Model model) {
        appStateService.closeSession(sessionId);
        AppStateView view = appStateService.loadViewData();
        populateProjectModel(model, view);
        populateSessionModel(model, view);
        populateShellUpdates(model, view);
        return "fragments/projects :: shellUpdates";
    }

    @PostMapping("/ui/sessions/{sessionId}/draft")
    public ResponseEntity<Void> updateSessionDraft(@PathVariable long sessionId, @RequestParam("draft") String draft) {
        appStateService.updateSessionDraft(sessionId, draft);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/ui/sessions/add")
    public String addSession(@RequestParam("name") String name, Model model) {
        AppStateView view = appStateService.loadViewData();
        appStateService.createSession(view.activeWorkspace().id(), name);
        view = appStateService.loadViewData();
        populateProjectModel(model, view);
        populateSessionModel(model, view);
        populateShellUpdates(model, view);
        return "fragments/projects :: shellUpdates";
    }

    @GetMapping("/ui/sessions/new")
    public String newSessionForm() {
        return "fragments/projects :: newSessionForm";
    }

    @GetMapping("/ui/workspaces/rail")
    public String workspaceRail(Model model) {
        AppStateView view = appStateService.loadViewData();
        populateProjectModel(model, view);
        return "fragments/projects :: workspaceRail";
    }

    @GetMapping("/ui/sessions/new/button")
    public String newSessionButton() {
        return "fragments/projects :: newSessionButton";
    }

    private void populateSessionModel(Model model, AppStateView view) {
        SessionView session = view.activeSession();
        SessionDetailView detail = view.activeSessionDetail();
        TerminalPanelState terminalState = view.activeWorkspace() == null ? new TerminalPanelState("none", List.of(), null, false) : terminalStateService.snapshot(view.activeWorkspace().id());
        if (session == null) {
            model.addAttribute("chatMessages", List.of());
            model.addAttribute("subagentView", false);
            model.addAttribute("changedFiles", List.of());
            model.addAttribute("reviewPanelOpen", false);
            model.addAttribute("reviewSource", null);
            model.addAttribute("selectedFile", null);
            model.addAttribute("hasPending", false);
            model.addAttribute("reviewOob", false);
            model.addAttribute("workspaceRoot", view.activeWorkspace() == null ? null : view.activeWorkspace().path());
            model.addAttribute("chatDraft", "");
            model.addAttribute("forkSessionId", null);
            model.addAttribute("terminalTabs", terminalState.terminalTabs());
            model.addAttribute("activeTerminal", terminalState.activeTerminal());
            model.addAttribute("bottomPanelMode", terminalState.bottomPanelMode());
            model.addAttribute("bottomPanelOpen", terminalState.bottomPanelOpen());
            model.addAttribute("terminalPanelOpen", terminalState.bottomPanelOpen());
            model.addAttribute("panelMode", terminalState.bottomPanelMode());
            return;
        }

        boolean hasPending = detail.chatMessages().stream().anyMatch(ChatMessageView::pending);
        model.addAttribute("chatMessages", detail.chatMessages().stream().map(this::toChatMessage).toList());
        model.addAttribute("subagentView", false);
        model.addAttribute("activeSession", toSession(session));
        model.addAttribute("changedFiles", detail.changedFiles().stream().map(this::toChangedFile).toList());
        model.addAttribute("reviewPanelOpen", detail.reviewPanelOpen());
        model.addAttribute("reviewSource", detail.reviewSource());
        model.addAttribute("selectedFile", toChangedFile(detail.selectedFile()));
        model.addAttribute("hasPending", hasPending);
        model.addAttribute("reviewOob", !hasPending && detail.reviewPanelOpen());
        model.addAttribute("workspaceRoot", detail.workspaceRoot());
        model.addAttribute("chatDraft", detail.chatDraft());
        model.addAttribute("forkSessionId", session.id());
        model.addAttribute("terminalTabs", terminalState.terminalTabs());
        model.addAttribute("activeTerminal", terminalState.activeTerminal());
        model.addAttribute("bottomPanelMode", terminalState.bottomPanelMode());
        model.addAttribute("bottomPanelOpen", terminalState.bottomPanelOpen());
        model.addAttribute("terminalPanelOpen", terminalState.bottomPanelOpen());
        model.addAttribute("panelMode", terminalState.bottomPanelMode());
    }

    private void populateChatModel(Model model, List<ChatMessageView> chatMessages, boolean subagentView, String subagentAgentName, String subagentAgentId,
                                    Long subagentSessionId) {
        model.addAttribute("chatMessages", chatMessages.stream().map(this::toChatMessage).toList());
        model.addAttribute("hasPending", chatMessages.stream().anyMatch(ChatMessageView::pending));
        model.addAttribute("reviewOob", false);
        model.addAttribute("shellRefresh", false);
        model.addAttribute("subagentView", subagentView);
        model.addAttribute("subagentAgentName", subagentAgentName);
        model.addAttribute("subagentAgentId", subagentAgentId);
        model.addAttribute("subagentSessionId", subagentSessionId);
        model.addAttribute("forkSessionId", null);
    }

    private boolean isTerminalPanelOpen(AppStateView view) {
        return view.activeWorkspace() != null && terminalStateService.snapshot(view.activeWorkspace().id()).bottomPanelOpen();
    }

    private void populateProjectModel(Model model, AppStateView view) {
        List<Project> projects = view.projects().stream().map(this::toProject).toList();
        model.addAttribute("projects", projects);
        model.addAttribute("visibleProjects", projects);
        model.addAttribute("mcpServers", appStateService.listMcpServers());
        model.addAttribute("activeProject", toProject(view.activeProject()));
        model.addAttribute("workspaces", view.workspaces().stream().map(this::toWorkspace).toList());
        model.addAttribute("workspaceActions", view.workspaces().stream().map(workspace -> toWorkspaceAction(view.activeProject(), workspace)).toList());
        model.addAttribute("activeWorkspace", toWorkspace(view.activeWorkspace()));
        model.addAttribute("sessions", view.sessions().stream().map(this::toSession).toList());
        model.addAttribute("activeSession", toSession(view.activeSession()));
        model.addAttribute("reviewPanelOpen", view.activeSessionDetail() != null && view.activeSessionDetail().reviewPanelOpen());
        model.addAttribute("shellRefresh", false);
        model.addAttribute("includeChatContainer", false);
        model.addAttribute("appVersion", appVersion);
    }

    private Map<String, String> projectEnvironmentVariables(AppStateView view) {
        return appStateService.loadProjectEnvironmentVariables(view.activeProject().id());
    }

    private void populateShellUpdates(Model model, AppStateView view) {
        model.addAttribute("terminalOob", true);
        model.addAttribute("shellRefresh", true);
        model.addAttribute("includeChatContainer", true);
        model.addAttribute("reviewOob", true);
        model.addAttribute("activeSession", toSession(view.activeSession()));
        populateChatControlsModel(model, activeChatSelection(view));
    }

    private void sendInitialMcpFailureBalloons(SseEmitter emitter) {
        if (mcpRuntimeManager == null) {
            return;
        }

        AppStateView view = appStateService.loadViewData();
        ProjectView project = view.activeProject();
        if (project == null) {
            return;
        }

        Map<Long, McpRuntimeEvents.ConnectionStatus> statuses = mcpRuntimeManager.connectionStatuses(project.id());
        if (statuses.isEmpty()) {
            return;
        }

        Map<Long, McpServerView> serversById = new LinkedHashMap<>();
        for (McpServerView server : appStateService.loadEnabledMcpServersForProject(project.id())) {
            serversById.put(server.id(), server);
        }

        for (Map.Entry<Long, McpRuntimeEvents.ConnectionStatus> entry : statuses.entrySet()) {
            if (entry.getValue() != McpRuntimeEvents.ConnectionStatus.FAILED) {
                continue;
            }

            McpServerView server = serversById.get(entry.getKey());
            if (server == null) {
                continue;
            }

            systemBalloonService.publishWarning(emitter,
                    "MCP server failed: " + project.name() + " / " + server.name(),
                    "An MCP server is unavailable for the active project.");
        }
    }

    private ChatSelection activeChatSelection(AppStateView view) {
        if (view != null && view.activeSessionDetail() != null) {
            ChatSelection selection = latestAssistantChatSelection(view.activeSessionDetail());
            if (selection != null) {
                return selection;
            }
        }
        return defaultChatSelection();
    }

    private ChatSelection latestAssistantChatSelection(SessionDetailView detail) {
        if (detail == null) {
            return null;
        }
        for (int i = detail.chatMessages().size() - 1; i >= 0; i--) {
            ChatMessageView message = detail.chatMessages().get(i);
            if (!"assistant".equals(message.role()) || message.metadata() == null) {
                continue;
            }
            ChatMessageMetadata metadata = message.metadata();
            AgentDefinition selectedAgent = agentDefinitionService.resolveOrDefault(metadata.agentId());
            ModelDefinition selectedModel = modelCatalogService.resolveOrDefault(metadata.modelId());
            ThinkingLevel selectedThinking = resolveThinkingLevelOrDefault(metadata.thinkingLevel(), selectedAgent.defaultThinkingLevel());
            AgentDefinition defaultAgent = agentDefinitionService.defaultAgent();
            ModelDefinition defaultModel = modelCatalogService.resolveOrDefault(defaultAgent.defaultModel());
            return new ChatSelection(selectedAgent, selectedModel, selectedThinking, defaultAgent, defaultModel, defaultAgent.defaultThinkingLevel());
        }
        return null;
    }

    private void populateChatControlsModel(Model model, ChatSelection selection) {
        model.addAttribute("agents", agentDefinitionService.listPrimaryAgents());
        model.addAttribute("models", modelCatalogService.list());
        model.addAttribute("thinkingLevels", List.of(ThinkingLevel.values()));
        model.addAttribute("defaultAgent", selection.defaultAgent());
        model.addAttribute("defaultModel", selection.defaultModel());
        model.addAttribute("defaultThinking", selection.defaultThinking());
        model.addAttribute("selectedAgent", selection.selectedAgent());
        model.addAttribute("selectedModel", selection.selectedModel());
        model.addAttribute("selectedThinking", selection.selectedThinking());
    }

    private ChatSelection defaultChatSelection() {
        AgentDefinition defaultAgent = agentDefinitionService.defaultAgent();
        ModelDefinition defaultModel = modelCatalogService.resolveOrDefault(defaultAgent.defaultModel());
        return new ChatSelection(defaultAgent, defaultModel, defaultAgent.defaultThinkingLevel(), defaultAgent, defaultModel, defaultAgent.defaultThinkingLevel());
    }

    private ChatSelection resolveChatSelection(String agentId, String modelId, String thinkingLevel) {
        AgentDefinition defaultAgent = agentDefinitionService.defaultAgent();
        AgentDefinition selectedAgent = agentId == null || agentId.isBlank() ? defaultAgent : agentDefinitionService.getRequired(agentId);
        ModelDefinition selectedModel = modelId == null || modelId.isBlank() ? modelCatalogService.resolveOrDefault(selectedAgent.defaultModel()) : modelCatalogService.getRequired(modelId);
        ThinkingLevel selectedThinking = thinkingLevel == null || thinkingLevel.isBlank() ? selectedAgent.defaultThinkingLevel() : ThinkingLevel.fromValue(thinkingLevel);
        ModelDefinition defaultModel = modelCatalogService.resolveOrDefault(defaultAgent.defaultModel());
        return new ChatSelection(selectedAgent, selectedModel, selectedThinking, defaultAgent, defaultModel, defaultAgent.defaultThinkingLevel());
    }

    private ThinkingLevel resolveThinkingLevelOrDefault(String value, ThinkingLevel fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return ThinkingLevel.fromValue(value);
        } catch (IllegalArgumentException e) {
            log.warn("Ignoring stale chat thinking level metadata '{}'; using {}", value, fallback);
            return fallback;
        }
    }

    private void populateWorkspaceCloseModel(Model model, AppStateService.WorkspaceCloseInspection inspection) {
        model.addAttribute("workspace", new Workspace(inspection.workspaceId(), inspection.workspaceName(), inspection.workspacePath(), false, RailStatus.NONE));
        model.addAttribute("workspaceId", inspection.workspaceId());
        model.addAttribute("workspaceName", inspection.workspaceName());
        model.addAttribute("workspacePath", inspection.workspacePath());
        model.addAttribute("workspaceCloseStatus", inspection);
        model.addAttribute("workspaceCloseReasons", inspection.reasons());
        model.addAttribute("modalTarget", "#modal-root");
    }

    private AppStateView currentViewWithSessionIfNeeded(boolean createIfMissing) {
        AppStateView view = appStateService.loadViewData();
        if (createIfMissing && view.activeSession() == null) {
            appStateService.ensureChatSession(agentProperties.getWorkspaceRoot());
            view = appStateService.loadViewData();
        }
        return view;
    }

    private List<DirectoryEntry> listDirectoryEntries(Path path) {
        try (var stream = Files.list(path)) {
            return stream
                    .filter(Files::isDirectory)
                    .map(entry -> new DirectoryEntry(entry.getFileName().toString(), entry.toString(), Files.isDirectory(entry)))
                    .sorted(Comparator.comparing((DirectoryEntry entry) -> entry.name().startsWith(".")).thenComparing(DirectoryEntry::name, String.CASE_INSENSITIVE_ORDER).thenComparing(DirectoryEntry::name))
                    .toList();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
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
            pb.directory(new File(root.toAbsolutePath().normalize().toString()));
            Process p = pb.start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
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
        String requestedModelId = request.getModelId();
        if (requestedModelId == null || requestedModelId.isBlank()) {
            requestedModelId = agent == null ? agentProperties.getModel() : agent.defaultModel();
        }
        return modelCatalogService.getRequired(requestedModelId);
    }

    String resolveModelLabel(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            return null;
        }
        try {
            return modelCatalogService.getRequired(modelId).displayName();
        } catch (RuntimeException ignored) {
            return modelId;
        }
    }

    private ChatMessage toChatMessage(ChatMessageView view) {
        return chatPresentationService.toChatMessage(view, this::resolveModelLabel);
    }

    public record UsagePoint(String hour, String modelLabel, String modelKey, long requests, Long input, Long output, Long total) {}

    private ChangedFile toChangedFile(ChangedFileView view) {
        return view == null ? null : new ChangedFile(view.key(), view.source(), view.id(), view.path(), view.diff());
    }

    private Project toProject(ProjectView view) {
        return view == null ? null : new Project(view.id(), view.name(), view.path(), view.workspaceInitCommands(), view.environmentVariables());
    }

    private Map<String, String> activeProjectEnvironmentVariables(AppStateView view) {
        return appStateService.loadProjectEnvironmentVariables(view.activeProject().id());
    }

    private Workspace toWorkspace(WorkspaceView view) {
        return view == null ? null : new Workspace(view.id(), view.name(), view.path(), view.unread(), view.railStatus());
    }

    private WorkspaceAction toWorkspaceAction(ProjectView activeProject, WorkspaceView workspace) {
        boolean defaultWorkspace = activeProject != null && workspace.path().equals(activeProject.path());
        return new WorkspaceAction(workspace.id(), defaultWorkspace, !defaultWorkspace);
    }

    private Session toSession(SessionView view) {
        return view == null ? null : new Session(view.id(), view.name(), view.unread(), view.railStatus());
    }

    private ToolCallTraceInput toToolCallTraceInput(ToolCallTrace trace) {
        return new ToolCallTraceInput(trace.getToolCallId(), trace.getToolName(), trace.getArgs(), trace.isSuccess(), trace.getTextSummary(), trace.getMachineSummary());
    }

    private String directoryDisplayName(Path path) {
        Path fileName = path.getFileName();
        return fileName == null ? path.toString() : fileName.toString();
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

    private record McpCatalogPayload(List<McpServerPayload> servers) {
        private Set<Long> serverIds() {
            if (servers == null) {
                return Set.of();
            }
            Set<Long> ids = new LinkedHashSet<>();
            for (McpServerPayload server : servers) {
                if (server.id() != null) {
                    ids.add(server.id());
                }
            }
            return ids;
        }
    }

    private record McpServerPayload(Long id, String name, String url, boolean enabled, List<McpServerHeaderPayload> headers, List<Long> exposedProjectIds) {
    }

    private record McpServerHeaderPayload(String name, String value) {
    }

    private enum BranchMode {
        CREATE("create"),
        CHECKOUT("checkout");

        private final String value;

        BranchMode(String value) {
            this.value = value;
        }

        String value() {
            return value;
        }

        static BranchMode fromValue(String value) {
            String normalized = value.trim().toLowerCase();
            return switch (normalized) {
                case "create" -> CREATE;
                case "checkout" -> CHECKOUT;
                default -> throw new IllegalArgumentException("Invalid branch mode: " + value);
            };
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ProviderErrorPayload(ProviderError error, ProviderDetail detail) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ProviderError(String message, String code) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ProviderDetail(String message, String code) {}

    public record ChangedFile(String key, ReviewSource source, Integer id, String path, String diff) {}

    public record Project(long id, String name, String path, String workspaceInitCommands, List<ProjectEnvironmentVariable> environmentVariables) {
    }

    public record Workspace(long id, String name, String path, boolean unread, RailStatus railStatus) {

        public boolean inProgress() {
            return railStatus == RailStatus.IN_PROGRESS;
        }

        public boolean failed() {
            return railStatus == RailStatus.FAILED;
        }
    }

    public record WorkspaceAction(long id, boolean defaultWorkspace, boolean deletable) {}

    public record Session(long id, String name, boolean unread, RailStatus railStatus) {

        public boolean inProgress() {
            return railStatus == RailStatus.IN_PROGRESS;
        }

        public boolean failed() {
            return railStatus == RailStatus.FAILED;
        }
    }

    public record DirectoryEntry(String name, String path, boolean directory) {}

    private record ChatSelection(AgentDefinition selectedAgent, ModelDefinition selectedModel, ThinkingLevel selectedThinking,
                                  AgentDefinition defaultAgent, ModelDefinition defaultModel, ThinkingLevel defaultThinking) {}

    private record PendingStream(long sessionId, String workspaceRoot, AgentTurnRequest request) {}

    private record ActiveStream(PendingStream pendingStream, CopyOnWriteArrayList<SseEmitter> emitters, AtomicBoolean started,
                                AtomicBoolean finished, AtomicBoolean completed, AtomicReference<Thread> runner,
                                CancellationToken cancellationToken, AtomicReference<StringBuilder> accumulatedText) {
        private static ActiveStream create(PendingStream pendingStream, CancellationToken cancellationToken) {
            return new ActiveStream(pendingStream, new CopyOnWriteArrayList<>(), new AtomicBoolean(false), new AtomicBoolean(false),
                    new AtomicBoolean(false), new AtomicReference<>(), cancellationToken, new AtomicReference<>(new StringBuilder()));
        }
    }
}
