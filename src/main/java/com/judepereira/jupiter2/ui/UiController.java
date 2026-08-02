package com.judepereira.jupiter2.ui;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.judepereira.jupiter2.agent.config.AgentProperties;
import com.judepereira.jupiter2.agent.catalog.AgentDefinition;
import com.judepereira.jupiter2.agent.catalog.AgentDefinitionService;
import com.judepereira.jupiter2.agent.catalog.ModelCatalogService;
import com.judepereira.jupiter2.agent.catalog.ModelDefinition;
import com.judepereira.jupiter2.agent.catalog.ThinkingLevel;
import com.judepereira.jupiter2.agent.harness.AgentTurnRequest;
import com.judepereira.jupiter2.agent.harness.AgentTurnResult;
import com.judepereira.jupiter2.agent.harness.CodingAgentHarness;
import com.judepereira.jupiter2.agent.harness.ToolCallTrace;
import com.judepereira.jupiter2.agent.llm.dto.Message;
import com.judepereira.jupiter2.agent.llm.AgentStreamListener;
import com.judepereira.jupiter2.agent.tools.impl.FileUtils;
import com.judepereira.jupiter2.persistence.AppStateService;
import com.judepereira.jupiter2.persistence.ContextCompactionService;
import com.judepereira.jupiter2.persistence.GitWorktreeException;
import com.judepereira.jupiter2.persistence.InvalidGitBranchNameException;
import com.judepereira.jupiter2.persistence.Persistence.AppStateView;
import com.judepereira.jupiter2.persistence.Persistence.ChangedFileDraft;
import com.judepereira.jupiter2.persistence.Persistence.ChangedFileView;
import com.judepereira.jupiter2.persistence.Persistence.ChatMessageView;
import com.judepereira.jupiter2.persistence.Persistence.ChatMessageMetadata;
import com.judepereira.jupiter2.persistence.Persistence.ProjectView;
import com.judepereira.jupiter2.persistence.Persistence.QueuedChatTurn;
import com.judepereira.jupiter2.persistence.Persistence.ReviewSource;
import com.judepereira.jupiter2.persistence.Persistence.SubagentSessionDetailView;
import com.judepereira.jupiter2.persistence.Persistence.SessionDetailView;
import com.judepereira.jupiter2.persistence.Persistence.SessionView;
import com.judepereira.jupiter2.persistence.Persistence.ToolCallTraceInput;
import com.judepereira.jupiter2.persistence.Persistence.WorkspaceView;
import com.judepereira.jupiter2.openai.oauth.OpenAiOAuthService;
import com.judepereira.jupiter2.terminal.TerminalManager;
import com.judepereira.jupiter2.terminal.TerminalHandle;
import com.judepereira.jupiter2.terminal.TerminalPanelState;
import com.judepereira.jupiter2.terminal.TerminalStateService;
import com.judepereira.jupiter2.ui.balloon.SystemBalloonService;
import com.judepereira.jupiter2.ui.rail.WorkspaceRailRefreshService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

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
    private final TerminalManager terminalManager;
    private final TerminalStateService terminalStateService;
    private final SystemBalloonService systemBalloonService;
    private final WorkspaceRailRefreshService workspaceRailRefreshService;
    private final OpenAiOAuthService openAiOAuthService;
    private final String appVersion;

    private final ConcurrentMap<String, ActiveStream> activeStreams = new ConcurrentHashMap<>();

    @Autowired
    public UiController(CodingAgentHarness harness, AgentProperties agentProperties, AppStateService appStateService,
                        AgentDefinitionService agentDefinitionService, ModelCatalogService modelCatalogService,
                        SystemBalloonService systemBalloonService, WorkspaceRailRefreshService workspaceRailRefreshService,
                        TerminalManager terminalManager,
                        TerminalStateService terminalStateService, OpenAiOAuthService openAiOAuthService,
                        ContextCompactionService contextCompactionService,
                        @Value("${app.version:" + DEFAULT_APP_VERSION + "}") String appVersion) {
        this.harness = harness;
        this.agentProperties = agentProperties;
        this.appStateService = appStateService;
        this.agentDefinitionService = agentDefinitionService;
        this.modelCatalogService = modelCatalogService;
        this.contextCompactionService = contextCompactionService;
        this.systemBalloonService = systemBalloonService;
        this.terminalManager = terminalManager;
        this.terminalStateService = terminalStateService;
        this.workspaceRailRefreshService = workspaceRailRefreshService;
        this.openAiOAuthService = openAiOAuthService;
        this.appVersion = appVersion;
    }

    @GetMapping("/")
    public String index(Model model) {
        AppStateView view = appStateService.loadViewData();
        populateChatControlsModel(model, defaultChatSelection());
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
            shellRefresh = view.activeSession() == null;
            session = appStateService.ensureChatSession(agentProperties.getWorkspaceRoot());
            String user = message.trim();
            String assistantId = UUID.randomUUID().toString();
            String userId = UUID.randomUUID().toString();
            ChatMessageMetadata metadata = new ChatMessageMetadata(selected.selectedAgent().id(), selected.selectedAgent().name(), selected.selectedModel().id(), selected.selectedThinking().name());
            Optional<ChatMessageView> summaryMessage = contextCompactionService.compactIfNeeded(session.id(), selected.selectedAgent(), selected.selectedModel(),
                    selected.selectedThinking(), agentProperties.getWorkspaceRoot(), user);
            summaryMessage.ifPresent(summary -> newChatMessages.add(toChatMessage(summary)));
            QueuedChatTurn queued = appStateService.appendUserMessageAndPendingAssistant(session.id(), userId, assistantId, user, metadata);
            newChatMessages.add(toChatMessage(queued.userMessage()));
            newChatMessages.add(toChatMessage(queued.assistantMessage()));

            view = appStateService.loadViewData();
            SessionDetailView sessionDetail = view.activeSessionDetail();
            String workspaceRoot = sessionDetail.workspaceRoot();
            List<Message> conversationHistory = new ArrayList<>();
            conversationHistory.addAll(appStateService.buildConversationHistory(session.id()));
            activeStreams.put(assistantId, new ActiveStream(new PendingStream(session.id(), workspaceRoot,
                    new AgentTurnRequest(null, conversationHistory, workspaceRoot,
                            selected.selectedAgent().id(), selected.selectedModel().id(), selected.selectedThinking(), session.id()))));
        }

        populateChatControlsModel(model, selected);
        populateProjectModel(model, view);
        populateSessionModel(model, view);
        model.addAttribute("newChatMessages", List.copyOf(newChatMessages));
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
        if (active.started().compareAndSet(false, true)) {
            startActiveStream(assistantId, active, emitter);
        }

        return emitter;
    }

    private void startActiveStream(String assistantId, ActiveStream active, SseEmitter emitter) {
        try {
            Thread.startVirtualThread(() -> runActiveStream(assistantId, active));
        } catch (Throwable t) {
            active.started().set(false);
            Exception e = t instanceof Exception exception ? exception : new RuntimeException(t);
            listenerStartFailed(active, assistantId, e, emitter);
        }
    }

    private void runActiveStream(String assistantId, ActiveStream active) {
        PendingStream pending = active.pendingStream();
        AtomicBoolean completed = new AtomicBoolean(false);
        StringBuilder accumulated = new StringBuilder();

        AgentStreamListener listener = new AgentStreamListener() {
            @Override
            public void onTextDelta(String delta) {
                try {
                    if (delta == null) {
                        return;
                    }
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
                broadcastEvent(active, assistantId, "tool_call_started", trace);
            }

            @Override
            public void onToolCallProgress(String toolCallId, String toolName, String eventName, Object payload) {
                broadcastEvent(active, assistantId, "tool_call_progress", Map.of(
                        "toolCallId", toolCallId,
                        "toolName", toolName,
                        "eventName", eventName,
                        "payload", payload
                ));
            }

            @Override
            public void onToolCallTrace(ToolCallTrace trace) {
                try {
                    ToolCallView v = toToolCallView(appStateService.appendToolCallTrace(pending.sessionId(), assistantId, toToolCallTraceInput(trace)));
                    broadcastEvent(active, assistantId, "tool_call", v);
                } catch (Exception e) {
                    onError(e);
                }
            }

            @Override
            public List<Message> onBeforeModelRequest(AgentTurnRequest currentRequest, List<Message> conversation) {
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
                    processChangedFiles(result, pending.sessionId(), pending.workspaceRoot());
                    finalizeStreamSuccess(active, assistantId, completedMessage, completed);
                } catch (Exception e) {
                    onError(e);
                }
            }

            @Override
            public void onError(Exception e) {
                try {
                    String normalizedMessage = normalizeProviderErrorMessage(e);
                    appStateService.failAssistantMessage(pending.sessionId(), assistantId, "Agent execution failed: " + normalizedMessage);
                    log.error("Execution failure!", e);
                    finalizeStreamError(active, assistantId, normalizedMessage, e, completed);
                } catch (Exception ignored) {
                }
            }
        };

        try {
            AgentTurnResult result = harness.runTurnStreaming(pending.request(), listener);
            if (!completed.get()) {
                listener.onComplete(result);
            }
        } catch (Exception e) {
            listener.onError(e);
        }
    }

    private void listenerStartFailed(ActiveStream active, String assistantId, Exception e, SseEmitter emitter) {
        try {
            String normalizedMessage = normalizeProviderErrorMessage(e);
            appStateService.failAssistantMessage(active.pendingStream().sessionId(), assistantId, "Agent execution failed: " + normalizedMessage);
            log.error("Execution failure!", e);
            broadcastEvent(active, assistantId, "error", Map.of("message", normalizedMessage));
        } catch (Exception ignored) {
        } finally {
            active.finished().set(true);
            activeStreams.remove(assistantId, active);
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
        broadcastEvent(active, assistantId, "done", Map.of("text", completedMessage.text(), "toolCalls", completedMessage.toolCalls()));
        completeEmitters(active);
    }

    private void finalizeStreamError(ActiveStream active, String assistantId, String normalizedMessage, Exception e, AtomicBoolean completed) {
        if (!completed.compareAndSet(false, true)) {
            return;
        }

        active.finished().set(true);
        activeStreams.remove(assistantId, active);
        broadcastEvent(active, assistantId, "error", Map.of("message", normalizedMessage));
        completeEmitters(active);
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

    @GetMapping("/ui/chat/primary")
    public String loadPrimaryChat(Model model) {
        AppStateView view = appStateService.loadViewData();
        if (view.activeSession() == null || view.activeSessionDetail() == null) {
            throw new IllegalStateException("No active primary session");
        }

        populateChatControlsModel(model, defaultChatSelection());
        populateChatModel(model, view.activeSessionDetail().chatMessages(), false, null, null, null);
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

    @GetMapping("/ui/system-balloons/stream")
    public SseEmitter systemBalloonStream() {
        return systemBalloonService.connect();
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
                    TerminalHandle terminal = terminalManager.createTerminal(view.activeWorkspace().path());
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
            TerminalHandle terminal = terminalManager.createTerminal(view.activeWorkspace().path());
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
        if (view.activeProject() == null) {
            return "fragments/projects :: modalClose";
        }

        populateProjectModel(model, view);
        model.addAttribute("openAiOAuthView", openAiOAuthService.currentView());
        return "fragments/projects :: settingsModal";
    }

    @PostMapping("/ui/settings/apply")
    public String applySettings(@RequestParam("workspaceInitCommands") String workspaceInitCommands, Model model) {
        AppStateView view = appStateService.loadViewData();
        if (view.activeProject() == null) {
            return "fragments/projects :: modalClose";
        }

        appStateService.updateProjectWorkspaceInitCommands(view.activeProject().id(), workspaceInitCommands);
        return "fragments/projects :: modalClose";
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
            TerminalHandle terminal = terminalManager.createTerminal(view.activeWorkspace().path(), "Workspace Init");
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
        model.addAttribute("changedFiles", detail.changedFiles().stream().map(this::toChangedFile).toList());
        model.addAttribute("reviewPanelOpen", detail.reviewPanelOpen());
        model.addAttribute("reviewSource", detail.reviewSource());
        model.addAttribute("selectedFile", toChangedFile(detail.selectedFile()));
        model.addAttribute("hasPending", hasPending);
        model.addAttribute("reviewOob", !hasPending && detail.reviewPanelOpen());
        model.addAttribute("workspaceRoot", detail.workspaceRoot());
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
    }

    private boolean isTerminalPanelOpen(AppStateView view) {
        return view.activeWorkspace() != null && terminalStateService.snapshot(view.activeWorkspace().id()).bottomPanelOpen();
    }

    private void populateProjectModel(Model model, AppStateView view) {
        model.addAttribute("projects", view.projects().stream().map(this::toProject).toList());
        model.addAttribute("activeProject", toProject(view.activeProject()));
        model.addAttribute("workspaces", view.workspaces().stream().map(this::toWorkspace).toList());
        model.addAttribute("workspaceActions", view.workspaces().stream().map(workspace -> toWorkspaceAction(view.activeProject(), workspace)).toList());
        model.addAttribute("activeWorkspace", toWorkspace(view.activeWorkspace()));
        model.addAttribute("sessions", view.sessions().stream().map(this::toSession).toList());
        model.addAttribute("activeSession", toSession(view.activeSession()));
        model.addAttribute("shellRefresh", false);
        model.addAttribute("includeChatContainer", false);
        model.addAttribute("appVersion", appVersion);
    }

    private void populateShellUpdates(Model model, AppStateView view) {
        model.addAttribute("terminalOob", true);
        model.addAttribute("shellRefresh", true);
        model.addAttribute("includeChatContainer", true);
        model.addAttribute("reviewOob", true);
        populateChatControlsModel(model, defaultChatSelection());
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

    private void populateWorkspaceCloseModel(Model model, AppStateService.WorkspaceCloseInspection inspection) {
        model.addAttribute("workspace", new Workspace(inspection.workspaceId(), inspection.workspaceName(), inspection.workspacePath()));
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

    private ChatMessage toChatMessage(ChatMessageView view) {
        return new ChatMessage(view.role(), view.text(), view.ts(), view.pending(), view.id(), view.toolCalls().stream().map(this::toToolCallView).toList(), view.metadata());
    }

    private ToolCallView toToolCallView(com.judepereira.jupiter2.persistence.Persistence.ToolCallView view) {
        return new ToolCallView(view.toolCallId(), view.toolName(), view.success(), view.inputPreview(), view.outputPreview(), view.inputTruncated(), view.outputTruncated(),
                view.subagentSessionId(), view.subagentAgentId(), view.subagentAgentName(), view.status());
    }

    private ChangedFile toChangedFile(ChangedFileView view) {
        return view == null ? null : new ChangedFile(view.key(), view.source(), view.id(), view.path(), view.diff());
    }

    private Project toProject(ProjectView view) {
        return view == null ? null : new Project(view.id(), view.name(), view.path(), view.workspaceInitCommands());
    }

    private Workspace toWorkspace(WorkspaceView view) {
        return view == null ? null : new Workspace(view.id(), view.name(), view.path(), view.unread());
    }

    private WorkspaceAction toWorkspaceAction(ProjectView activeProject, WorkspaceView workspace) {
        boolean defaultWorkspace = activeProject != null && workspace.path().equals(activeProject.path());
        return new WorkspaceAction(workspace.id(), defaultWorkspace, !defaultWorkspace);
    }

    private Session toSession(SessionView view) {
        return view == null ? null : new Session(view.id(), view.name(), view.unread());
    }

    private ToolCallTraceInput toToolCallTraceInput(ToolCallTrace trace) {
        return new ToolCallTraceInput(trace.getToolCallId(), trace.getToolName(), trace.getArgs(), trace.isSuccess(), trace.getTextSummary(), trace.getMachineSummary());
    }

    private String directoryDisplayName(Path path) {
        Path fileName = path.getFileName();
        return fileName == null ? path.toString() : fileName.toString();
    }

    private String normalizeProviderErrorMessage(Exception e) {
        String message = e == null ? null : e.getMessage();
        if (message == null || message.isBlank()) {
            return "error";
        }

        String rawJson = extractJsonObject(message);
        if (rawJson != null) {
            try {
                ProviderErrorPayload payload = SseJson.readValue(rawJson, ProviderErrorPayload.class);
                String errorMessage = payload.error() == null ? null : payload.error().message();
                if (errorMessage != null && !errorMessage.isBlank()) {
                    log.warn("Provider error payload: {}", rawJson);
                    return errorMessage;
                }
            } catch (Exception ignored) {
            }
        }

        return message;
    }

    private String extractJsonObject(String message) {
        int start = message.indexOf('{');
        int end = message.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        return message.substring(start, end + 1);
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
    private record ProviderErrorPayload(ProviderError error) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ProviderError(String message) {}

    public record ToolCallView(String toolCallId, String toolName, boolean success, String inputPreview, String outputPreview, boolean inputTruncated, boolean outputTruncated,
                                Long subagentSessionId, String subagentAgentId, String subagentAgentName, String status) {
        public ToolCallView(String toolCallId, String toolName, boolean success, String inputPreview, String outputPreview, boolean inputTruncated, boolean outputTruncated,
                            Long subagentSessionId, String subagentAgentId, String subagentAgentName) {
            this(toolCallId, toolName, success, inputPreview, outputPreview, inputTruncated, outputTruncated, subagentSessionId, subagentAgentId, subagentAgentName, null);
        }
    }

    public record ToolCallGroupView(String toolName, String displayLabel, boolean success, int count, List<ToolCallView> calls) {}

    public record ChatMessage(String role, String text, long ts, boolean pending, String id, List<ToolCallView> toolCalls, ChatMessageMetadata metadata) {
        private static final Set<String> EXPLORATORY_TOOL_NAMES = Set.of("list_files", "read_file", "search_code");

        public List<ToolCallGroupView> toolCallGroups() {
            if (toolCalls.isEmpty()) {
                return List.of();
            }

            List<ToolCallGroupView> groups = new ArrayList<>();
            List<ToolCallView> currentCalls = new ArrayList<>();
            for (ToolCallView call : toolCalls) {
                if (currentCalls.isEmpty() || startsNewGroup(currentCalls.get(currentCalls.size() - 1), call)) {
                    if (!currentCalls.isEmpty()) {
                        groups.add(toGroup(currentCalls));
                    }
                    currentCalls = new ArrayList<>();
                }

                currentCalls.add(call);
            }

            if (!currentCalls.isEmpty()) {
                groups.add(toGroup(currentCalls));
            }

            return List.copyOf(groups);
        }

        private boolean startsNewGroup(ToolCallView previous, ToolCallView current) {
            if ("task".equals(previous.toolName()) || "task".equals(current.toolName())) {
                return true;
            }

            if (isExploratory(previous.toolName()) && isExploratory(current.toolName())) {
                return false;
            }

            return !previous.toolName().equals(current.toolName());
        }

        private boolean isExploratory(String toolName) {
            return EXPLORATORY_TOOL_NAMES.contains(toolName);
        }

        private ToolCallGroupView toGroup(List<ToolCallView> calls) {
            ToolCallView first = calls.get(0);
            return new ToolCallGroupView(first.toolName(), displayLabel(calls), calls.stream().allMatch(ToolCallView::success), calls.size(), List.copyOf(calls));
        }

        private String displayLabel(List<ToolCallView> calls) {
            StringBuilder label = new StringBuilder();
            String currentToolName = calls.get(0).toolName();
            int currentCount = 1;

            for (int i = 1; i < calls.size(); i++) {
                String nextToolName = calls.get(i).toolName();
                if (currentToolName.equals(nextToolName)) {
                    currentCount++;
                    continue;
                }

                appendDisplaySegment(label, currentToolName, currentCount);
                currentToolName = nextToolName;
                currentCount = 1;
            }

            appendDisplaySegment(label, currentToolName, currentCount);
            return label.toString();
        }

        private void appendDisplaySegment(StringBuilder label, String toolName, int count) {
            if (!label.isEmpty()) {
                label.append(", ");
            }
            label.append(toolName);
            if (count > 1) {
                label.append(" (").append(count).append(")");
            }
        }
    }

    public record ChangedFile(String key, ReviewSource source, Integer id, String path, String diff) {}

    public record Project(long id, String name, String path, String workspaceInitCommands) {}

    public record Workspace(long id, String name, String path, boolean unread) {
        public Workspace(long id, String name, String path) {
            this(id, name, path, false);
        }
    }

    public record WorkspaceAction(long id, boolean defaultWorkspace, boolean deletable) {}

    public record Session(long id, String name, boolean unread) {
        public Session(long id, String name) {
            this(id, name, false);
        }
    }

    public record DirectoryEntry(String name, String path, boolean directory) {}

    private record ChatSelection(AgentDefinition selectedAgent, ModelDefinition selectedModel, ThinkingLevel selectedThinking,
                                  AgentDefinition defaultAgent, ModelDefinition defaultModel, ThinkingLevel defaultThinking) {}

    private record PendingStream(long sessionId, String workspaceRoot, AgentTurnRequest request) {}

    private record ActiveStream(PendingStream pendingStream, CopyOnWriteArrayList<SseEmitter> emitters, AtomicBoolean started,
                                AtomicBoolean finished) {
        private ActiveStream(PendingStream pendingStream) {
            this(pendingStream, new CopyOnWriteArrayList<>(), new AtomicBoolean(false), new AtomicBoolean(false));
        }
    }
}
