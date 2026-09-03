package com.judepereira.jupiter.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class Persistence {

    public enum ReviewSource {
        SESSION,
        GIT
    }

    public enum RailStatus {
        NONE,
        IN_PROGRESS,
        FAILED
    }

    private Persistence() {
    }

    public record ProjectView(long id, String name, String path, String workspaceInitCommands, List<ProjectEnvironmentVariable> environmentVariables) {
        public ProjectView(long id, String name, String path, String workspaceInitCommands) {
            this(id, name, path, workspaceInitCommands, List.of());
        }
    }

    public record ProjectEnvironmentVariable(String name, String value) {
    }

    public record McpServerHeader(String name, String value) {
    }

    public record McpServerView(long id, String name, String url, boolean enabled, List<McpServerHeader> headers, List<Long> exposedProjectIds) {
        public McpServerView(long id, String name, String url, boolean enabled, List<McpServerHeader> headers) {
            this(id, name, url, enabled, headers, List.of());
        }
    }

    public record WorkspaceView(long id, String name, String path, boolean unread, RailStatus railStatus) {
        public WorkspaceView(long id, String name, String path, boolean unread) {
            this(id, name, path, unread, RailStatus.NONE);
        }

        public WorkspaceView(long id, String name, String path, boolean unread, boolean inProgress) {
            this(id, name, path, unread, inProgress ? RailStatus.IN_PROGRESS : RailStatus.NONE);
        }

        public WorkspaceView(long id, String name, String path) {
            this(id, name, path, false, RailStatus.NONE);
        }

        public boolean inProgress() {
            return railStatus == RailStatus.IN_PROGRESS;
        }

        public boolean failed() {
            return railStatus == RailStatus.FAILED;
        }
    }

    public record SessionView(long id, String name, boolean unread, RailStatus railStatus) {
        public SessionView(long id, String name, boolean unread) {
            this(id, name, unread, RailStatus.NONE);
        }

        public SessionView(long id, String name, boolean unread, boolean inProgress) {
            this(id, name, unread, inProgress ? RailStatus.IN_PROGRESS : RailStatus.NONE);
        }

        public SessionView(long id, String name) {
            this(id, name, false, RailStatus.NONE);
        }

        public boolean inProgress() {
            return railStatus == RailStatus.IN_PROGRESS;
        }

        public boolean failed() {
            return railStatus == RailStatus.FAILED;
        }
    }

    public record ToolCallView(String toolCallId, String toolName, boolean success, String inputPreview, String outputPreview, boolean inputTruncated, boolean outputTruncated,
                                Long subagentSessionId, String subagentAgentId, String subagentAgentName, String status,
                                String imageUrl, String imageAlt, String imagePath, String imageMediaType, String taskBody) {
        public ToolCallView(String toolCallId, String toolName, boolean success, String inputPreview, String outputPreview, boolean inputTruncated, boolean outputTruncated,
                            Long subagentSessionId, String subagentAgentId, String subagentAgentName, String status) {
            this(toolCallId, toolName, success, inputPreview, outputPreview, inputTruncated, outputTruncated, subagentSessionId, subagentAgentId, subagentAgentName, status, null, null, null, null, null);
        }

        public ToolCallView(String toolCallId, String toolName, boolean success, String inputPreview, String outputPreview, boolean inputTruncated, boolean outputTruncated,
                            Long subagentSessionId, String subagentAgentId, String subagentAgentName) {
            this(toolCallId, toolName, success, inputPreview, outputPreview, inputTruncated, outputTruncated, subagentSessionId, subagentAgentId, subagentAgentName, null, null, null, null, null, null);
        }

        public ToolCallView(String toolCallId, String toolName, boolean success, String inputPreview, String outputPreview, boolean inputTruncated, boolean outputTruncated,
                            Long subagentSessionId, String subagentAgentId, String subagentAgentName, String status, String taskBody) {
            this(toolCallId, toolName, success, inputPreview, outputPreview, inputTruncated, outputTruncated, subagentSessionId, subagentAgentId, subagentAgentName, status, null, null, null, null, taskBody);
        }

        public ToolCallView(String toolCallId, String toolName, boolean success, String inputPreview, String outputPreview, boolean inputTruncated, boolean outputTruncated,
                            Long subagentSessionId, String subagentAgentId, String subagentAgentName, String status, String imageUrl, String imageAlt, String imagePath, String imageMediaType) {
            this(toolCallId, toolName, success, inputPreview, outputPreview, inputTruncated, outputTruncated, subagentSessionId, subagentAgentId, subagentAgentName, status, imageUrl, imageAlt, imagePath, imageMediaType, null);
        }
    }

    public record ChatMessageMetadata(String agentId, String agentName, String modelId, String thinkingLevel) {
    }

    public record ChatMessageView(String role, String text, long ts, boolean pending, String id, Long completedTs, List<ToolCallView> toolCalls, ChatMessageMetadata metadata) {
    }

    public record ChangedFileView(String key, ReviewSource source, Integer id, String path, String diff) {
    }

    public record SessionDetailView(List<ChatMessageView> chatMessages, List<ChangedFileView> changedFiles, boolean reviewPanelOpen,
                                    ReviewSource reviewSource, ChangedFileView selectedFile, String workspaceRoot, String chatDraft) {
    }

    public record SubagentSessionDetailView(SessionDetailView sessionDetail, Long parentSessionId, String parentToolCallId,
                                            String subagentAgentId, String subagentAgentName) {
    }

    public record AppStateView(List<ProjectView> projects, ProjectView activeProject, List<WorkspaceView> workspaces, WorkspaceView activeWorkspace, List<SessionView> sessions, SessionView activeSession, SessionDetailView activeSessionDetail,
                               boolean autoGitUpdateEnabled) {
        public AppStateView(List<ProjectView> projects, ProjectView activeProject, List<WorkspaceView> workspaces, WorkspaceView activeWorkspace,
                            List<SessionView> sessions, SessionView activeSession, SessionDetailView activeSessionDetail) {
            this(projects, activeProject, workspaces, activeWorkspace, sessions, activeSession, activeSessionDetail, true);
        }
    }

    public record AutoGitUpdateFailureState(boolean failureEpisodeActive, Instant failureStartedAt, Instant lastSuccessAt) {
    }

    public record AutoGitUpdateFailureNotification(boolean firstFailure) {
    }

    public record LifecycleHookSettings(String assistantCompletedScript, String assistantErroredScript,
                                        String subagentCompletedScript, int timeoutSeconds) {
    }

    public record LifecycleHookContext(long sessionId, String projectName, String workspaceName, String sessionName,
                                       Map<String, String> projectEnvironmentVariables) {
    }

    public record QueuedChatTurn(ChatMessageView userMessage, ChatMessageView assistantMessage) {
    }

    public record ChangedFileDraft(String path, String diff) {
    }

    public record ToolCallTraceInput(String toolCallId, String toolName, Map<String, Object> args, boolean success, String textSummary, Map<String, Object> machineSummary) {
    }

    public record TokenUsageFact(String sessionUsageKey, long sessionIdSnapshot, long workspaceIdSnapshot, long projectIdSnapshot,
                                 String sessionNameSnapshot, String workspaceNameSnapshot, String projectNameSnapshot,
                                 String workspacePathSnapshot, String projectPathSnapshot, Instant occurredAt, Instant hourStartUtc,
                                 String modelKey, String operation, Integer inputTokenCount, Integer outputTokenCount, Integer totalTokenCount,
                                 Integer cachedInputTokenCount, Integer cacheWriteTokenCount, Integer reasoningTokenCount, String responseId,
                                 String responseModelId, String finishReason, Map<String, Object> providerMetadata) {}

    public record TokenUsageHourly(String sessionUsageKey, Instant hourStartUtc, String modelKey,
                                   long requestCount, Long inputTokenCount, Long outputTokenCount, Long totalTokenCount,
                                   Long cachedInputTokenCount, Long cacheWriteTokenCount, Long reasoningTokenCount,
                                   Instant lastOccurredAt) {}

    public record ProjectTokenUsageHourly(Instant hourStartUtc, String modelKey, long requestCount,
                                          Long inputTokenCount, Long outputTokenCount, Long totalTokenCount) {}
}
