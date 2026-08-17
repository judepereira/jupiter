package com.judepereira.jupiter.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.judepereira.jupiter.agent.catalog.AgentDefinition;
import com.judepereira.jupiter.agent.llm.dto.Message;
import com.judepereira.jupiter.agent.llm.dto.ToolCall;
import com.judepereira.jupiter.persistence.Persistence.AppStateView;
import com.judepereira.jupiter.persistence.Persistence.ChatMessageMetadata;
import com.judepereira.jupiter.persistence.Persistence.ChatMessageView;
import com.judepereira.jupiter.persistence.Persistence.ChangedFileDraft;
import com.judepereira.jupiter.persistence.Persistence.ChangedFileView;
import com.judepereira.jupiter.persistence.Persistence.ProjectView;
import com.judepereira.jupiter.persistence.Persistence.QueuedChatTurn;
import com.judepereira.jupiter.persistence.Persistence.ReviewSource;
import com.judepereira.jupiter.persistence.Persistence.SessionDetailView;
import com.judepereira.jupiter.persistence.Persistence.RailStatus;
import com.judepereira.jupiter.persistence.Persistence.SessionView;
import com.judepereira.jupiter.persistence.Persistence.SubagentSessionDetailView;
import com.judepereira.jupiter.persistence.Persistence.ToolCallTraceInput;
import com.judepereira.jupiter.persistence.Persistence.ToolCallView;
import com.judepereira.jupiter.persistence.Persistence.WorkspaceView;
import com.judepereira.jupiter.ui.ActiveStreamRegistryService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.judepereira.jupiter.persistence.Persistence.ProjectEnvironmentVariable;

@Service
@RequiredArgsConstructor
public class AppStateService {

    private static final TypeReference<List<ToolCallPayload>> TOOL_CALLS_TYPE = new TypeReference<>() {};

    private final AppStateRepository repository;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final ActiveStreamRegistryService activeStreamRegistryService;

    public ActiveStreamRegistryService activeStreamRegistryService() {
        return activeStreamRegistryService;
    }

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
        long workspaceId = repository.insertWorkspace(projectId, "Default Workspace", normalizedPath, 1L, now);
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

        String normalizedBranchName = branchName.trim();
        if (createBranch) {
            if (normalizedBranchName.isBlank()) {
                throw new InvalidGitBranchNameException("Branch name is required", null);
            }
            validateGitBranchName(normalizedBranchName);
        }

        Path projectRoot = Path.of(project.normalizedPath());
        Path worktreePath = projectRoot.resolveSibling(".trees")
                .resolve(projectRoot.getFileName().toString())
                .resolve(normalizedBranchName)
                .toAbsolutePath()
                .normalize();
        runGitWorktreeAdd(projectRoot, worktreePath, normalizedBranchName, createBranch);

        long position = repository.nextWorkspacePosition(projectId);
        long workspaceId = repository.insertWorkspace(projectId, normalizedBranchName, worktreePath.toString(), position, now);
        createSessionInternal(workspaceId, now);
        return toWorkspaceView(repository.findWorkspace(workspaceId), activeStreamRegistryService.activeSessionIdsSnapshot());
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
        repository.updateProjectLastOpened(workspace.projectId(), now);
        repository.updateWorkspaceLastOpened(workspaceId, now);
        if (session == null) {
            repository.updateAppState(workspace.projectId(), workspaceId, null);
            return;
        }
        repository.updateSessionLastOpened(session.id(), now);
        repository.updateSessionUnread(session.id(), false);
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
    public void closeSession(long sessionId) {
        Instant now = Instant.now();
        var session = repository.findSession(sessionId);
        var workspace = repository.findWorkspace(session.workspaceId());
        var appState = repository.loadAppState();

        if (appState.activeSessionId() != null && appState.activeSessionId() == sessionId) {
            var next = repository.findNextSessionAfter(workspace.id(), session.position());
            if (next != null) {
                repository.updateProjectLastOpened(workspace.projectId(), now);
                repository.updateWorkspaceLastOpened(workspace.id(), now);
                repository.updateSessionLastOpened(next.id(), now);
                repository.updateAppState(workspace.projectId(), workspace.id(), next.id());
            } else {
                var previous = repository.findPreviousSessionBefore(workspace.id(), session.position());
                if (previous != null) {
                    repository.updateProjectLastOpened(workspace.projectId(), now);
                    repository.updateWorkspaceLastOpened(workspace.id(), now);
                    repository.updateSessionLastOpened(previous.id(), now);
                    repository.updateAppState(workspace.projectId(), workspace.id(), previous.id());
                } else {
                    repository.updateProjectLastOpened(workspace.projectId(), now);
                    repository.updateWorkspaceLastOpened(workspace.id(), now);
                    repository.updateAppState(workspace.projectId(), workspace.id(), null);
                }
            }
        }

        deleteSessionTree(sessionId);
    }

    @Transactional
    public void closeWorkspace(long workspaceId) {
        Instant now = Instant.now();
        var workspace = repository.findWorkspace(workspaceId);
        var project = repository.findProject(workspace.projectId());
        ensureWorkspaceCanBeClosed(workspace, project);
        var appState = repository.loadAppState();

        if (appState.activeWorkspaceId() != null && appState.activeWorkspaceId() == workspaceId) {
            var next = repository.findNextWorkspaceAfter(project.id(), workspace.position());
            if (next != null) {
                activateWorkspace(next.id());
            } else {
                var previous = repository.findPreviousWorkspaceBefore(project.id(), workspace.position());
                if (previous != null) {
                    activateWorkspace(previous.id());
                } else {
                    repository.updateProjectLastOpened(project.id(), now);
                    repository.updateAppState(project.id(), null, null);
                }
            }
        }

        for (var session : repository.listSessionsByWorkspace(workspaceId)) {
            deleteSessionTree(session.id());
        }
        repository.deleteWorkspace(workspaceId);
    }

