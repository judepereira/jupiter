package com.judepereira.jupiter2.ui;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.judepereira.jupiter2.agent.config.AgentProperties;
import com.judepereira.jupiter2.agent.harness.AgentTurnRequest;
import com.judepereira.jupiter2.agent.harness.AgentTurnResult;
import com.judepereira.jupiter2.agent.harness.CodingAgentHarness;
import com.judepereira.jupiter2.agent.harness.ToolCallTrace;
import com.judepereira.jupiter2.agent.llm.AgentStreamListener;
import com.judepereira.jupiter2.agent.llm.dto.Message;
import com.judepereira.jupiter2.agent.tools.impl.FileUtils;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Log4j2
@Controller
@lombok.RequiredArgsConstructor
public class UiController {

    private static final ObjectMapper SseJson = new ObjectMapper();

    private final CodingAgentHarness harness;
    private final AgentProperties agentProperties;

    @Qualifier("agentTaskExecutor")
    private final Executor agentExecutor;

    private final AppState appState = new AppState();
    private final ConcurrentMap<String, PendingStream> pendingStreams = new ConcurrentHashMap<>();

    @GetMapping("/")
    public String index(Model model) {
        populateSessionModel(model, appState.activeSession());
        return "index";
    }

    @PostMapping("/ui/chat/send")
    public String sendMessage(@RequestParam("message") String message, Model model, HttpServletRequest request) {
        List<ChatMessage> newChatMessages = new ArrayList<>();
        SessionState session = null;
        boolean shellRefresh = false;

        if (message != null && !message.isBlank()) {
            shellRefresh = appState.activeSession() == null;
            session = appState.ensureChatSession(agentProperties.getWorkspaceRoot());
            String user = message.trim();
            ChatMessage userMsg = new ChatMessage("user", user, Instant.now().toEpochMilli(), false, UUID.randomUUID().toString(), List.of());
            session.chat.add(userMsg);
            newChatMessages.add(userMsg);

            String assistantId = UUID.randomUUID().toString();
            ChatMessage pendingAssistant = new ChatMessage("assistant", "Thinking…", Instant.now().toEpochMilli(), true, assistantId, List.of());
            session.chat.add(pendingAssistant);
            newChatMessages.add(pendingAssistant);

            String systemPrompt = "You are a concise coding assistant. Use available tools to inspect and modify the workspace when helpful. Prefer tools for file edits and external commands; return a final assistant message when done.";
            pendingStreams.put(assistantId, new PendingStream(session.session.id(), session.workspacePath, new AgentTurnRequest(systemPrompt, buildConversationHistory(session), session.workspacePath)));
        }

        populateSessionModel(model, session != null ? session : appState.activeSession());
        model.addAttribute("newChatMessages", List.copyOf(newChatMessages));
        model.addAttribute("hasPending", session != null && session.chat.stream().anyMatch(m -> m.pending()));
        model.addAttribute("shellRefresh", shellRefresh);
        model.addAttribute("includeChatContainer", false);
        model.addAttribute("reviewOob", shellRefresh || (session != null && !session.chat.stream().anyMatch(m -> m.pending()) && session.reviewPanelOpen.get()));
        return "fragments/chat-response :: response";
    }

