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
    }

    public record ProjectEnvironmentVariable(String name, String value) {
    }

    public record McpServerHeader(String name, String value) {
    }

    public record McpServerView(long id, String name, String url, boolean enabled, List<McpServerHeader> headers, List<Long> exposedProjectIds) {
    }

    public record WorkspaceView(long id, String name, String path, boolean unread, RailStatus railStatus) {

        public boolean inProgress() {
            return railStatus == RailStatus.IN_PROGRESS;
        }

        public boolean failed() {
            return railStatus == RailStatus.FAILED;
        }
    }

    public record SessionView(long id, String name, boolean unread, RailStatus railStatus) {

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
