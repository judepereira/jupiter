package com.judepereira.jupiter2.ui;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.judepereira.jupiter2.agent.config.AgentProperties;
import com.judepereira.jupiter2.agent.harness.AgentTurnRequest;
import com.judepereira.jupiter2.agent.harness.AgentTurnResult;
import com.judepereira.jupiter2.agent.harness.CodingAgentHarness;
import com.judepereira.jupiter2.agent.harness.ToolCallTrace;
import com.judepereira.jupiter2.agent.llm.dto.Message;
import com.judepereira.jupiter2.agent.llm.AgentStreamListener;
import com.judepereira.jupiter2.agent.tools.impl.FileUtils;
import com.judepereira.jupiter2.persistence.AppStateService;
import com.judepereira.jupiter2.persistence.Persistence.AppStateView;
import com.judepereira.jupiter2.persistence.Persistence.ChangedFileDraft;
import com.judepereira.jupiter2.persistence.Persistence.ChangedFileView;
import com.judepereira.jupiter2.persistence.Persistence.ChatMessageView;
import com.judepereira.jupiter2.persistence.Persistence.ProjectView;
import com.judepereira.jupiter2.persistence.Persistence.QueuedChatTurn;
import com.judepereira.jupiter2.persistence.Persistence.SessionDetailView;
import com.judepereira.jupiter2.persistence.Persistence.SessionView;
import com.judepereira.jupiter2.persistence.Persistence.ToolCallTraceInput;
import com.judepereira.jupiter2.persistence.Persistence.WorkspaceView;
import com.judepereira.jupiter2.terminal.TerminalManager;
import com.judepereira.jupiter2.terminal.TerminalHandle;
import com.judepereira.jupiter2.terminal.TerminalPanelState;
import com.judepereira.jupiter2.terminal.TerminalStateService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Qualifier;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

@Log4j2
@Controller
@lombok.RequiredArgsConstructor
public class UiController {

    private static final ObjectMapper SseJson = new ObjectMapper();

    private final CodingAgentHarness harness;
    private final AgentProperties agentProperties;
    private final AppStateService appStateService;
    private final TerminalManager terminalManager;
    private final TerminalStateService terminalStateService;

    @Qualifier("agentTaskExecutor")
    private final Executor agentExecutor;
    private final ConcurrentMap<String, PendingStream> pendingStreams = new ConcurrentHashMap<>();

    @GetMapping("/")
    public String index(Model model) {
        AppStateView view = appStateService.loadViewData();
        populateProjectModel(model, view);
        populateSessionModel(model, view.activeSession(), view.activeSessionDetail());
        return "index";
    }

