package com.judepereira.jupiter2.persistence;

import java.util.List;
import java.util.Map;

public final class Persistence {

    private Persistence() {
    }

    public record ProjectView(long id, String name, String path) {
    }

    public record WorkspaceView(long id, String name, String path) {
    }

    public record SessionView(long id, String name) {
    }

    public record ToolCallView(String toolName, boolean success, String inputPreview, String outputPreview, boolean inputTruncated, boolean outputTruncated) {
    }

    public record ChatMessageMetadata(String agentId, String agentName, String modelId, String thinkingLevel) {
    }

    public record ChatMessageView(String role, String text, long ts, boolean pending, String id, List<ToolCallView> toolCalls, ChatMessageMetadata metadata) {
    }

    public record ChangedFileView(int id, String path, String diff) {
    }

    public record SessionDetailView(List<ChatMessageView> chatMessages, List<ChangedFileView> changedFiles, boolean reviewPanelOpen, ChangedFileView selectedFile, String workspaceRoot) {
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