    public WorkspaceCloseInspection inspectWorkspaceClose(long workspaceId) {
        var workspace = repository.findWorkspace(workspaceId);
        var project = repository.findProject(workspace.projectId());
        ensureWorkspaceCanBeClosed(workspace, project);

        GitCloseStatus gitStatus = inspectGitCloseStatus(Path.of(workspace.normalizedPath()));
        return new WorkspaceCloseInspection(workspace.id(), workspace.name(), workspace.normalizedPath(), project.normalizedPath(),
                gitStatus.uncommittedChanges(), gitStatus.unpushedCommits(), gitStatus.reasons());
    }

    public void removeWorkspaceWorktree(long workspaceId, boolean force) {
        var workspace = repository.findWorkspace(workspaceId);
        var project = repository.findProject(workspace.projectId());
        ensureWorkspaceCanBeClosed(workspace, project);
        Path projectRoot = Path.of(project.normalizedPath());
        Path workspacePath = Path.of(workspace.normalizedPath());
        List<String> command = force
                ? List.of("git", "worktree", "remove", "--force", workspacePath.toString())
                : List.of("git", "worktree", "remove", workspacePath.toString());
        runGitCommand(projectRoot, command);
    }

    @Transactional
    public void activateSession(long sessionId) {
        Instant now = Instant.now();
        var session = repository.findSession(sessionId);
        if (session.hidden()) {
            throw new IllegalStateException("Hidden subagent sessions cannot be activated: " + sessionId);
        }
        var workspace = repository.findWorkspace(session.workspaceId());
        repository.updateProjectLastOpened(workspace.projectId(), now);
        repository.updateSessionLastOpened(sessionId, now);
        repository.updateSessionUnread(sessionId, false);
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

    @Transactional
    public long createHiddenSubagentSession(long parentSessionId, String parentToolCallId, AgentDefinition subagent) {
        if (subagent == null) {
            throw new IllegalStateException("Subagent definition is required");
        }
        var parentSession = repository.findSession(parentSessionId);
        var workspace = repository.findWorkspace(parentSession.workspaceId());
        Long parentAssistantMessageId = repository.findLatestPendingVisibleAssistantMessage(parentSessionId)
                .map(AppStateRepository.ConversationMessageRow::id)
                .orElse(null);
        Instant now = Instant.now();
        long position = repository.nextSessionPosition(workspace.id());
        long sessionId = repository.insertSession(workspace.id(), "Subagent: " + subagent.name(), position, now, false, ReviewSource.SESSION, null,
                true, parentSessionId, parentToolCallId, subagent.id(), subagent.name(), parentAssistantMessageId);
        repository.updateProjectLastOpened(workspace.projectId(), now);
        repository.updateWorkspaceLastOpened(workspace.id(), now);
        repository.updateSessionLastOpened(sessionId, now);
        return sessionId;
    }

    public AppStateView loadViewData() {
        var appState = repository.loadAppState();
        Set<Long> activeSessionIds = activeStreamRegistryService.activeSessionIdsSnapshot();
        List<ProjectView> projects = repository.listVisibleProjects().stream().map(this::toProjectView).toList();
        ProjectView activeProject = appState.activeProjectId() == null ? null : toProjectView(repository.findProject(appState.activeProjectId()));

        List<WorkspaceView> workspaces = activeProject == null ? List.of() : repository.listWorkspacesByProject(activeProject.id()).stream().map(workspace -> toWorkspaceView(workspace, activeSessionIds)).toList();
        WorkspaceView activeWorkspace = appState.activeWorkspaceId() == null ? null : toWorkspaceView(repository.findWorkspace(appState.activeWorkspaceId()), activeSessionIds);

        AppStateRepository.SessionRow activeSessionRow = appState.activeSessionId() == null ? null : repository.findSession(appState.activeSessionId());
        List<SessionView> sessions = activeWorkspace == null ? List.of() : repository.listSessionsByWorkspace(activeWorkspace.id()).stream().map(session -> toSessionView(session, activeSessionIds)).toList();
        SessionView activeSession = activeSessionRow == null ? null : toSessionView(activeSessionRow, activeSessionIds);
        SessionDetailView sessionDetail = activeSession == null ? null : loadSessionDetail(activeSession.id());
        return new AppStateView(projects, activeProject, workspaces, activeWorkspace, sessions, activeSession, sessionDetail);
    }

    @Transactional
    public void updateProjectWorkspaceInitCommands(long projectId, String workspaceInitCommands) {
        String normalized = workspaceInitCommands == null || workspaceInitCommands.isBlank() ? null : workspaceInitCommands;
        repository.updateProjectWorkspaceInitCommands(projectId, normalized);
    }

    @Transactional
    public void updateProjectEnvironmentVariables(long projectId, List<ProjectEnvironmentVariable> environmentVariables) {
        repository.updateProjectEnvironmentVariables(projectId, json(normalizeEnvironmentVariables(environmentVariables)));
    }

    @Transactional(readOnly = true)
    public Map<String, String> loadSessionProjectEnvironmentVariables(long sessionId) {
        var session = repository.findSession(sessionId);
        var workspace = repository.findWorkspace(session.workspaceId());
        var project = repository.findProject(workspace.projectId());
        return toEnvironmentVariables(projectEnvironmentVariables(project.environmentVariables()));
    }

    @Transactional(readOnly = true)
    public Map<String, String> loadProjectEnvironmentVariables(long projectId) {
        return toEnvironmentVariables(projectEnvironmentVariables(repository.findProject(projectId).environmentVariables()));
    }

    @Transactional
    public QueuedChatTurn appendUserMessageAndPendingAssistant(long sessionId, String userPublicId, String assistantPublicId, String userText) {
        return appendUserMessageAndPendingAssistant(sessionId, userPublicId, assistantPublicId, userText, null);
    }

    @Transactional
    public QueuedChatTurn appendUserMessageAndPendingAssistant(long sessionId, String userPublicId, String assistantPublicId, String userText, ChatMessageMetadata assistantMetadata) {
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
        repository.insertConversationMessage(sessionId, assistantId, "assistant", turnId, assistantSequence, "Thinking…", null, null, true, false, true,
                assistantMetadata == null ? null : assistantMetadata.agentId(),
                assistantMetadata == null ? null : assistantMetadata.agentName(),
                assistantMetadata == null ? null : assistantMetadata.modelId(),
                assistantMetadata == null ? null : assistantMetadata.thinkingLevel(),
                null,
                null,
                now);
        return new QueuedChatTurn(new ChatMessageView("user", userText, now.toEpochMilli(), false, userId, null, List.of(), null),
                new ChatMessageView("assistant", "Thinking…", now.toEpochMilli(), true, assistantId, null, List.of(), assistantMetadata));
    }

    @Transactional
    public ChatMessageView appendPendingAssistantMessage(long sessionId, String assistantPublicId, String content, ChatMessageMetadata assistantMetadata) {
        Instant now = Instant.now();
        long turnId = repository.nextTurnId(sessionId);
        long assistantSequence = repository.nextMessageSequence(sessionId);
        String assistantId = publicId(assistantPublicId);
        repository.insertConversationMessage(sessionId, assistantId, "assistant", turnId, assistantSequence, content, null, null, true, false, true,
                assistantMetadata == null ? null : assistantMetadata.agentId(),
                assistantMetadata == null ? null : assistantMetadata.agentName(),
                assistantMetadata == null ? null : assistantMetadata.modelId(),
                assistantMetadata == null ? null : assistantMetadata.thinkingLevel(),
                null,
                null,
                now);
        applicationEventPublisher.publishEvent(new WorkspaceRailRefreshEvent());
        return new ChatMessageView("assistant", content, now.toEpochMilli(), true, assistantId, null, List.of(), assistantMetadata);
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
        repository.updateMessageContentAndPending(message.id(), text, true, false, null);
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
        ToolCallTraceInput normalizedTrace = new ToolCallTraceInput(toolCallId, trace.toolName(), trace.args(), trace.success(), trace.textSummary(), trace.machineSummary());
        List<ToolCallPayload> payloads = toolCallPayloads(assistantMessage.toolCallsJson());
        if (payloads.stream().anyMatch(payload -> toolCallId.equals(payload.toolCallId()))) {
            return traceToView(normalizedTrace);
        }

        String argsJson = json(normalizedTrace.args());
        String machineSummaryJson = json(normalizedTrace.machineSummary());
        repository.insertToolCallTrace(sessionId, assistantMessage.id(), sequence, normalizedTrace.toolCallId(), normalizedTrace.toolName(), normalizedTrace.success(), argsJson, normalizedTrace.textSummary(), machineSummaryJson, now);
        payloads.add(new ToolCallPayload(toolCallId, normalizedTrace.toolName(), normalizedTrace.args()));
        repository.updateMessageToolCalls(assistantMessage.id(), json(payloads));

        long assistantToolCallSequence = repository.nextMessageSequence(sessionId);
        repository.insertConversationMessage(sessionId, UUID.randomUUID().toString(), "assistant", assistantMessage.turnId(), assistantToolCallSequence,
                "", null, json(List.of(new ToolCallPayload(toolCallId, normalizedTrace.toolName(), normalizedTrace.args()))), false, true, false,
                assistantMessage.agentId(), assistantMessage.agentName(), assistantMessage.modelId(), assistantMessage.thinkingLevel(), null, null, now);

        long toolResultSequence = repository.nextMessageSequence(sessionId);
        repository.insertConversationMessage(sessionId, UUID.randomUUID().toString(), "tool", assistantMessage.turnId(), toolResultSequence,
                normalizedTrace.textSummary() == null ? "" : normalizedTrace.textSummary(), toolCallId, null, false, true, false, now);

        return traceToView(normalizedTrace);
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
        repository.updateMessageContentAndPending(assistantMessage.id(), finalText, false, true, Instant.now());
        markUnreadIfInactive(sessionId);
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
        repository.updateMessageContentAndPending(assistantMessage.id(), errorText, false, false, Instant.now());
        markUnreadIfInactive(sessionId);
        return toChatMessageView(repository.findMessageBySessionAndPublicId(sessionId, assistantPublicId), sessionId);
    }

    public void publishWorkspaceRailRefresh() {
        applicationEventPublisher.publishEvent(new WorkspaceRailRefreshEvent());
    }

    @Transactional
    public boolean toggleReviewPanel(long sessionId) {
        var session = repository.findSession(sessionId);
        boolean open = !session.reviewPanelOpen();
        repository.updateSessionReviewState(sessionId, open, session.reviewSource(), session.selectedChangedFileId());
        return open;
    }

    @Transactional
    public void selectSessionChangedFile(long sessionId, int changedFileId) {
        var file = repository.findChangedFile(changedFileId);
        if (file.sessionId() != sessionId) {
            throw new IllegalStateException("Changed file does not belong to session " + sessionId);
        }
        repository.updateSessionSelectedChangedFile(sessionId, file.id());
    }

    @Transactional
    public void clearSessionChangedFileSelection(long sessionId) {
        repository.updateSessionSelectedChangedFile(sessionId, null);
    }

    @Transactional
    public void selectChangedFile(long sessionId, int changedFileId) {
        selectSessionChangedFile(sessionId, changedFileId);
    }

    @Transactional
    public ChangedFileView selectGitChangedFile(long sessionId, String changedFilePath) {
        var session = repository.findSession(sessionId);
        var workspace = repository.findWorkspace(session.workspaceId());
        var gitFiles = listGitChangedFiles(Path.of(workspace.normalizedPath()));
        return gitFiles.stream()
                .filter(file -> file.path().equals(changedFilePath))
                .findFirst()
                .map(this::toChangedFileView)
                .orElseThrow(() -> new IllegalStateException("Git changed file not found: " + changedFilePath));
    }

    @Transactional
    public void switchReviewSource(long sessionId, ReviewSource reviewSource) {
        repository.updateSessionReviewSource(sessionId, reviewSource);
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
        repository.updateSessionSelectedChangedFile(sessionId, latestFileId);
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

    public ChatMessageView appendVisibleSystemMessage(long sessionId, String content) {
        return appendVisibleSystemMessage(sessionId, content, null);
    }

    public ChatMessageView appendVisibleSystemMessage(long sessionId, String content, Long compactedThroughTurnId) {
        Instant now = Instant.now();
        long turnId = repository.nextTurnId(sessionId);
        long sequence = repository.nextMessageSequence(sessionId);
        String id = UUID.randomUUID().toString();
        repository.insertConversationMessage(sessionId, id, "system", turnId, sequence, content, null, null, true, true, false,
                null, null, null, null, compactedThroughTurnId, null, now);
        return toChatMessageView(repository.findMessageBySessionAndPublicId(sessionId, id), sessionId);
    }

    public List<AppStateRepository.ConversationMessageRow> listConversationMessages(long sessionId) {
        return repository.listMessagesBySession(sessionId);
    }

    @Transactional
    public void markTurnsIncludeInModelFalse(long sessionId, long maxTurnId) {
        repository.updateConversationMessagesIncludeInModelUpToTurnId(sessionId, maxTurnId, false);
    }

    public List<Message> buildConversationHistory(long sessionId) {
        var messages = repository.listMessagesBySession(sessionId).stream()
                .filter(message -> message.includeInModel() && !message.pending())
                .toList();

        long compactionCutoffTurnId = messages.stream()
                .filter(message -> "system".equals(message.role()) && message.showInChat() && message.compactedThroughTurnId() != null)
                .mapToLong(AppStateRepository.ConversationMessageRow::compactedThroughTurnId)
                .max()
                .orElse(Long.MIN_VALUE);
        if (compactionCutoffTurnId != Long.MIN_VALUE) {
            messages = messages.stream()
                    .filter(message -> message.turnId() > compactionCutoffTurnId)
                    .toList();
        }

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
        repository.updateProjectLastOpened(projectId, now);
        repository.updateWorkspaceLastOpened(workspace.id(), now);
        if (session == null) {
            repository.updateAppState(projectId, workspace.id(), null);
            return;
        }
        repository.updateSessionLastOpened(session.id(), now);
        repository.updateSessionUnread(session.id(), false);
        repository.updateAppState(projectId, workspace.id(), session.id());
    }

    private boolean markUnreadIfInactive(long sessionId) {
        var session = repository.findSession(sessionId);
        if (session.hidden() || session.unread()) {
            return false;
        }

        var appState = repository.loadAppState();
        if (appState.activeSessionId() != null && appState.activeSessionId() == sessionId) {
            return false;
        }

        repository.updateSessionUnread(sessionId, true);
        applicationEventPublisher.publishEvent(new SessionMarkedUnreadEvent(sessionId));
        return true;
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
        long sessionId = repository.insertSession(workspaceId, sessionName, position, now, false, ReviewSource.SESSION, null);
        repository.insertConversationMessage(sessionId, UUID.randomUUID().toString(), "system", 0L, 0L,
                "Welcome to Jupiter. Let's get started - what's on your mind?", null, null, true, false, false, null, null, null, null, null, null, now);
        var workspace = repository.findWorkspace(workspaceId);
        repository.updateProjectLastOpened(workspace.projectId(), now);
        repository.updateWorkspaceLastOpened(workspaceId, now);
        repository.updateSessionLastOpened(sessionId, now);
        repository.updateAppState(workspace.projectId(), workspaceId, sessionId);
        return sessionId;
    }

    private void deleteSessionTree(long sessionId) {
        for (var child : repository.listChildSessionsByParentSession(sessionId)) {
            deleteSessionTree(child.id());
        }
        repository.deleteChangedFilesBySession(sessionId);
        repository.deleteToolCallTracesBySession(sessionId);
        repository.deleteConversationMessagesBySession(sessionId);
        repository.deleteSession(sessionId);
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

    private void validateGitBranchName(String branchName) {
        try {
            Process process = new ProcessBuilder("git", "check-ref-format", "--branch", branchName)
                    .start();
            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new InvalidGitBranchNameException("Invalid Git branch name: " + branchName, gitOutput(stdout, stderr));
            }
        } catch (InvalidGitBranchNameException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to validate git branch name", e);
        }
    }

    private String gitOutput(String stdout, String stderr) {
        return List.of(nonBlank(stdout), nonBlank(stderr)).stream()
                .filter(value -> !value.isBlank())
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private String nonBlank(String value) {
        return value == null ? "" : value.trim();
    }

    private GitCloseStatus inspectGitCloseStatus(Path workspacePath) {
        String status = runGitCommand(workspacePath, List.of("git", "status", "--porcelain")).stdout().trim();
        boolean uncommittedChanges = !status.isBlank();

        boolean unpushedCommits = false;
        GitCommandResult head = runGitCommandAllowingMissingHead(workspacePath, List.of("git", "rev-parse", "--verify", "--quiet", "HEAD"));
        if (head.exists()) {
            GitCommandResult upstream = runGitCommandAllowingMissingUpstream(workspacePath,
                    List.of("git", "rev-parse", "--abbrev-ref", "--symbolic-full-name", "@{upstream}"));
            if (upstream.exists()) {
                String count = runGitCommand(workspacePath, List.of("git", "rev-list", "--count", upstream.stdout().trim() + "..HEAD")).stdout().trim();
                unpushedCommits = !count.isBlank() && Long.parseLong(count) > 0;
            }
        }

        List<String> reasons = new ArrayList<>();
        if (uncommittedChanges) {
            reasons.add("uncommitted changes");
        }
        if (unpushedCommits) {
            reasons.add("unpushed commits");
        }
        return new GitCloseStatus(uncommittedChanges, unpushedCommits, reasons);
    }

    private GitCommandResult runGitCommand(Path cwd, List<String> command) {
        try {
            Process process = new ProcessBuilder(command)
                    .directory(cwd.toFile())
                    .start();
            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IllegalStateException("git command failed with exit code " + exitCode + "\nstdout:\n" + stdout + "\nstderr:\n" + stderr);
            }
            return new GitCommandResult(stdout, stderr, false);
        } catch (Exception e) {
            if (e instanceof IllegalStateException) {
                throw (IllegalStateException) e;
            }
            throw new IllegalStateException("git command failed", e);
        }
    }

    private GitCommandResult runGitCommandAllowingMissingUpstream(Path cwd, List<String> command) {
        try {
            Process process = new ProcessBuilder(command)
                    .directory(cwd.toFile())
                    .start();
            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            int exitCode = process.waitFor();
            if (exitCode == 0) {
                return new GitCommandResult(stdout, stderr, false);
            }
            if (stderr.contains("no upstream")) {
                return new GitCommandResult(stdout, stderr, true);
            }
            throw new IllegalStateException("git command failed with exit code " + exitCode + "\nstdout:\n" + stdout + "\nstderr:\n" + stderr);
        } catch (Exception e) {
            if (e instanceof IllegalStateException) {
                throw (IllegalStateException) e;
            }
            throw new IllegalStateException("git command failed", e);
        }
    }

    private GitCommandResult runGitCommandAllowingMissingHead(Path cwd, List<String> command) {
        try {
            Process process = new ProcessBuilder(command)
                    .directory(cwd.toFile())
                    .start();
            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            int exitCode = process.waitFor();
            return new GitCommandResult(stdout, stderr, exitCode != 0);
        } catch (Exception e) {
            if (e instanceof IllegalStateException) {
                throw (IllegalStateException) e;
            }
            throw new IllegalStateException("git command failed", e);
        }
    }

    private void ensureWorkspaceCanBeClosed(AppStateRepository.WorkspaceRow workspace, AppStateRepository.ProjectRow project) {
        if (workspace.normalizedPath().equals(project.normalizedPath())) {
            throw new IllegalStateException("Default workspace cannot be deleted: " + workspace.id());
        }
    }

    public SessionDetailView loadSessionDetail(long sessionId) {
        var session = repository.findSession(sessionId);
        var workspace = repository.findWorkspace(session.workspaceId());
        List<AppStateRepository.ConversationMessageRow> visibleMessages = repository.listVisibleMessagesBySession(sessionId);
        List<ChatMessageView> messages = visibleMessages.stream().map(message -> toChatMessageView(message, sessionId)).toList();
        messages = applySyntheticSubagentToolCalls(sessionId, visibleMessages, messages);
        messages = injectSyntheticFailedAssistantMessage(sessionId, visibleMessages, messages);
        messages = clearStalePendingAssistantBindings(messages);
        ReviewSource reviewSource = session.reviewSource();
        List<ChangedFileView> files = reviewSource == ReviewSource.GIT
                ? listGitChangedFiles(Path.of(workspace.normalizedPath())).stream().map(this::toChangedFileView).toList()
                : repository.listChangedFilesBySession(sessionId).stream().map(this::toChangedFileView).toList();
        ChangedFileView selected = reviewSource == ReviewSource.GIT
                ? null
                : session.selectedChangedFileId() == null ? null : toChangedFileView(repository.findChangedFile(session.selectedChangedFileId()));
        return new SessionDetailView(messages, files, session.reviewPanelOpen(), reviewSource, selected, workspace.normalizedPath());
    }

    private List<ChatMessageView> injectSyntheticFailedAssistantMessage(long sessionId, List<AppStateRepository.ConversationMessageRow> visibleMessages, List<ChatMessageView> messages) {
        if (messages.isEmpty()) {
            return messages;
        }

        AppStateRepository.ConversationMessageRow targetMessage = repository.findLatestPendingVisibleAssistantMessage(sessionId).orElse(null);
        if (targetMessage == null || !targetMessage.pending()) {
            return messages;
        }

        if (activeStreamRegistryService.hasActiveStreamForAssistantId(targetMessage.publicId())) {
            return messages;
        }

        int targetIndex = findVisibleMessageIndex(messages, targetMessage.publicId());
        if (targetIndex < 0) {
            return messages;
        }

        ChatMessageView visibleRow = messages.get(targetIndex);
        if (!visibleRow.pending() || !"assistant".equals(visibleRow.role())) {
            return messages;
        }

        ArrayList<ChatMessageView> updated = new ArrayList<>(messages);
        updated.set(targetIndex, new ChatMessageView(
                visibleRow.role(),
                "*Assistant stream failed because the process ended before this response could be completed. Restart the request to continue.*",
                visibleRow.ts(),
                false,
                visibleRow.id(),
                visibleRow.completedTs(),
                visibleRow.toolCalls(),
                visibleRow.metadata()));
        return updated;
    }

    private List<ChatMessageView> clearStalePendingAssistantBindings(List<ChatMessageView> messages) {
        if (messages.isEmpty()) {
            return messages;
        }

        ArrayList<ChatMessageView> updated = null;
        for (int i = 0; i < messages.size(); i++) {
            ChatMessageView message = messages.get(i);
            if (!message.pending() || !"assistant".equals(message.role()) || activeStreamRegistryService.hasActiveStreamForAssistantId(message.id())) {
                continue;
            }

            if (updated == null) {
                updated = new ArrayList<>(messages);
            }
            updated.set(i, new ChatMessageView(message.role(), message.text(), message.ts(), false, message.id(), message.completedTs(), message.toolCalls(), message.metadata()));
        }

        return updated == null ? messages : updated;
    }

    private int findVisibleMessageIndex(List<ChatMessageView> messages, String publicId) {
        if (publicId == null) {
            return -1;
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (publicId.equals(messages.get(i).id())) {
                return i;
            }
        }
        return -1;
    }

    private List<ChatMessageView> applySyntheticSubagentToolCalls(long parentSessionId, List<AppStateRepository.ConversationMessageRow> visibleMessages, List<ChatMessageView> messages) {
        if (messages.isEmpty()) {
            return messages;
        }

        Map<Long, Integer> pendingAssistantIndexByMessageId = new java.util.HashMap<>();
        int latestPendingAssistantIndex = -1;
        for (int i = 0; i < messages.size(); i++) {
            ChatMessageView message = messages.get(i);
            if (message.pending() && "assistant".equals(message.role())) {
                pendingAssistantIndexByMessageId.put(visibleMessages.get(i).id(), i);
                latestPendingAssistantIndex = i;
            }
        }
        if (latestPendingAssistantIndex < 0) {
            return messages;
        }

        Map<Integer, List<ToolCallView>> syntheticByMessageIndex = new java.util.HashMap<>();
        List<ToolCallView> legacySyntheticToolCalls = new ArrayList<>();
        for (var childSession : repository.listChildSessionsByParentSession(parentSessionId)) {
            if (!childSession.hidden()) {
                continue;
            }
            String parentToolCallId = childSession.parentToolCallId();
            if (parentToolCallId == null || parentToolCallId.isBlank()) {
                continue;
            }
            ToolCallView syntheticToolCall = new ToolCallView(parentToolCallId, "task", true, "", "running", false, false,
                    childSession.id(), childSession.subagentAgentId(), childSession.subagentAgentName(), "running");
            Long parentAssistantMessageId = childSession.parentAssistantMessageId();
            if (parentAssistantMessageId != null) {
                Integer messageIndex = pendingAssistantIndexByMessageId.get(parentAssistantMessageId);
                if (messageIndex != null) {
                    ChatMessageView targetMessage = messages.get(messageIndex);
                    boolean toolCallAlreadyPresent = targetMessage.toolCalls().stream()
                            .anyMatch(toolCall -> parentToolCallId.equals(toolCall.toolCallId()));
                    if (!toolCallAlreadyPresent) {
                        syntheticByMessageIndex.computeIfAbsent(messageIndex, ignored -> new ArrayList<>()).add(syntheticToolCall);
                    }
                }
                continue;
            }
            if (!repository.existsToolCallTraceBySessionAndToolCallId(parentSessionId, parentToolCallId)) {
                legacySyntheticToolCalls.add(syntheticToolCall);
            }
        }

        if (syntheticByMessageIndex.isEmpty() && legacySyntheticToolCalls.isEmpty()) {
            return messages;
        }

        ArrayList<ChatMessageView> updated = new ArrayList<>(messages);
        for (var entry : syntheticByMessageIndex.entrySet()) {
            ChatMessageView message = updated.get(entry.getKey());
            ArrayList<ToolCallView> mergedToolCalls = new ArrayList<>(message.toolCalls());
            mergedToolCalls.addAll(entry.getValue());
            updated.set(entry.getKey(), new ChatMessageView(message.role(), message.text(), message.ts(), message.pending(), message.id(), message.completedTs(), mergedToolCalls, message.metadata()));
        }

        if (!legacySyntheticToolCalls.isEmpty()) {
            ChatMessageView pendingAssistant = updated.get(latestPendingAssistantIndex);
            ArrayList<ToolCallView> mergedToolCalls = new ArrayList<>(pendingAssistant.toolCalls());
            mergedToolCalls.addAll(legacySyntheticToolCalls);
            updated.set(latestPendingAssistantIndex, new ChatMessageView(pendingAssistant.role(), pendingAssistant.text(), pendingAssistant.ts(), pendingAssistant.pending(),
                    pendingAssistant.id(), pendingAssistant.completedTs(), mergedToolCalls, pendingAssistant.metadata()));
        }

        return updated;
    }

    public SubagentSessionDetailView loadSubagentSessionDetail(long sessionId) {
        var session = repository.findSession(sessionId);
        if (!session.hidden()) {
            throw new IllegalStateException("Session is not a hidden subagent session: " + sessionId);
        }
        return new SubagentSessionDetailView(loadSessionDetail(sessionId), session.parentSessionId(), session.parentToolCallId(),
                session.subagentAgentId(), session.subagentAgentName());
    }

    private ChatMessageView toChatMessageView(AppStateRepository.ConversationMessageRow message, long sessionId) {
        List<ToolCallPayload> payloads = toolCallPayloads(message.toolCallsJson());
        List<AppStateRepository.ToolCallTraceRow> traces = repository.listToolCallTracesByAssistantMessage(message.id());
        List<ToolCallView> toolCalls = new ArrayList<>(traces.size());
        for (int i = 0; i < traces.size(); i++) {
            AppStateRepository.ToolCallTraceRow trace = traces.get(i);
            String toolCallId = trace.toolCallId() != null ? trace.toolCallId() : i < payloads.size() ? payloads.get(i).toolCallId() : null;
            toolCalls.add(toToolCallView(trace, toolCallId));
        }
        return new ChatMessageView(message.role(), message.content(), message.createdAt().toEpochMilli(), message.pending(), message.publicId(),
                message.completedAt() == null ? null : message.completedAt().toEpochMilli(), toolCalls,
                message.agentId() == null && message.agentName() == null && message.modelId() == null && message.thinkingLevel() == null ? null :
                        new ChatMessageMetadata(message.agentId(), message.agentName(), message.modelId(), message.thinkingLevel()));
    }

    private ToolCallView toToolCallView(AppStateRepository.ToolCallTraceRow trace, String toolCallId) {
        return traceToView(new ToolCallTraceInput(toolCallId, trace.toolName(), readMap(trace.argsJson()), trace.success(), trace.textSummary(), readMap(trace.machineSummaryJson())));
    }

    private ToolCallView traceToView(ToolCallTraceInput trace) {
        String input = jsonPretty(trace.args());
        String output = trace.textSummary() == null ? "" : trace.textSummary();
        boolean[] inTr = new boolean[1];
        boolean[] outTr = new boolean[1];
        String inPreview = previewAndTruncate(input, 2000, inTr);
        String outPreview = previewAndTruncate(output, 2000, outTr);
        SubagentLinkInfo subagent = subagentLinkInfo(trace.machineSummary());
        return new ToolCallView(trace.toolCallId(), trace.toolName(), trace.success(), inPreview, outPreview, inTr[0], outTr[0], subagent.subagentSessionId(),
                subagent.subagentAgentId(), subagent.subagentAgentName(), null);
    }

    private SubagentLinkInfo subagentLinkInfo(Map<String, Object> machineSummary) {
        if (machineSummary == null || machineSummary.isEmpty()) {
            return new SubagentLinkInfo(null, null, null);
        }
        return new SubagentLinkInfo(asLong(machineSummary.get("subagentSessionId")), asString(machineSummary.get("subagentAgentId")), asString(machineSummary.get("subagentAgentName")));
    }

    private Long asLong(Object value) {
        if (value instanceof Long l) {
            return l;
        }
        if (value instanceof Integer i) {
            return i.longValue();
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            return Long.parseLong(s);
        }
        return null;
    }

    private String asString(Object value) {
        return value instanceof String s && !s.isBlank() ? s : null;
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

    private List<ProjectEnvironmentVariable> normalizeEnvironmentVariables(List<ProjectEnvironmentVariable> environmentVariables) {
        if (environmentVariables == null || environmentVariables.isEmpty()) {
            return List.of();
        }

        Map<String, ProjectEnvironmentVariable> deduped = new LinkedHashMap<>();
        for (ProjectEnvironmentVariable environmentVariable : environmentVariables) {
            if (environmentVariable == null) {
                continue;
            }
            String name = environmentVariable.name() == null ? "" : environmentVariable.name().trim();
            if (name.isBlank()) {
                continue;
            }
            deduped.remove(name);
            deduped.put(name, new ProjectEnvironmentVariable(name, environmentVariable.value()));
        }
        return List.copyOf(deduped.values());
    }

    private List<ProjectEnvironmentVariable> projectEnvironmentVariables(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return normalizeEnvironmentVariables(objectMapper.readValue(json, new TypeReference<List<ProjectEnvironmentVariable>>() {}));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read project environment variables JSON", e);
        }
    }

    private Map<String, String> toEnvironmentVariables(List<ProjectEnvironmentVariable> environmentVariables) {
        Map<String, String> vars = new LinkedHashMap<>();
        for (ProjectEnvironmentVariable environmentVariable : environmentVariables) {
            vars.put(environmentVariable.name(), environmentVariable.value() == null ? "" : environmentVariable.value());
        }
        return Map.copyOf(vars);
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

    private record SubagentLinkInfo(Long subagentSessionId, String subagentAgentId, String subagentAgentName) {}

    private ProjectView toProjectView(AppStateRepository.ProjectRow row) {
        String workspaceInitCommands = row.workspaceInitCommands() == null || row.workspaceInitCommands().isBlank() ? null : row.workspaceInitCommands();
        List<ProjectEnvironmentVariable> environmentVariables = projectEnvironmentVariables(row.environmentVariables());
        return new ProjectView(row.id(), row.name(), row.normalizedPath(), workspaceInitCommands, environmentVariables);
    }

    private WorkspaceView toWorkspaceView(AppStateRepository.WorkspaceRow row) {
        return new WorkspaceView(row.id(), row.name(), row.normalizedPath(), row.unread(), workspaceRailStatus(row.id(), activeStreamRegistryService.activeSessionIdsSnapshot()));
    }

    private WorkspaceView toWorkspaceView(AppStateRepository.WorkspaceRow row, Set<Long> activeSessionIds) {
        return new WorkspaceView(row.id(), row.name(), row.normalizedPath(), row.unread(), workspaceRailStatus(row.id(), activeSessionIds));
    }

    private SessionView toSessionView(AppStateRepository.SessionRow row) {
        return new SessionView(row.id(), row.name(), row.unread(), railStatus(row.inProgress(), activeStreamRegistryService.activeSessionIdsSnapshot().contains(row.id())));
    }

    private SessionView toSessionView(AppStateRepository.SessionRow row, Set<Long> activeSessionIds) {
        return new SessionView(row.id(), row.name(), row.unread(), railStatus(row.inProgress(), activeSessionIds.contains(row.id())));
    }

    private RailStatus workspaceRailStatus(long workspaceId, Set<Long> activeSessionIds) {
        RailStatus status = RailStatus.NONE;
        for (AppStateRepository.SessionRow session : repository.listSessionsByWorkspace(workspaceId)) {
            RailStatus sessionStatus = railStatus(session.inProgress(), activeSessionIds.contains(session.id()));
            if (sessionStatus == RailStatus.FAILED) {
                return RailStatus.FAILED;
            }
            if (sessionStatus == RailStatus.IN_PROGRESS) {
                status = RailStatus.IN_PROGRESS;
            }
        }
        return status;
    }

    private RailStatus railStatus(boolean dbPending, boolean hasActiveStream) {
        if (dbPending && hasActiveStream) {
            return RailStatus.IN_PROGRESS;
        }
        if (dbPending) {
            return RailStatus.FAILED;
        }
        return hasActiveStream ? RailStatus.FAILED : RailStatus.NONE;
    }

    private ChangedFileView toChangedFileView(AppStateRepository.ChangedFileRow row) {
        return new ChangedFileView("session:" + row.id(), ReviewSource.SESSION, Math.toIntExact(row.id()), row.path(), row.diff());
    }

    private ChangedFileView toChangedFileView(GitChangedFile file) {
        return new ChangedFileView("git:" + file.path(), ReviewSource.GIT, null, file.path(), file.diff());
    }

    private record ToolCallPayload(String toolCallId, String toolName, Map<String, Object> arguments) {}

    private List<GitChangedFile> listGitChangedFiles(Path workspaceRoot) {
        boolean hasHead = runGitCommandAllowingMissingHead(workspaceRoot, List.of("git", "rev-parse", "--verify", "HEAD")).exists();
        String status = runGitCommand(workspaceRoot, List.of("git", "status", "--porcelain", "-z", "--untracked-files=all")).stdout();
        return parseGitStatus(status).stream()
                .map(entry -> new GitChangedFile(entry.path(), entry.untracked()
                        ? readUntrackedFileDiff(workspaceRoot, entry.path())
                        : readTrackedFileDiff(workspaceRoot, entry.path(), hasHead)))
                .sorted(Comparator.comparing(GitChangedFile::path))
                .toList();
    }

    private List<GitStatusEntry> parseGitStatus(String status) {
        List<GitStatusEntry> entries = new ArrayList<>();
        int i = 0;
        while (i < status.length()) {
            String code = status.substring(i, i + 2);
            i += 3;
            String path = readNullTerminated(status, i);
            i += path.length() + 1;
            if (code.charAt(0) == 'R' || code.charAt(0) == 'C') {
                path = readNullTerminated(status, i);
                i += path.length() + 1;
            }
            if (!"!!".equals(code)) {
                entries.add(new GitStatusEntry(path, "??".equals(code)));
            }
        }
        return entries;
    }

    private String readNullTerminated(String value, int offset) {
        int end = value.indexOf('\0', offset);
        if (end < 0) {
            throw new IllegalStateException("Malformed git status output");
        }
        return value.substring(offset, end);
    }

    private String readTrackedFileDiff(Path workspaceRoot, String path, boolean hasHead) {
        if (hasHead) {
            return runGitCommand(workspaceRoot, List.of("git", "diff", "HEAD", "--", path)).stdout().stripTrailing();
        }
        String staged = runGitCommand(workspaceRoot, List.of("git", "diff", "--cached", "--", path)).stdout().stripTrailing();
        String unstaged = runGitCommand(workspaceRoot, List.of("git", "diff", "--", path)).stdout().stripTrailing();
        if (staged.isBlank()) {
            return unstaged;
        }
        if (unstaged.isBlank()) {
            return staged;
        }
        return staged + "\n" + unstaged;
    }

    private String readUntrackedFileDiff(Path workspaceRoot, String path) {
        Path file = workspaceRoot.resolve(path).normalize();
        try {
            return "+++ " + path + "\n" + Files.readString(file);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read untracked file: " + path, e);
        }
    }

    private record GitStatusEntry(String path, boolean untracked) {}

    private record GitChangedFile(String path, String diff) {}

    public record WorkspaceCloseInspection(long workspaceId, String workspaceName, String workspacePath, String projectPath,
                                            boolean uncommittedChanges, boolean unpushedCommits, List<String> reasons) {}

    private record GitCloseStatus(boolean uncommittedChanges, boolean unpushedCommits, List<String> reasons) {}

    private record GitCommandResult(String stdout, String stderr, boolean missingRef) {
        boolean exists() {
            return !missingRef;
        }

        boolean missingUpstream() {
            return missingRef;
        }
    }
}