    @PostMapping("/ui/chat/send")
    public String sendMessage(@RequestParam("message") String message, Model model, HttpServletRequest request) {
        List<ChatMessage> newChatMessages = new ArrayList<>();
        AppStateView view = appStateService.loadViewData();
        SessionView session = null;
        boolean shellRefresh = false;

        if (message != null && !message.isBlank()) {
            shellRefresh = view.activeSession() == null;
            session = appStateService.ensureChatSession(agentProperties.getWorkspaceRoot());
            String user = message.trim();
            String assistantId = UUID.randomUUID().toString();
            String userId = UUID.randomUUID().toString();
            QueuedChatTurn queued = appStateService.appendUserMessageAndPendingAssistant(session.id(), userId, assistantId, user);
            newChatMessages.add(toChatMessage(queued.userMessage()));
            newChatMessages.add(toChatMessage(queued.assistantMessage()));

            String systemPrompt = "You are a concise coding assistant. Use available tools to inspect and modify the workspace when helpful. Prefer tools for file edits and external commands; return a final assistant message when done.";
            view = appStateService.loadViewData();
            SessionDetailView sessionDetail = view.activeSessionDetail();
            String workspaceRoot = sessionDetail.workspaceRoot();
            List<Message> conversationHistory = new ArrayList<>();
            conversationHistory.add(new Message(Message.Role.SYSTEM, systemPrompt));
            conversationHistory.addAll(appStateService.buildConversationHistory(session.id()));
            pendingStreams.put(assistantId, new PendingStream(session.id(), workspaceRoot, new AgentTurnRequest(systemPrompt, conversationHistory, workspaceRoot)));
        }

        populateProjectModel(model, view);
        populateSessionModel(model, view.activeSession(), view.activeSessionDetail());
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
            ChangedFileView found = view.activeSessionDetail().changedFiles().stream().filter(f -> f.id() == id).findFirst().orElse(null);
            if (found != null) {
                appStateService.selectChangedFile(view.activeSession().id(), id);
                view = appStateService.loadViewData();
            }
        }
        populateProjectModel(model, view);
        populateSessionModel(model, view.activeSession(), view.activeSessionDetail());
        return "fragments/file-diff :: diff";
    }

    @GetMapping("/ui/chat/stream/{assistantId}")
    public SseEmitter streamChat(@PathVariable("assistantId") String assistantId) {
        PendingStream pending = pendingStreams.remove(assistantId);
        if (pending == null) {
            SseEmitter emitter = new SseEmitter(0L);
            try {
                emitter.send(SseEmitter.event().name("error").data(SseJson.writeValueAsString(Map.of("message", "no_job"))));
            } catch (Exception ignored) {
            }
            emitter.complete();
            return emitter;
        }

        SseEmitter emitter = new SseEmitter(0L);

        Runnable task = () -> {
            AtomicBoolean done = new AtomicBoolean(false);
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
                        try {
                            emitter.send(SseEmitter.event().name("delta").data(SseJson.writeValueAsString(Map.of("text", delta))));
                        } catch (JsonProcessingException e) {
                            emitter.send(SseEmitter.event().name("delta").data(delta));
                        }
                    } catch (Exception e) {
                        onError(e);
                    }
                }

                @Override
                public void onStatus(String status) {
                    try {
                        try {
                            emitter.send(SseEmitter.event().name("status").data(SseJson.writeValueAsString(Map.of("status", status))));
                        } catch (JsonProcessingException e) {
                            emitter.send(SseEmitter.event().name("status").data(status));
                        }
                    } catch (Exception ignored) {
                    }
                }

                @Override
                public void onToolCallTrace(ToolCallTrace trace) {
                    try {
                        ToolCallView v = toToolCallView(appStateService.appendToolCallTrace(pending.sessionId(), assistantId, toToolCallTraceInput(trace)));
                        try {
                            emitter.send(SseEmitter.event().name("tool_call").data(SseJson.writeValueAsString(v)));
                        } catch (JsonProcessingException e) {
                            emitter.send(SseEmitter.event().name("tool_call").data(v.toString()));
                        }
                    } catch (Exception e) {
                        onError(e);
                    }
                }

                @Override
                public void onComplete(AgentTurnResult result) {
                    try {
                        String finalText = result.getFinalText() == null ? "" : result.getFinalText();
                        List<ToolCallTraceInput> traces = result.getTraces() == null ? List.of() : result.getTraces().stream().map(UiController.this::toToolCallTraceInput).toList();
                        appStateService.completeAssistantMessage(pending.sessionId(), assistantId, finalText, traces);
                        processChangedFiles(result, pending.sessionId(), pending.workspaceRoot());
                        try {
                            emitter.send(SseEmitter.event().name("done").data(SseJson.writeValueAsString(Map.of("text", finalText))));
                        } catch (JsonProcessingException e) {
                            emitter.send(SseEmitter.event().name("done").data(finalText));
                        }
                    } catch (Exception e) {
                        onError(e);
                    } finally {
                        done.set(true);
                        emitter.complete();
                    }
                }

                @Override
                public void onError(Exception e) {
                    try {
                        String normalizedMessage = normalizeProviderErrorMessage(e);
                        appStateService.failAssistantMessage(pending.sessionId(), assistantId, "Agent execution failed: " + normalizedMessage);
                        log.error("Execution failure!", e);
                        try {
                            emitter.send(SseEmitter.event().name("error").data(SseJson.writeValueAsString(Map.of("message", normalizedMessage))));
                        } catch (JsonProcessingException ex) {
                            emitter.send(SseEmitter.event().name("error").data(normalizedMessage));
                        }
                    } catch (Exception ignored) {
                    } finally {
                        done.set(true);
                        emitter.completeWithError(e);
                    }
                }
            };

            try {
                AgentTurnResult result = harness.runTurnStreaming(pending.request(), listener);
                if (!done.get()) {
                    listener.onComplete(result);
                }
            } catch (Exception e) {
                listener.onError(e);
            }
        };

        if (agentExecutor instanceof ExecutorService service) {
            service.submit(task);
        } else {
            agentExecutor.execute(task);
        }

        return emitter;
    }

    @PostMapping("/ui/review/toggle")
    public String toggleReview(Model model) {
        AppStateView view = appStateService.loadViewData();
        if (view.activeSession() != null) {
            appStateService.toggleReviewPanel(view.activeSession().id());
            view = appStateService.loadViewData();
        }
        populateProjectModel(model, view);
        populateSessionModel(model, view.activeSession(), view.activeSessionDetail());
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
        populateSessionModel(model, view.activeSession(), view.activeSessionDetail());
        model.addAttribute("reviewOob", false);
        return "fragments/review :: panel";
    }

    @PostMapping("/ui/panel/terminal")
    public String openTerminalPanel(Model model) {
        AppStateView view = currentViewWithSessionIfNeeded(true);
        if (view.activeSession() != null) {
            TerminalPanelState state = terminalStateService.snapshot(view.activeSession().id());
            if (state.bottomPanelOpen()) {
                terminalStateService.closeTerminalPane(view.activeSession().id());
            } else {
                if (state.terminalTabs().isEmpty()) {
                    TerminalHandle terminal = terminalManager.createTerminal(view.activeSessionDetail().workspaceRoot());
                    terminalStateService.registerTerminal(view.activeSession().id(), terminal);
                }
                terminalStateService.openTerminalPane(view.activeSession().id());
            }
            view = appStateService.loadViewData();
        }
        populateProjectModel(model, view);
        populateSessionModel(model, view.activeSession(), view.activeSessionDetail());
        return "fragments/terminal :: panel";
    }

    @PostMapping("/ui/terminal/new")
    public String newTerminal(Model model) {
        AppStateView view = currentViewWithSessionIfNeeded(true);
        if (view.activeSession() != null) {
            TerminalHandle terminal = terminalManager.createTerminal(view.activeSessionDetail().workspaceRoot());
            terminalStateService.registerTerminal(view.activeSession().id(), terminal);
            terminalStateService.openTerminalPane(view.activeSession().id());
            view = appStateService.loadViewData();
        }
        populateProjectModel(model, view);
        populateSessionModel(model, view.activeSession(), view.activeSessionDetail());
        return "fragments/terminal :: panel";
    }

    @PostMapping("/ui/terminal/{id}/activate")
    public String activateTerminal(@PathVariable String id, Model model) {
        AppStateView view = currentViewWithSessionIfNeeded(true);
        if (view.activeSession() != null) {
            terminalStateService.activateTerminal(view.activeSession().id(), id);
            terminalStateService.openTerminalPane(view.activeSession().id());
            view = appStateService.loadViewData();
        }
        populateProjectModel(model, view);
        populateSessionModel(model, view.activeSession(), view.activeSessionDetail());
        return "fragments/terminal :: panel";
    }

    @PostMapping("/ui/terminal/{id}/close")
    public String closeTerminal(@PathVariable String id, Model model) {
        AppStateView view = currentViewWithSessionIfNeeded(true);
        if (view.activeSession() != null) {
            terminalManager.closeTerminal(id);
            terminalStateService.closeTerminal(view.activeSession().id(), id);
            view = appStateService.loadViewData();
            populateProjectModel(model, view);
            populateSessionModel(model, view.activeSession(), view.activeSessionDetail());
            return "fragments/terminal :: panel";
        }
        populateProjectModel(model, view);
        populateSessionModel(model, view.activeSession(), view.activeSessionDetail());
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

    @PostMapping("/ui/projects/add")
    public String addProject(@RequestParam("name") String name, @RequestParam("path") String path, Model model) {
        appStateService.addOrReopenProject(name, Path.of(path).toAbsolutePath().normalize().toString());
        AppStateView view = appStateService.loadViewData();
        populateProjectModel(model, view);
        populateSessionModel(model, view.activeSession(), view.activeSessionDetail());
        model.addAttribute("terminalOob", isTerminalPanelOpen(view));
        model.addAttribute("shellRefresh", true);
        model.addAttribute("includeChatContainer", true);
        model.addAttribute("reviewOob", true);
        return "fragments/projects :: shellUpdates";
    }

    @PostMapping("/ui/workspaces/add")
    public String addWorkspace(@RequestParam("branchName") String branchName,
                               @RequestParam(name = "branchMode", defaultValue = "create") String branchMode,
                               Model model) {
        AppStateView view = appStateService.loadViewData();
        boolean createBranch = !"checkout".equalsIgnoreCase(branchMode);
        appStateService.createWorkspace(view.activeProject().id(), branchName, createBranch);
        view = appStateService.loadViewData();
        populateProjectModel(model, view);
        populateSessionModel(model, view.activeSession(), view.activeSessionDetail());
        populateShellUpdates(model, view);
        return "fragments/projects :: shellUpdates";
    }

    @PostMapping("/ui/projects/{projectId}/activate")
    public String activateProject(@PathVariable long projectId, Model model) {
        appStateService.activateProject(projectId);
        AppStateView view = appStateService.loadViewData();
        populateProjectModel(model, view);
        populateSessionModel(model, view.activeSession(), view.activeSessionDetail());
        model.addAttribute("terminalOob", isTerminalPanelOpen(view));
        model.addAttribute("shellRefresh", true);
        model.addAttribute("includeChatContainer", true);
        model.addAttribute("reviewOob", true);
        return "fragments/projects :: shellUpdates";
    }

    @PostMapping("/ui/projects/{projectId}/close")
    public String closeProject(@PathVariable long projectId, Model model) {
        appStateService.closeProject(projectId);
        AppStateView view = appStateService.loadViewData();
        populateProjectModel(model, view);
        populateSessionModel(model, view.activeSession(), view.activeSessionDetail());
        model.addAttribute("terminalOob", isTerminalPanelOpen(view));
        model.addAttribute("shellRefresh", true);
        model.addAttribute("includeChatContainer", true);
        model.addAttribute("reviewOob", true);
        return "fragments/projects :: shellUpdates";
    }

    @PostMapping("/ui/workspaces/{workspaceId}/activate")
    public String activateWorkspace(@PathVariable long workspaceId, Model model) {
        appStateService.activateWorkspace(workspaceId);
        AppStateView view = appStateService.loadViewData();
        populateProjectModel(model, view);
        populateSessionModel(model, view.activeSession(), view.activeSessionDetail());
        model.addAttribute("terminalOob", isTerminalPanelOpen(view));
        model.addAttribute("shellRefresh", true);
        model.addAttribute("includeChatContainer", true);
        model.addAttribute("reviewOob", true);
        return "fragments/projects :: shellUpdates";
    }

    @PostMapping("/ui/workspaces/{workspaceId}/collapse")
    public String collapseWorkspace(@PathVariable long workspaceId, Model model) {
        appStateService.collapseWorkspace(workspaceId);
        AppStateView view = appStateService.loadViewData();
        populateProjectModel(model, view);
        populateSessionModel(model, view.activeSession(), view.activeSessionDetail());
        model.addAttribute("terminalOob", isTerminalPanelOpen(view));
        model.addAttribute("shellRefresh", true);
        model.addAttribute("includeChatContainer", true);
        model.addAttribute("reviewOob", true);
        return "fragments/projects :: shellUpdates";
    }

    @PostMapping("/ui/sessions/{sessionId}/activate")
    public String activateSession(@PathVariable long sessionId, Model model) {
        appStateService.activateSession(sessionId);
        AppStateView view = appStateService.loadViewData();
        populateProjectModel(model, view);
        populateSessionModel(model, view.activeSession(), view.activeSessionDetail());
        model.addAttribute("terminalOob", isTerminalPanelOpen(view));
        model.addAttribute("shellRefresh", true);
        model.addAttribute("includeChatContainer", true);
        model.addAttribute("reviewOob", true);
        return "fragments/projects :: shellUpdates";
    }

    @PostMapping("/ui/sessions/add")
    public String addSession(Model model) {
        AppStateView view = appStateService.loadViewData();
        appStateService.createSession(view.activeWorkspace().id());
        view = appStateService.loadViewData();
        populateProjectModel(model, view);
        populateSessionModel(model, view.activeSession(), view.activeSessionDetail());
        populateShellUpdates(model, view);
        return "fragments/projects :: shellUpdates";
    }

    private void populateSessionModel(Model model, SessionView session, SessionDetailView detail) {
        TerminalPanelState terminalState = session == null ? new TerminalPanelState("none", List.of(), null, false) : terminalStateService.snapshot(session.id());
        if (session == null) {
            model.addAttribute("chatMessages", List.of());
            model.addAttribute("changedFiles", List.of());
            model.addAttribute("reviewPanelOpen", false);
            model.addAttribute("selectedFile", null);
            model.addAttribute("hasPending", false);
            model.addAttribute("reviewOob", false);
            model.addAttribute("workspaceRoot", null);
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
        model.addAttribute("changedFiles", detail.changedFiles().stream().map(this::toChangedFile).toList());
        model.addAttribute("reviewPanelOpen", detail.reviewPanelOpen());
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

    private boolean isTerminalPanelOpen(AppStateView view) {
        return view.activeSession() != null && terminalStateService.snapshot(view.activeSession().id()).bottomPanelOpen();
    }

    private void populateProjectModel(Model model, AppStateView view) {
        model.addAttribute("projects", view.projects().stream().map(this::toProject).toList());
        model.addAttribute("activeProject", toProject(view.activeProject()));
        model.addAttribute("workspaces", view.workspaces().stream().map(this::toWorkspace).toList());
        model.addAttribute("activeWorkspace", toWorkspace(view.activeWorkspace()));
        model.addAttribute("sessions", view.sessions().stream().map(this::toSession).toList());
        model.addAttribute("activeSession", toSession(view.activeSession()));
        model.addAttribute("shellRefresh", false);
        model.addAttribute("includeChatContainer", false);
    }

    private void populateShellUpdates(Model model, AppStateView view) {
        model.addAttribute("terminalOob", isTerminalPanelOpen(view));
        model.addAttribute("shellRefresh", true);
        model.addAttribute("includeChatContainer", true);
        model.addAttribute("reviewOob", true);
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

    private ChatMessage toChatMessage(ChatMessageView view) {
        return new ChatMessage(view.role(), view.text(), view.ts(), view.pending(), view.id(), view.toolCalls().stream().map(this::toToolCallView).toList());
    }

    private ToolCallView toToolCallView(com.judepereira.jupiter2.persistence.Persistence.ToolCallView view) {
        return new ToolCallView(view.toolName(), view.success(), view.inputPreview(), view.outputPreview(), view.inputTruncated(), view.outputTruncated());
    }

    private ChangedFile toChangedFile(ChangedFileView view) {
        return view == null ? null : new ChangedFile(view.id(), view.path(), view.diff());
    }

    private Project toProject(ProjectView view) {
        return view == null ? null : new Project(view.id(), view.name(), view.path());
    }

    private Workspace toWorkspace(WorkspaceView view) {
        return view == null ? null : new Workspace(view.id(), view.name(), view.path());
    }

    private Session toSession(SessionView view) {
        return view == null ? null : new Session(view.id(), view.name());
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

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ProviderErrorPayload(ProviderError error) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ProviderError(String message) {}

    public record ToolCallView(String toolName, boolean success, String inputPreview, String outputPreview, boolean inputTruncated, boolean outputTruncated) {}

    public record ChatMessage(String role, String text, long ts, boolean pending, String id, List<ToolCallView> toolCalls) {}

    public record ChangedFile(int id, String path, String diff) {}

    public record Project(long id, String name, String path) {}

    public record Workspace(long id, String name, String path) {}

    public record Session(long id, String name) {}

    public record DirectoryEntry(String name, String path, boolean directory) {}

    private record PendingStream(long sessionId, String workspaceRoot, AgentTurnRequest request) {}
}
