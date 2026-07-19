package com.judepereira.jupiter2.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.judepereira.jupiter2.agent.llm.dto.Message;
import com.judepereira.jupiter2.agent.llm.dto.ToolCall;
import com.judepereira.jupiter2.persistence.Persistence.AppStateView;
import com.judepereira.jupiter2.persistence.Persistence.ChatMessageView;
import com.judepereira.jupiter2.persistence.Persistence.ChangedFileDraft;
import com.judepereira.jupiter2.persistence.Persistence.ChangedFileView;
import com.judepereira.jupiter2.persistence.Persistence.ProjectView;
import com.judepereira.jupiter2.persistence.Persistence.QueuedChatTurn;
import com.judepereira.jupiter2.persistence.Persistence.SessionDetailView;
import com.judepereira.jupiter2.persistence.Persistence.SessionView;
import com.judepereira.jupiter2.persistence.Persistence.ToolCallTraceInput;
import com.judepereira.jupiter2.persistence.Persistence.ToolCallView;
import com.judepereira.jupiter2.persistence.Persistence.WorkspaceView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AppStateService {

    private static final TypeReference<List<ToolCallPayload>> TOOL_CALLS_TYPE = new TypeReference<>() {};

    private final AppStateRepository repository;
    private final ObjectMapper objectMapper;

    @Transactional
    public SessionView ensureChatSession(String defaultWorkspaceRoot) {
        var appState = repository.loadAppState();
        if (appState.activeSessionId() != null) {
            return toSessionView(repository.findSession(appState.activeSessionId()));
        }
        addOrReopenProject("Project #1", defaultWorkspaceRoot);
        return loadViewData().activeSession();
    }

    @Transactional
    public ProjectView addOrReopenProject(String name, String normalizedPath) {
        Instant now = Instant.now();
        var existing = repository.findProjectByNormalizedPath(normalizedPath);
        if (existing.isPresent()) {
            var project = existing.get();
            long displayOrder = repository.nextProjectDisplayOrder();
            repository.reopenProject(project.id(), name, displayOrder, now);
            activateProjectInternal(project.id(), now);
            return toProjectView(repository.findProject(project.id()));
        }

        long projectId = repository.insertProject(name, normalizedPath, repository.nextProjectDisplayOrder(), now);
        long workspaceId = repository.insertWorkspace(projectId, "Workspace #1", normalizedPath, 1L, now);
        long sessionId = createSessionInternal(workspaceId, now);
        repository.updateAppState(projectId, workspaceId, sessionId);
        return toProjectView(repository.findProject(projectId));
    }

    @Transactional
    public WorkspaceView createWorkspace(long projectId, String branchName, boolean createBranch) {
        Instant now = Instant.now();
        var project = repository.findProject(projectId);
        if (project.closedAt() != null) {
            throw new IllegalStateException("Project is closed: " + projectId);
        }

        Path projectRoot = Path.of(project.normalizedPath());
        Path worktreePath = projectRoot.resolveSibling(".trees").resolve(branchName).toAbsolutePath().normalize();
        runGitWorktreeAdd(projectRoot, worktreePath, branchName, createBranch);

        long position = repository.nextWorkspacePosition(projectId);
        long workspaceId = repository.insertWorkspace(projectId, branchName, worktreePath.toString(), position, now);
        createSessionInternal(workspaceId, now);
        return toWorkspaceView(repository.findWorkspace(workspaceId));
    }

    @Transactional
    public void closeProject(long projectId) {
        Instant now = Instant.now();
        var project = repository.findProject(projectId);
        repository.closeProject(projectId, now);
        var appState = repository.loadAppState();
        if (appState.activeProjectId() == null || appState.activeProjectId() != projectId) {
            return;
        }

        var next = repository.findNextVisibleProjectAfter(project.displayOrder());
        if (next != null) {
            activateProjectInternal(next.id(), now);
            return;
        }

        var previous = repository.findPreviousVisibleProjectBefore(project.displayOrder());
        if (previous != null) {
            activateProjectInternal(previous.id(), now);
            return;
        }

        repository.updateAppState(null, null, null);
    }

    @Transactional
    public void activateProject(long projectId) {
        activateProjectInternal(projectId, Instant.now());
    }

    @Transactional
    public void activateWorkspace(long workspaceId) {
        Instant now = Instant.now();
        var workspace = repository.findWorkspace(workspaceId);
        var session = repository.findSessionToActivate(workspaceId);
        if (session == null) {
            throw new IllegalStateException("Missing session for workspace " + workspaceId);
        }
        repository.updateProjectLastOpened(workspace.projectId(), now);
        repository.updateSessionLastOpened(session.id(), now);
        repository.updateWorkspaceLastOpened(workspaceId, now);
        repository.updateAppState(workspace.projectId(), workspaceId, session.id());
    }

    @Transactional
    public void collapseWorkspace(long workspaceId) {
        Instant now = Instant.now();
        var workspace = repository.findWorkspace(workspaceId);
        repository.updateProjectLastOpened(workspace.projectId(), now);
        repository.updateAppState(workspace.projectId(), null, null);
    }

    @Transactional
    public void activateSession(long sessionId) {
        Instant now = Instant.now();
        var session = repository.findSession(sessionId);
        var workspace = repository.findWorkspace(session.workspaceId());
        repository.updateProjectLastOpened(workspace.projectId(), now);
        repository.updateSessionLastOpened(sessionId, now);
        repository.updateWorkspaceLastOpened(workspace.id(), now);
        repository.updateAppState(workspace.projectId(), workspace.id(), sessionId);
    }

    @Transactional
    public SessionView createSession(long workspaceId) {
        long sessionId = createSessionInternal(workspaceId, Instant.now());
        return toSessionView(repository.findSession(sessionId));
    }

    @Transactional
    public SessionView createSession(long workspaceId, String name) {
        long position = repository.nextSessionPosition(workspaceId);
        long sessionId = createSessionInternal(workspaceId, Instant.now(), position, name);
        return toSessionView(repository.findSession(sessionId));
    }

    public AppStateView loadViewData() {
        var appState = repository.loadAppState();
        List<ProjectView> projects = repository.listVisibleProjects().stream().map(this::toProjectView).toList();
        ProjectView activeProject = appState.activeProjectId() == null ? null : toProjectView(repository.findProject(appState.activeProjectId()));
        List<WorkspaceView> workspaces = activeProject == null ? List.of() : repository.listWorkspacesByProject(activeProject.id()).stream().map(this::toWorkspaceView).toList();
        WorkspaceView activeWorkspace = appState.activeWorkspaceId() == null ? null : toWorkspaceView(repository.findWorkspace(appState.activeWorkspaceId()));
        List<SessionView> sessions = activeWorkspace == null ? List.of() : repository.listSessionsByWorkspace(activeWorkspace.id()).stream().map(this::toSessionView).toList();
        SessionView activeSession = appState.activeSessionId() == null ? null : toSessionView(repository.findSession(appState.activeSessionId()));
        SessionDetailView sessionDetail = activeSession == null ? null : loadSessionDetail(activeSession.id());
        return new AppStateView(projects, activeProject, workspaces, activeWorkspace, sessions, activeSession, sessionDetail);
    }

    @Transactional
    public QueuedChatTurn appendUserMessageAndPendingAssistant(long sessionId, String userPublicId, String assistantPublicId, String userText) {
        if (userText == null) {
            throw new IllegalStateException("User text is required");
        }
        Instant now = Instant.now();
        long turnId = repository.nextTurnId(sessionId);
        long userSequence = repository.nextMessageSequence(sessionId);
        String userId = publicId(userPublicId);
        String assistantId = publicId(assistantPublicId);
        repository.insertConversationMessage(sessionId, userId, "user", turnId, userSequence, userText, null, null, true, true, false, now);
        long assistantSequence = repository.nextMessageSequence(sessionId);
        repository.insertConversationMessage(sessionId, assistantId, "assistant", turnId, assistantSequence, "Thinking…", null, null, true, false, true, now);
        return new QueuedChatTurn(new ChatMessageView("user", userText, now.toEpochMilli(), false, userId, List.of()),
                new ChatMessageView("assistant", "Thinking…", now.toEpochMilli(), true, assistantId, List.of()));
    }

    public QueuedChatTurn appendUserMessageAndPendingAssistant(long sessionId, String userText) {
        return appendUserMessageAndPendingAssistant(sessionId, null, null, userText);
    }

    @Transactional
    public void updateStreamingAssistantText(long sessionId, String assistantPublicId, String text) {
        var message = repository.findMessageBySessionAndPublicId(sessionId, assistantPublicId);
        if (!message.pending() || !"assistant".equals(message.role())) {
            throw new IllegalStateException("Assistant message is not pending: " + assistantPublicId);
        }
        repository.updateMessageContentAndPending(message.id(), text, true, false);
    }

    @Transactional
    public ToolCallView appendToolCallTrace(long sessionId, String assistantPublicId, ToolCallTraceInput trace) {
        Instant now = Instant.now();
        var assistantMessage = repository.findMessageBySessionAndPublicId(sessionId, assistantPublicId);
        if (!assistantMessage.pending() || !"assistant".equals(assistantMessage.role())) {
            throw new IllegalStateException("Assistant message is not pending: " + assistantPublicId);
        }

        long sequence = repository.nextToolCallTraceSequence(sessionId);
        String toolCallId = trace.toolCallId() == null || trace.toolCallId().isBlank() ? String.valueOf(sequence) : trace.toolCallId();
        List<ToolCallPayload> payloads = toolCallPayloads(assistantMessage.toolCallsJson());
        if (payloads.stream().anyMatch(payload -> toolCallId.equals(payload.toolCallId()))) {
            return traceToView(trace);
        }

        String argsJson = json(trace.args());
        String machineSummaryJson = json(trace.machineSummary());
        repository.insertToolCallTrace(sessionId, assistantMessage.id(), sequence, trace.toolName(), trace.success(), argsJson, trace.textSummary(), machineSummaryJson, now);
        payloads.add(new ToolCallPayload(toolCallId, trace.toolName(), trace.args()));
        repository.updateMessageToolCalls(assistantMessage.id(), json(payloads));

        long assistantToolCallSequence = repository.nextMessageSequence(sessionId);
        repository.insertConversationMessage(sessionId, UUID.randomUUID().toString(), "assistant", assistantMessage.turnId(), assistantToolCallSequence,
                "", null, json(List.of(new ToolCallPayload(toolCallId, trace.toolName(), trace.args()))), false, true, false, now);

        long toolResultSequence = repository.nextMessageSequence(sessionId);
        repository.insertConversationMessage(sessionId, UUID.randomUUID().toString(), "tool", assistantMessage.turnId(), toolResultSequence,
                trace.textSummary() == null ? "" : trace.textSummary(), toolCallId, null, false, true, false, now);

        return traceToView(trace);
    }

    @Transactional
    public ChatMessageView completeAssistantMessage(long sessionId, String assistantPublicId, String finalText, List<ToolCallTraceInput> traces) {
        if (finalText == null) {
            throw new IllegalStateException("Final assistant text is required");
        }
        var assistantMessage = repository.findMessageBySessionAndPublicId(sessionId, assistantPublicId);
        if (!assistantMessage.pending() || !"assistant".equals(assistantMessage.role())) {
            throw new IllegalStateException("Assistant message is not pending: " + assistantPublicId);
        }
        repository.updateMessageToolCalls(assistantMessage.id(), null);
        repository.updateMessageContentAndPending(assistantMessage.id(), finalText, false, true);
        return toChatMessageView(repository.findMessageBySessionAndPublicId(sessionId, assistantPublicId), sessionId);
    }

    @Transactional
    public ChatMessageView failAssistantMessage(long sessionId, String assistantPublicId, String errorText) {
        if (errorText == null) {
            throw new IllegalStateException("Assistant error text is required");
        }
        var assistantMessage = repository.findMessageBySessionAndPublicId(sessionId, assistantPublicId);
        if (!assistantMessage.pending() || !"assistant".equals(assistantMessage.role())) {
            throw new IllegalStateException("Assistant message is not pending: " + assistantPublicId);
        }
        repository.updateMessageToolCalls(assistantMessage.id(), null);
        repository.updateMessageContentAndPending(assistantMessage.id(), errorText, false, false);
        return toChatMessageView(repository.findMessageBySessionAndPublicId(sessionId, assistantPublicId), sessionId);
    }

    @Transactional
    public boolean toggleReviewPanel(long sessionId) {
        var session = repository.findSession(sessionId);
        boolean open = !session.reviewPanelOpen();
        repository.updateSessionReviewState(sessionId, open, session.selectedChangedFileId());
        return open;
    }

    @Transactional
    public void selectChangedFile(long sessionId, int changedFileId) {
        var file = repository.findChangedFile(changedFileId);
        if (file.sessionId() != sessionId) {
            throw new IllegalStateException("Changed file does not belong to session " + sessionId);
        }
        repository.updateSessionSelectedChangedFile(sessionId, file.id());
    }

    @Transactional
    public List<ChangedFileView> addChangedFilesToSession(long sessionId, List<ChangedFileDraft> changedFiles) {
        Instant now = Instant.now();
        if (changedFiles == null || changedFiles.isEmpty()) {
            throw new IllegalStateException("No changed files provided");
        }
        long latestFileId = -1;
        for (ChangedFileDraft draft : changedFiles) {
            long position = repository.nextChangedFilePosition(sessionId);
            latestFileId = repository.insertChangedFile(sessionId, draft.path(), draft.diff(), position, now);
        }
        repository.updateSessionReviewState(sessionId, true, latestFileId);
        return repository.listChangedFilesBySession(sessionId).stream().map(this::toChangedFileView).toList();
    }

    @Transactional
    public ChatMessageView appendToolResultMessage(long sessionId, String publicId, String toolCallId, String content, boolean showInChat) {
        Instant now = Instant.now();
        long turnId = repository.nextTurnId(sessionId);
        long sequence = repository.nextMessageSequence(sessionId);
        String id = publicId(publicId);
        repository.insertConversationMessage(sessionId, id, "tool", turnId, sequence, content, toolCallId, null, showInChat, true, false, now);
        return toChatMessageView(repository.findMessageBySessionAndPublicId(sessionId, id), sessionId);
    }

    public List<Message> buildConversationHistory(long sessionId) {
        var messages = repository.listMessagesBySession(sessionId).stream()
                .filter(message -> message.includeInModel() && !message.pending())
                .toList();

        Map<String, Long> toolCallRoots = new java.util.HashMap<>();
        for (var message : messages) {
            if ("assistant".equals(message.role()) && !message.showInChat()) {
                for (var payload : toolCallPayloads(message.toolCallsJson())) {
                    toolCallRoots.put(payload.toolCallId(), message.sequence());
                }
            }
        }

        return messages.stream()
                .sorted(Comparator.comparingLong(AppStateRepository.ConversationMessageRow::turnId)
                        .thenComparingLong(message -> conversationHistoryGroupKey(message, toolCallRoots))
                        .thenComparingInt(this::conversationHistoryOrder)
                        .thenComparingLong(AppStateRepository.ConversationMessageRow::sequence))
                .map(this::toModelMessage)
                .toList();
    }

    private long conversationHistoryGroupKey(AppStateRepository.ConversationMessageRow message, Map<String, Long> toolCallRoots) {
        if ("assistant".equals(message.role()) && message.showInChat()) {
            return Long.MAX_VALUE;
        }
        if ("tool".equals(message.role()) && message.toolCallId() != null) {
            return toolCallRoots.getOrDefault(message.toolCallId(), message.sequence());
        }
        return message.sequence();
    }

    private int conversationHistoryOrder(AppStateRepository.ConversationMessageRow message) {
        return switch (message.role()) {
            case "system", "user" -> 0;
            case "assistant" -> message.showInChat() ? 3 : 1;
            case "tool" -> 2;
            default -> throw new IllegalStateException("Unsupported role: " + message.role());
        };
    }

    private void activateProjectInternal(long projectId, Instant now) {
        var project = repository.findProject(projectId);
        if (project.closedAt() != null) {
            throw new IllegalStateException("Project is closed: " + projectId);
        }
        var workspace = repository.findWorkspaceToActivate(projectId);
        if (workspace == null) {
            throw new IllegalStateException("Missing workspace for project " + projectId);
        }
        var session = repository.findSessionToActivate(workspace.id());
        if (session == null) {
            throw new IllegalStateException("Missing session for workspace " + workspace.id());
        }
        repository.updateProjectLastOpened(projectId, now);
        repository.updateWorkspaceLastOpened(workspace.id(), now);
        repository.updateSessionLastOpened(session.id(), now);
        repository.updateAppState(projectId, workspace.id(), session.id());
    }

    private long createSessionInternal(long workspaceId, Instant now) {
        long position = repository.nextSessionPosition(workspaceId);
        return createSessionInternal(workspaceId, now, position, "Session #" + position);
    }

    private long createSessionInternal(long workspaceId, Instant now, long position, String name) {
        String sessionName = name == null ? null : name.trim();
        if (sessionName == null || sessionName.isBlank()) {
            throw new IllegalStateException("Session name is required");
        }
        long sessionId = repository.insertSession(workspaceId, sessionName, position, now, false, null);
        repository.insertConversationMessage(sessionId, UUID.randomUUID().toString(), "system", 0L, 0L,
                "Welcome to Jupiter. Let's get started - what's on your mind?", null, null, true, false, false, now);
        var workspace = repository.findWorkspace(workspaceId);
        repository.updateProjectLastOpened(workspace.projectId(), now);
        repository.updateWorkspaceLastOpened(workspaceId, now);
        repository.updateSessionLastOpened(sessionId, now);
        repository.updateAppState(workspace.projectId(), workspaceId, sessionId);
        return sessionId;
    }

    private void runGitWorktreeAdd(Path projectRoot, Path worktreePath, String branchName, boolean createBranch) {
        String stdout = "";
        String stderr = "";
        try {
            Files.createDirectories(worktreePath.getParent());
            List<String> command = createBranch
                    ? List.of("git", "worktree", "add", "-b", branchName, worktreePath.toString())
                    : List.of("git", "worktree", "add", worktreePath.toString(), branchName);
            Process process = new ProcessBuilder(command)
                    .directory(projectRoot.toFile())
                    .start();
            stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new GitWorktreeException("git worktree add failed with exit code " + exitCode, stdout, stderr);
            }
        } catch (Exception e) {
            if (e instanceof GitWorktreeException gitWorktreeException) {
                throw gitWorktreeException;
            }
            throw new GitWorktreeException("git worktree add failed", stdout, stderr, e);
        }
    }

    private SessionDetailView loadSessionDetail(long sessionId) {
        var session = repository.findSession(sessionId);
        var workspace = repository.findWorkspace(session.workspaceId());
        var messages = repository.listVisibleMessagesBySession(sessionId).stream().map(message -> toChatMessageView(message, sessionId)).toList();
        var files = repository.listChangedFilesBySession(sessionId).stream().map(this::toChangedFileView).toList();
        ChangedFileView selected = session.selectedChangedFileId() == null ? null : toChangedFileView(repository.findChangedFile(session.selectedChangedFileId()));
        return new SessionDetailView(messages, files, session.reviewPanelOpen(), selected, workspace.normalizedPath());
    }

    private ChatMessageView toChatMessageView(AppStateRepository.ConversationMessageRow message, long sessionId) {
        List<ToolCallView> toolCalls = repository.listToolCallTracesByAssistantMessage(message.id()).stream().map(this::toToolCallView).toList();
        return new ChatMessageView(message.role(), message.content(), message.createdAt().toEpochMilli(), message.pending(), message.publicId(), toolCalls);
    }

    private ToolCallView toToolCallView(AppStateRepository.ToolCallTraceRow trace) {
        return traceToView(new ToolCallTraceInput(null, trace.toolName(), readMap(trace.argsJson()), trace.success(), trace.textSummary(), readMap(trace.machineSummaryJson())));
    }

    private ToolCallView traceToView(ToolCallTraceInput trace) {
        String input = jsonPretty(trace.args());
        String output = trace.textSummary() == null ? "" : trace.textSummary();
        boolean[] inTr = new boolean[1];
        boolean[] outTr = new boolean[1];
        String inPreview = previewAndTruncate(input, 2000, inTr);
        String outPreview = previewAndTruncate(output, 2000, outTr);
        return new ToolCallView(trace.toolName(), trace.success(), inPreview, outPreview, inTr[0], outTr[0]);
    }

    private Message toModelMessage(AppStateRepository.ConversationMessageRow row) {
        return switch (row.role()) {
            case "assistant" -> new Message(Message.Role.ASSISTANT, row.content(), toolCalls(row.toolCallsJson()));
            case "tool" -> new Message(Message.Role.TOOL, row.content(), row.toolCallId());
            case "system" -> new Message(Message.Role.SYSTEM, row.content());
            case "user" -> new Message(Message.Role.USER, row.content());
            default -> throw new IllegalStateException("Unsupported role: " + row.role());
        };
    }

    private List<ToolCall> toolCalls(String json) {
        return toolCallPayloads(json).stream().map(payload -> new ToolCall(payload.toolCallId(), payload.toolName(), payload.arguments())).toList();
    }

    private List<ToolCallPayload> toolCallPayloads(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(json, TOOL_CALLS_TYPE);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read tool call JSON", e);
        }
    }

    private String json(Object value) {
        try {
            return value == null ? null : objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize JSON", e);
        }
    }

    private String jsonPretty(Object value) {
        try {
            return value == null ? null : objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize JSON", e);
        }
    }

    private Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse JSON", e);
        }
    }

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

    private static String publicId(String publicId) {
        return publicId == null || publicId.isBlank() ? UUID.randomUUID().toString() : publicId;
    }

    private ProjectView toProjectView(AppStateRepository.ProjectRow row) {
        return new ProjectView(row.id(), row.name(), row.normalizedPath());
    }

    private WorkspaceView toWorkspaceView(AppStateRepository.WorkspaceRow row) {
        return new WorkspaceView(row.id(), row.name(), row.normalizedPath());
    }

    private SessionView toSessionView(AppStateRepository.SessionRow row) {
        return new SessionView(row.id(), row.name());
    }

    private ChangedFileView toChangedFileView(AppStateRepository.ChangedFileRow row) {
        return new ChangedFileView(Math.toIntExact(row.id()), row.path(), row.diff());
    }

    private record ToolCallPayload(String toolCallId, String toolName, Map<String, Object> arguments) {}
}
