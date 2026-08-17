package com.judepereira.jupiter.persistence;

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
                                Long subagentSessionId, String subagentAgentId, String subagentAgentName, String status) {
        public ToolCallView(String toolCallId, String toolName, boolean success, String inputPreview, String outputPreview, boolean inputTruncated, boolean outputTruncated,
                            Long subagentSessionId, String subagentAgentId, String subagentAgentName) {
            this(toolCallId, toolName, success, inputPreview, outputPreview, inputTruncated, outputTruncated, subagentSessionId, subagentAgentId, subagentAgentName, null);
        }
    }

    public record ChatMessageMetadata(String agentId, String agentName, String modelId, String thinkingLevel) {
    }

    public record ChatMessageView(String role, String text, long ts, boolean pending, String id, List<ToolCallView> toolCalls, ChatMessageMetadata metadata) {
    }

    public record ChangedFileView(String key, ReviewSource source, Integer id, String path, String diff) {
    }

    public record SessionDetailView(List<ChatMessageView> chatMessages, List<ChangedFileView> changedFiles, boolean reviewPanelOpen,
                                    ReviewSource reviewSource, ChangedFileView selectedFile, String workspaceRoot) {
    }

    public record SubagentSessionDetailView(SessionDetailView sessionDetail, Long parentSessionId, String parentToolCallId,
                                            String subagentAgentId, String subagentAgentName) {
    }

    public record AppStateView(List<ProjectView> projects, ProjectView activeProject, List<WorkspaceView> workspaces, WorkspaceView activeWorkspace, List<SessionView> sessions, SessionView activeSession, SessionDetailView activeSessionDetail) {
    }

    public record QueuedChatTurn(ChatMessageView userMessage, ChatMessageView assistantMessage) {
    }

    public record ChangedFileDraft(String path, String diff) {
    }

    public record ToolCallTraceInput(String toolCallId, String toolName, Map<String, Object> args, boolean success, String textSummary, Map<String, Object> machineSummary) {
    }
}