    @GetMapping("/ui/review/file/{id}")
    public String loadFile(@PathVariable("id") int id, Model model) {
        SessionState session = appState.activeSession();
        if (session != null) {
            ChangedFile found = session.changedFiles.stream().filter(f -> f.id() == id).findFirst().orElse(null);
            if (found != null) {
                session.selectedFile = found;
            }
        }
        populateSessionModel(model, session);
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

        SessionState session = appState.session(pending.sessionId());
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
                        try {
                            emitter.send(SseEmitter.event().name("delta").data(SseJson.writeValueAsString(Map.of("text", delta))));
                        } catch (JsonProcessingException e) {
                            emitter.send(SseEmitter.event().name("delta").data(delta));
                        }
                        synchronized (session.chat) {
                            for (int i = 0; i < session.chat.size(); i++) {
                                ChatMessage m = session.chat.get(i);
                                if (m.id.equals(assistantId)) {
                                    session.chat.set(i, new ChatMessage("assistant", accumulated.toString(), m.ts, true, assistantId, m.toolCalls));
                                    break;
                                }
                            }
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
                        ToolCallView v = traceToView(trace);
                        synchronized (session.chat) {
                            for (int i = 0; i < session.chat.size(); i++) {
                                ChatMessage m = session.chat.get(i);
                                if (m.id.equals(assistantId)) {
                                    List<ToolCallView> updated = new ArrayList<>(m.toolCalls == null ? List.of() : m.toolCalls);
                                    updated.add(v);
                                    session.chat.set(i, new ChatMessage("assistant", m.text, m.ts, m.pending, assistantId, List.copyOf(updated)));
                                    break;
                                }
                            }
                        }

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
                        synchronized (session.chat) {
                            for (int i = 0; i < session.chat.size(); i++) {
                                ChatMessage m = session.chat.get(i);
                                if (m.id.equals(assistantId)) {
                                    List<ToolCallView> views = result.getTraces() == null ? List.of() : result.getTraces().stream().map(UiController::traceToView).toList();
                                    session.chat.set(i, new ChatMessage("assistant", result.getFinalText(), Instant.now().toEpochMilli(), false, assistantId, views));
                                    break;
                                }
                            }
                        }

                        try {
                            String finalText = result.getFinalText() == null ? "" : result.getFinalText();
                            emitter.send(SseEmitter.event().name("done").data(SseJson.writeValueAsString(Map.of("text", finalText))));
                        } catch (JsonProcessingException e) {
                            emitter.send(SseEmitter.event().name("done").data(result.getFinalText() == null ? "" : result.getFinalText()));
                        }

                        processChangedFiles(result, session, pending.workspaceRoot());
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
                        synchronized (session.chat) {
                            for (int i = 0; i < session.chat.size(); i++) {
                                ChatMessage m = session.chat.get(i);
                                if (m.id.equals(assistantId)) {
                                    session.chat.set(i, new ChatMessage("assistant", "Agent execution failed: " + normalizedMessage, Instant.now().toEpochMilli(), false, assistantId, m.toolCalls));
                                    log.error("Execution failure!", e);
                                    break;
                                }
                            }
                        }
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
        SessionState session = appState.activeSession();
        if (session != null) {
            boolean prev;
            do {
                prev = session.reviewPanelOpen.get();
            } while (!session.reviewPanelOpen.compareAndSet(prev, !prev));
        }
        populateSessionModel(model, session);
        model.addAttribute("reviewOob", false);
        return "fragments/review :: panel";
    }

    @GetMapping("/ui/panel/{name}")
    public String panelPlaceholder(@PathVariable String name, Model model) {
        model.addAttribute("panelName", name);
        return "fragments/panel :: panel";
    }

    @GetMapping("/ui/projects/new")
    public String newProjectModal(Model model) {
        Path home = Path.of(System.getProperty("user.home"));
        model.addAttribute("currentPath", home.toString());
        model.addAttribute("selectedPath", home.toString());
        model.addAttribute("startPath", home.toString());
        model.addAttribute("directoryEntries", listDirectoryEntries(home));
        populateProjectModel(model);
        return "fragments/projects :: modal";
    }

    @GetMapping("/ui/projects/directory")
    public String listDirectory(@RequestParam("path") String path, Model model) {
        Path current = Path.of(path);
        model.addAttribute("directoryEntries", listDirectoryEntries(current));
        model.addAttribute("name", current.getFileName() == null ? current.toString() : current.getFileName().toString());
        model.addAttribute("path", current.toString());
        model.addAttribute("expanded", true);
        return "fragments/directory-list :: node";
    }

    @GetMapping("/ui/projects/directory/select")
    public String selectDirectory(@RequestParam("path") String path, Model model) {
        model.addAttribute("selectedPath", Path.of(path).toAbsolutePath().normalize().toString());
        return "fragments/projects :: selectedPathField";
    }

    @GetMapping("/ui/projects/modal/close")
    public String closeProjectModal() {
        return "fragments/projects :: modalClose";
    }

    @PostMapping("/ui/projects/add")
    public String addProject(@RequestParam("name") String name, @RequestParam("path") String path, Model model) {
        appState.addProject(name, Path.of(path).toAbsolutePath().normalize().toString());
        populateProjectModel(model);
        populateSessionModel(model, appState.activeSession());
        model.addAttribute("shellRefresh", true);
        model.addAttribute("includeChatContainer", true);
        model.addAttribute("reviewOob", true);
        return "fragments/projects :: shellUpdates";
    }

    @PostMapping("/ui/projects/{projectId}/activate")
    public String activateProject(@PathVariable long projectId, Model model) {
        appState.activateProject(projectId);
        populateProjectModel(model);
        populateSessionModel(model, appState.activeSession());
        model.addAttribute("shellRefresh", true);
        model.addAttribute("includeChatContainer", true);
        model.addAttribute("reviewOob", true);
        return "fragments/projects :: shellUpdates";
    }

    @PostMapping("/ui/workspaces/{workspaceId}/activate")
    public String activateWorkspace(@PathVariable long workspaceId, Model model) {
        appState.activateWorkspace(workspaceId);
        populateProjectModel(model);
        populateSessionModel(model, appState.activeSession());
        model.addAttribute("shellRefresh", true);
        model.addAttribute("includeChatContainer", true);
        model.addAttribute("reviewOob", true);
        return "fragments/projects :: shellUpdates";
    }

    @PostMapping("/ui/sessions/{sessionId}/activate")
    public String activateSession(@PathVariable long sessionId, Model model) {
        appState.activateSession(sessionId);
        populateProjectModel(model);
        populateSessionModel(model, appState.activeSession());
        model.addAttribute("shellRefresh", true);
        model.addAttribute("includeChatContainer", true);
        model.addAttribute("reviewOob", true);
        return "fragments/projects :: shellUpdates";
    }

    private void populateSessionModel(Model model, SessionState session) {
        populateProjectModel(model);
        if (session == null) {
            model.addAttribute("chatMessages", List.of());
            model.addAttribute("changedFiles", List.of());
            model.addAttribute("reviewPanelOpen", false);
            model.addAttribute("selectedFile", null);
            model.addAttribute("hasPending", false);
            model.addAttribute("reviewOob", false);
            model.addAttribute("workspaceRoot", null);
            return;
        }

        model.addAttribute("chatMessages", List.copyOf(session.chat));
        model.addAttribute("changedFiles", List.copyOf(session.changedFiles));
        model.addAttribute("reviewPanelOpen", session.reviewPanelOpen.get());
        model.addAttribute("selectedFile", session.selectedFile);
        model.addAttribute("hasPending", session.chat.stream().anyMatch(m -> m.pending()));
        model.addAttribute("reviewOob", !session.chat.stream().anyMatch(m -> m.pending()) && session.reviewPanelOpen.get());
        model.addAttribute("workspaceRoot", session.workspacePath);
    }

    private void populateProjectModel(Model model) {
        model.addAttribute("projects", appState.projectsView());
        model.addAttribute("activeProject", appState.activeProjectView());
        model.addAttribute("workspaces", appState.activeProjectWorkspaces());
        model.addAttribute("activeWorkspace", appState.activeWorkspaceView());
        model.addAttribute("sessions", appState.activeWorkspaceSessions());
        model.addAttribute("activeSession", appState.activeSessionView());
        model.addAttribute("shellRefresh", false);
        model.addAttribute("includeChatContainer", false);
    }

    private List<DirectoryEntry> listDirectoryEntries(Path path) {
        try (var stream = Files.list(path)) {
            return stream
                    .filter(Files::isDirectory)
                    .map(entry -> new DirectoryEntry(entry.getFileName().toString(), entry.toString(), Files.isDirectory(entry)))
                    .sorted(Comparator.comparing(DirectoryEntry::directory).reversed().thenComparing(DirectoryEntry::name))
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

    private void processChangedFiles(AgentTurnResult result, SessionState session, String workspaceRoot) {
        var mutatingTools = Set.of("write_file", "apply_patch");
        List<ToolCallTrace> traces = result.getTraces();
        Set<String> seen = new HashSet<>();
        String latestPath = null;
        List<String> addedPathsInOrder = new ArrayList<>();

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
                    addedPathsInOrder.add(relStr);
                    latestPath = relStr;
                }
            } catch (Exception ignored) {
            }
        }

        for (String p : addedPathsInOrder) {
            String diff = safeComputeDiff(workspaceRoot, p);
            int id = session.nextFileId.getAndIncrement();
            session.changedFiles.add(0, new ChangedFile(id, p, diff));
        }

        if (!addedPathsInOrder.isEmpty()) {
            session.reviewPanelOpen.set(true);
            String selectedPath = latestPath;
            if (selectedPath != null) {
                ChangedFile found = session.changedFiles.stream().filter(f -> f.path().equals(selectedPath)).findFirst().orElse(null);
                if (found != null) {
                    session.selectedFile = found;
                }
            }
        }
    }

    private List<Message> buildConversationHistory(SessionState session) {
        return session.chat.stream()
                .filter(m -> !m.pending && !"system".equals(m.role))
                .map(this::toMessage)
                .toList();
    }

    private Message toMessage(ChatMessage chatMessage) {
        Message.Role role = "assistant".equals(chatMessage.role) ? Message.Role.ASSISTANT : Message.Role.USER;
        return new Message(role, chatMessage.text);
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

    private static ToolCallView traceToView(ToolCallTrace t) {
        String input;
        try {
            input = SseJson.writerWithDefaultPrettyPrinter().writeValueAsString(t.getArgs());
        } catch (Exception e) {
            input = String.valueOf(t.getArgs());
        }
        String output = t.getTextSummary();
        boolean[] inTr = new boolean[1];
        boolean[] outTr = new boolean[1];
        String inPrev = previewAndTruncate(input, TOOL_PREVIEW_MAX, inTr);
        String outPrev = previewAndTruncate(output, TOOL_PREVIEW_MAX, outTr);
        return new ToolCallView(t.getToolName(), t.isSuccess(), inPrev, outPrev, inTr[0], outTr[0]);
    }

    private static final int TOOL_PREVIEW_MAX = 2000;

    private static String previewAndTruncate(String s, int max, boolean[] truncatedFlag) {
        if (s == null) {
            truncatedFlag[0] = false;
            return "";
        }
        if (s.length() <= max) {
            truncatedFlag[0] = false;
            return s;
        }
        truncatedFlag[0] = true;
        return s.substring(0, max);
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

    private static final class AppState {
        private final AtomicLong projectIds = new AtomicLong(1);
        private final AtomicLong workspaceIds = new AtomicLong(1);
        private final AtomicLong sessionIds = new AtomicLong(1);
        private final ConcurrentMap<Long, ProjectState> projects = new ConcurrentHashMap<>();
        private final ConcurrentMap<Long, WorkspaceState> workspaces = new ConcurrentHashMap<>();
        private final ConcurrentMap<Long, SessionState> sessions = new ConcurrentHashMap<>();
        private volatile Long activeProjectId;
        private volatile Long activeWorkspaceId;
        private volatile Long activeSessionId;

        synchronized SessionState ensureChatSession(String workspaceRoot) {
            SessionState session = activeSession();
            if (session != null) {
                return session;
            }
            addProject("Project #1", workspaceRoot);
            return activeSession();
        }

        synchronized ProjectState addProject(String name, String path) {
            long projectId = projectIds.getAndIncrement();
            Project project = new Project(projectId, name, path);
            ProjectState projectState = new ProjectState(project);
            projects.put(projectId, projectState);

            long workspaceId = workspaceIds.getAndIncrement();
            Workspace workspace = new Workspace(workspaceId, "Workspace #1", path);
            WorkspaceState workspaceState = new WorkspaceState(workspace, projectId);
            workspaces.put(workspaceId, workspaceState);
            projectState.workspaceIds.add(workspaceId);
            projectState.activeWorkspaceId = workspaceId;

            long sessionId = sessionIds.getAndIncrement();
            Session session = new Session(sessionId, "Session #1");
            SessionState sessionState = new SessionState(session, workspaceId, path);
            sessions.put(sessionId, sessionState);
            workspaceState.sessionIds.add(sessionId);
            workspaceState.activeSessionId = sessionId;

            activeProjectId = projectId;
            activeWorkspaceId = workspaceId;
            activeSessionId = sessionId;
            return projectState;
        }

        synchronized void activateProject(long projectId) {
            ProjectState project = projects.get(projectId);
            activeProjectId = projectId;
            activateWorkspace(project.activeWorkspaceId);
        }

        synchronized void activateWorkspace(long workspaceId) {
            WorkspaceState workspace = workspaces.get(workspaceId);
            activeWorkspaceId = workspaceId;
            activeProjectId = workspace.projectId;
            if (workspace.activeSessionId == null) {
                workspace.activeSessionId = workspace.sessionIds.getFirst();
            }
            activateSession(workspace.activeSessionId);
        }

        synchronized void activateSession(long sessionId) {
            SessionState session = sessions.get(sessionId);
            WorkspaceState workspace = workspaces.get(session.workspaceId);
            activeSessionId = sessionId;
            activeWorkspaceId = session.workspaceId;
            activeProjectId = workspace.projectId;
            workspace.activeSessionId = sessionId;
        }

        SessionState activeSession() {
            return activeSessionId == null ? null : sessions.get(activeSessionId);
        }

        Project activeProjectView() {
            ProjectState project = activeProjectId == null ? null : projects.get(activeProjectId);
            return project == null ? null : project.project;
        }

        Workspace activeWorkspaceView() {
            WorkspaceState workspace = activeWorkspaceId == null ? null : workspaces.get(activeWorkspaceId);
            return workspace == null ? null : workspace.workspace;
        }

        Session activeSessionView() {
            SessionState session = activeSession();
            return session == null ? null : session.session;
        }

        List<Project> projectsView() {
            return projects.values().stream().map(state -> state.project).sorted(Comparator.comparingLong(Project::id)).toList();
        }

        List<Workspace> activeProjectWorkspaces() {
            ProjectState project = activeProjectId == null ? null : projects.get(activeProjectId);
            if (project == null) {
                return List.of();
            }
            return project.workspaceIds.stream().map(workspaces::get).map(state -> state.workspace).sorted(Comparator.comparingLong(Workspace::id)).toList();
        }

        List<Session> activeWorkspaceSessions() {
            WorkspaceState workspace = activeWorkspaceId == null ? null : workspaces.get(activeWorkspaceId);
            if (workspace == null) {
                return List.of();
            }
            return workspace.sessionIds.stream().map(sessions::get).map(state -> state.session).sorted(Comparator.comparingLong(Session::id)).toList();
        }

        SessionState session(long id) {
            return sessions.get(id);
        }
    }

    private static final class ProjectState {
        private final Project project;
        private final List<Long> workspaceIds = new CopyOnWriteArrayList<>();
        private volatile Long activeWorkspaceId;

        private ProjectState(Project project) {
            this.project = project;
        }
    }

    private static final class WorkspaceState {
        private final Workspace workspace;
        private final long projectId;
        private final List<Long> sessionIds = new CopyOnWriteArrayList<>();
        private volatile Long activeSessionId;

        private WorkspaceState(Workspace workspace, long projectId) {
            this.workspace = workspace;
            this.projectId = projectId;
        }
    }

    private static final class SessionState {
        private final Session session;
        private final long workspaceId;
        private final String workspacePath;
        private final List<ChatMessage> chat = new CopyOnWriteArrayList<>(List.of(new ChatMessage("system",
                "Welcome to Jupiter. Let's get started - what's on your mind?", Instant.now().toEpochMilli(), false, UUID.randomUUID().toString(), List.of())));
        private final List<ChangedFile> changedFiles = new CopyOnWriteArrayList<>();
        private final AtomicInteger nextFileId = new AtomicInteger(1);
        private final AtomicBoolean reviewPanelOpen = new AtomicBoolean(false);
        private volatile ChangedFile selectedFile;

        private SessionState(Session session, long workspaceId, String workspacePath) {
            this.session = session;
            this.workspaceId = workspaceId;
            this.workspacePath = workspacePath;
        }
    }
}
