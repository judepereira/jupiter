package com.judepereira.jupiter2.persistence;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
class AppStateRepository {

    private final NamedParameterJdbcTemplate jdbc;

    AppStateRow loadAppState() {
        return jdbc.queryForObject("SELECT active_project_id, active_workspace_id, active_session_id FROM app_state WHERE id = 1", new MapSqlParameterSource(), (rs, rowNum) -> new AppStateRow(
                rs.getObject("active_project_id", Long.class),
                rs.getObject("active_workspace_id", Long.class),
                rs.getObject("active_session_id", Long.class)
        ));
    }

    void updateAppState(Long projectId, Long workspaceId, Long sessionId) {
        jdbc.update("UPDATE app_state SET active_project_id = :projectId, active_workspace_id = :workspaceId, active_session_id = :sessionId WHERE id = 1",
                new MapSqlParameterSource()
                        .addValue("projectId", projectId)
                        .addValue("workspaceId", workspaceId)
                        .addValue("sessionId", sessionId));
    }

    Optional<ProjectRow> findProjectByNormalizedPath(String normalizedPath) {
        return queryOne("SELECT * FROM projects WHERE normalized_path = :normalizedPath", new MapSqlParameterSource("normalizedPath", normalizedPath), this::mapProject);
    }

    ProjectRow findProject(long id) {
        return queryRequired("SELECT * FROM projects WHERE id = :id", new MapSqlParameterSource("id", id), this::mapProject, "project " + id);
    }

    List<ProjectRow> listVisibleProjects() {
        return jdbc.query("SELECT * FROM projects WHERE closed_at IS NULL ORDER BY display_order ASC", new MapSqlParameterSource(), this::mapProject);
    }

    ProjectRow findNextVisibleProjectAfter(long displayOrder) {
        return queryOne("SELECT * FROM projects WHERE closed_at IS NULL AND display_order > :displayOrder ORDER BY display_order ASC LIMIT 1",
                new MapSqlParameterSource("displayOrder", displayOrder), this::mapProject).orElse(null);
    }

    ProjectRow findPreviousVisibleProjectBefore(long displayOrder) {
        return queryOne("SELECT * FROM projects WHERE closed_at IS NULL AND display_order < :displayOrder ORDER BY display_order DESC LIMIT 1",
                new MapSqlParameterSource("displayOrder", displayOrder), this::mapProject).orElse(null);
    }

    long nextProjectDisplayOrder() {
        Long value = jdbc.queryForObject("SELECT COALESCE(MAX(display_order), 0) + 1 FROM projects", new MapSqlParameterSource(), Long.class);
        return value == null ? 1L : value;
    }

    long insertProject(String name, String normalizedPath, long displayOrder, Instant now) {
        return insertAndReturnId("""
                INSERT INTO projects (name, normalized_path, display_order, closed_at, created_at, last_opened_at)
                VALUES (:name, :normalizedPath, :displayOrder, NULL, :createdAt, :lastOpenedAt)
                """, params -> params
                .addValue("name", name)
                .addValue("normalizedPath", normalizedPath)
                .addValue("displayOrder", displayOrder)
                .addValue("createdAt", Timestamp.from(now))
                .addValue("lastOpenedAt", Timestamp.from(now)));
    }

    void reopenProject(long projectId, String name, long displayOrder, Instant now) {
        var params = new MapSqlParameterSource()
                .addValue("projectId", projectId)
                .addValue("displayOrder", displayOrder)
                .addValue("lastOpenedAt", Timestamp.from(now));
        String sql;
        if (name == null || name.isBlank()) {
            sql = "UPDATE projects SET closed_at = NULL, display_order = :displayOrder, last_opened_at = :lastOpenedAt WHERE id = :projectId";
        } else {
            sql = "UPDATE projects SET name = :name, closed_at = NULL, display_order = :displayOrder, last_opened_at = :lastOpenedAt WHERE id = :projectId";
            params.addValue("name", name);
        }
        jdbc.update(sql, params);
    }

    void closeProject(long projectId, Instant now) {
        jdbc.update("UPDATE projects SET closed_at = :closedAt WHERE id = :projectId",
                new MapSqlParameterSource().addValue("closedAt", Timestamp.from(now)).addValue("projectId", projectId));
    }

    void updateProjectLastOpened(long projectId, Instant now) {
        jdbc.update("UPDATE projects SET last_opened_at = :lastOpenedAt WHERE id = :projectId",
                new MapSqlParameterSource().addValue("projectId", projectId).addValue("lastOpenedAt", Timestamp.from(now)));
    }

    WorkspaceRow findWorkspace(long workspaceId) {
        return queryRequired("SELECT * FROM workspaces WHERE id = :id",
                new MapSqlParameterSource("id", workspaceId), this::mapWorkspace, "workspace " + workspaceId);
    }

    List<WorkspaceRow> listWorkspacesByProject(long projectId) {
        return jdbc.query("SELECT * FROM workspaces WHERE project_id = :projectId ORDER BY position ASC",
                new MapSqlParameterSource("projectId", projectId), this::mapWorkspace);
    }

    WorkspaceRow findWorkspaceToActivate(long projectId) {
        return queryOne("""
                SELECT * FROM workspaces
                WHERE project_id = :projectId
                ORDER BY CASE WHEN last_opened_at IS NULL THEN 1 ELSE 0 END, last_opened_at DESC, position ASC
                LIMIT 1
                """, new MapSqlParameterSource("projectId", projectId), this::mapWorkspace).orElse(null);
    }

    long nextWorkspacePosition(long projectId) {
        Long value = jdbc.queryForObject("SELECT COALESCE(MAX(position), 0) + 1 FROM workspaces WHERE project_id = :projectId",
                new MapSqlParameterSource("projectId", projectId), Long.class);
        return value == null ? 1L : value;
    }

    long insertWorkspace(long projectId, String name, String normalizedPath, long position, Instant now) {
        return insertAndReturnId("""
                INSERT INTO workspaces (project_id, name, normalized_path, position, created_at, last_opened_at)
                VALUES (:projectId, :name, :normalizedPath, :position, :createdAt, :lastOpenedAt)
                """, params -> params
                .addValue("projectId", projectId)
                .addValue("name", name)
                .addValue("normalizedPath", normalizedPath)
                .addValue("position", position)
                .addValue("createdAt", Timestamp.from(now))
                .addValue("lastOpenedAt", Timestamp.from(now)));
    }

    long nextSessionPosition(long workspaceId) {
        Long value = jdbc.queryForObject("SELECT COALESCE(MAX(position), 0) + 1 FROM sessions WHERE workspace_id = :workspaceId",
                new MapSqlParameterSource("workspaceId", workspaceId), Long.class);
        return value == null ? 1L : value;
    }

    void updateWorkspaceLastOpened(long workspaceId, Instant now) {
        jdbc.update("UPDATE workspaces SET last_opened_at = :lastOpenedAt WHERE id = :workspaceId",
                new MapSqlParameterSource().addValue("workspaceId", workspaceId).addValue("lastOpenedAt", Timestamp.from(now)));
    }

    SessionRow findSession(long sessionId) {
        return queryRequired("SELECT * FROM sessions WHERE id = :id", new MapSqlParameterSource("id", sessionId), this::mapSession, "session " + sessionId);
    }

    List<SessionRow> listSessionsByWorkspace(long workspaceId) {
        return jdbc.query("SELECT * FROM sessions WHERE workspace_id = :workspaceId ORDER BY position ASC",
                new MapSqlParameterSource("workspaceId", workspaceId), this::mapSession);
    }

    SessionRow findSessionToActivate(long workspaceId) {
        return queryOne("""
                SELECT * FROM sessions
                WHERE workspace_id = :workspaceId
                ORDER BY CASE WHEN last_opened_at IS NULL THEN 1 ELSE 0 END, last_opened_at DESC, position ASC
                LIMIT 1
                """, new MapSqlParameterSource("workspaceId", workspaceId), this::mapSession).orElse(null);
    }

    long insertSession(long workspaceId, String name, long position, Instant now, boolean reviewPanelOpen, Long selectedChangedFileId) {
        return insertAndReturnId("""
                INSERT INTO sessions (workspace_id, name, position, review_panel_open, selected_changed_file_id, created_at, last_opened_at)
                VALUES (:workspaceId, :name, :position, :reviewPanelOpen, :selectedChangedFileId, :createdAt, :lastOpenedAt)
                """, params -> params
                .addValue("workspaceId", workspaceId)
                .addValue("name", name)
                .addValue("position", position)
                .addValue("reviewPanelOpen", reviewPanelOpen)
                .addValue("selectedChangedFileId", selectedChangedFileId)
                .addValue("createdAt", Timestamp.from(now))
                .addValue("lastOpenedAt", Timestamp.from(now)));
    }

    void updateSessionLastOpened(long sessionId, Instant now) {
        jdbc.update("UPDATE sessions SET last_opened_at = :lastOpenedAt WHERE id = :sessionId",
                new MapSqlParameterSource().addValue("sessionId", sessionId).addValue("lastOpenedAt", Timestamp.from(now)));
    }

    void updateSessionReviewState(long sessionId, boolean reviewPanelOpen, Long selectedChangedFileId) {
        jdbc.update("UPDATE sessions SET review_panel_open = :reviewPanelOpen, selected_changed_file_id = :selectedChangedFileId WHERE id = :sessionId",
                new MapSqlParameterSource()
                        .addValue("sessionId", sessionId)
                        .addValue("reviewPanelOpen", reviewPanelOpen)
                        .addValue("selectedChangedFileId", selectedChangedFileId));
    }

    void updateSessionSelectedChangedFile(long sessionId, Long selectedChangedFileId) {
        jdbc.update("UPDATE sessions SET selected_changed_file_id = :selectedChangedFileId WHERE id = :sessionId",
                new MapSqlParameterSource().addValue("sessionId", sessionId).addValue("selectedChangedFileId", selectedChangedFileId));
    }

    ConversationMessageRow findMessageBySessionAndPublicId(long sessionId, String publicId) {
        return queryRequired("SELECT * FROM conversation_messages WHERE session_id = :sessionId AND public_id = :publicId",
                new MapSqlParameterSource().addValue("sessionId", sessionId).addValue("publicId", publicId), this::mapConversationMessage,
                "message " + publicId + " in session " + sessionId);
    }

    List<ConversationMessageRow> listMessagesBySession(long sessionId) {
        return jdbc.query("SELECT * FROM conversation_messages WHERE session_id = :sessionId ORDER BY sequence ASC",
                new MapSqlParameterSource("sessionId", sessionId), this::mapConversationMessage);
    }

    long nextMessageSequence(long sessionId) {
        Long value = jdbc.queryForObject("SELECT COALESCE(MAX(sequence), 0) + 1 FROM conversation_messages WHERE session_id = :sessionId",
                new MapSqlParameterSource("sessionId", sessionId), Long.class);
        return value == null ? 1L : value;
    }

    long nextTurnId(long sessionId) {
        Long value = jdbc.queryForObject("SELECT COALESCE(MAX(turn_id), 0) + 1 FROM conversation_messages WHERE session_id = :sessionId",
                new MapSqlParameterSource("sessionId", sessionId), Long.class);
        return value == null ? 1L : value;
    }

    long insertConversationMessage(long sessionId, String publicId, String role, long turnId, long sequence, String content,
                                   String toolCallId, String toolCallsJson, boolean showInChat, boolean includeInModel, boolean pending, Instant now) {
        return insertAndReturnId("""
                INSERT INTO conversation_messages
                (session_id, public_id, role, turn_id, sequence, content, tool_call_id, tool_calls_json, show_in_chat, include_in_model, pending, created_at)
                VALUES (:sessionId, :publicId, :role, :turnId, :sequence, :content, :toolCallId, :toolCallsJson, :showInChat, :includeInModel, :pending, :createdAt)
                """, params -> params
                .addValue("sessionId", sessionId)
                .addValue("publicId", publicId)
                .addValue("role", role)
                .addValue("turnId", turnId)
                .addValue("sequence", sequence)
                .addValue("content", content)
                .addValue("toolCallId", toolCallId)
                .addValue("toolCallsJson", toolCallsJson)
                .addValue("showInChat", showInChat)
                .addValue("includeInModel", includeInModel)
                .addValue("pending", pending)
                .addValue("createdAt", Timestamp.from(now)));
    }

    void updateMessageContentAndPending(long messageId, String content, boolean pending, boolean includeInModel) {
        jdbc.update("UPDATE conversation_messages SET content = :content, pending = :pending, include_in_model = :includeInModel WHERE id = :messageId",
                new MapSqlParameterSource().addValue("messageId", messageId).addValue("content", content).addValue("pending", pending).addValue("includeInModel", includeInModel));
    }

    void updateMessageToolCalls(long messageId, String toolCallsJson) {
        jdbc.update("UPDATE conversation_messages SET tool_calls_json = :toolCallsJson WHERE id = :messageId",
                new MapSqlParameterSource().addValue("messageId", messageId).addValue("toolCallsJson", toolCallsJson));
    }

    long nextToolCallTraceSequence(long sessionId) {
        Long value = jdbc.queryForObject("SELECT COALESCE(MAX(sequence), 0) + 1 FROM tool_call_traces WHERE session_id = :sessionId",
                new MapSqlParameterSource("sessionId", sessionId), Long.class);
        return value == null ? 1L : value;
    }

    long insertToolCallTrace(long sessionId, long assistantMessageId, long sequence, String toolName, boolean success, String argsJson, String textSummary, String machineSummaryJson, Instant now) {
        return insertAndReturnId("""
                INSERT INTO tool_call_traces (session_id, assistant_message_id, sequence, tool_name, success, args_json, text_summary, machine_summary_json, created_at)
                VALUES (:sessionId, :assistantMessageId, :sequence, :toolName, :success, :argsJson, :textSummary, :machineSummaryJson, :createdAt)
                """, params -> params
                .addValue("sessionId", sessionId)
                .addValue("assistantMessageId", assistantMessageId)
                .addValue("sequence", sequence)
                .addValue("toolName", toolName)
                .addValue("success", success)
                .addValue("argsJson", argsJson)
                .addValue("textSummary", textSummary)
                .addValue("machineSummaryJson", machineSummaryJson)
                .addValue("createdAt", Timestamp.from(now)));
    }

    List<ToolCallTraceRow> listToolCallTracesBySession(long sessionId) {
        return jdbc.query("SELECT * FROM tool_call_traces WHERE session_id = :sessionId ORDER BY sequence ASC",
                new MapSqlParameterSource("sessionId", sessionId), this::mapToolCallTrace);
    }

    List<ToolCallTraceRow> listToolCallTracesByAssistantMessage(long assistantMessageId) {
        return jdbc.query("SELECT * FROM tool_call_traces WHERE assistant_message_id = :assistantMessageId ORDER BY sequence ASC",
                new MapSqlParameterSource("assistantMessageId", assistantMessageId), this::mapToolCallTrace);
    }

    long nextChangedFilePosition(long sessionId) {
        Long value = jdbc.queryForObject("SELECT COALESCE(MAX(position), 0) + 1 FROM changed_files WHERE session_id = :sessionId",
                new MapSqlParameterSource("sessionId", sessionId), Long.class);
        return value == null ? 1L : value;
    }

    long insertChangedFile(long sessionId, String path, String diff, long position, Instant now) {
        return insertAndReturnId("""
                INSERT INTO changed_files (session_id, path, diff, position, created_at)
                VALUES (:sessionId, :path, :diff, :position, :createdAt)
                """, params -> params
                .addValue("sessionId", sessionId)
                .addValue("path", path)
                .addValue("diff", diff)
                .addValue("position", position)
                .addValue("createdAt", Timestamp.from(now)));
    }

    List<ChangedFileRow> listChangedFilesBySession(long sessionId) {
        return jdbc.query("SELECT * FROM changed_files WHERE session_id = :sessionId ORDER BY position DESC",
                new MapSqlParameterSource("sessionId", sessionId), this::mapChangedFile);
    }

    ChangedFileRow findChangedFile(long changedFileId) {
        return queryRequired("SELECT * FROM changed_files WHERE id = :id", new MapSqlParameterSource("id", changedFileId), this::mapChangedFile, "changed file " + changedFileId);
    }

    List<ConversationMessageRow> listVisibleMessagesBySession(long sessionId) {
        return jdbc.query("SELECT * FROM conversation_messages WHERE session_id = :sessionId AND show_in_chat = TRUE ORDER BY sequence ASC",
                new MapSqlParameterSource("sessionId", sessionId), this::mapConversationMessage);
    }

    private <T> Optional<T> queryOne(String sql, MapSqlParameterSource params, RowMapper<T> mapper) {
        List<T> rows = jdbc.query(sql, params, mapper);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    private <T> T queryRequired(String sql, MapSqlParameterSource params, RowMapper<T> mapper, String label) {
        return queryOne(sql, params, mapper).orElseThrow(() -> new IllegalStateException("Missing " + label));
    }

    private long insertAndReturnId(String sql, java.util.function.UnaryOperator<MapSqlParameterSource> paramsFn) {
        MapSqlParameterSource params = paramsFn.apply(new MapSqlParameterSource());
        KeyHolder keyHolder = new GeneratedKeyHolder();
        int updated = jdbc.update(sql, params, keyHolder, new String[] {"id"});
        if (updated != 1) {
            throw new IllegalStateException("Expected one row to be inserted");
        }
        Number key = (Number) keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("No generated id returned");
        }
        return key.longValue();
    }

    private ProjectRow mapProject(ResultSet rs, int rowNum) throws SQLException {
        return new ProjectRow(rs.getLong("id"), rs.getString("name"), rs.getString("normalized_path"), rs.getLong("display_order"),
                timestampToInstant(rs.getTimestamp("closed_at")), timestampToInstant(rs.getTimestamp("created_at")), timestampToInstant(rs.getTimestamp("last_opened_at")));
    }

    private WorkspaceRow mapWorkspace(ResultSet rs, int rowNum) throws SQLException {
        return new WorkspaceRow(rs.getLong("id"), rs.getLong("project_id"), rs.getString("name"), rs.getString("normalized_path"), rs.getLong("position"),
                timestampToInstant(rs.getTimestamp("created_at")), timestampToInstant(rs.getTimestamp("last_opened_at")));
    }

    private SessionRow mapSession(ResultSet rs, int rowNum) throws SQLException {
        Long selectedChangedFileId = rs.getObject("selected_changed_file_id", Long.class);
        return new SessionRow(rs.getLong("id"), rs.getLong("workspace_id"), rs.getString("name"), rs.getLong("position"), rs.getBoolean("review_panel_open"),
                selectedChangedFileId, timestampToInstant(rs.getTimestamp("created_at")), timestampToInstant(rs.getTimestamp("last_opened_at")));
    }

    private ConversationMessageRow mapConversationMessage(ResultSet rs, int rowNum) throws SQLException {
        return new ConversationMessageRow(rs.getLong("id"), rs.getLong("session_id"), rs.getString("public_id"), rs.getString("role"), rs.getLong("turn_id"),
                rs.getLong("sequence"), rs.getString("content"), rs.getString("tool_call_id"), rs.getString("tool_calls_json"), rs.getBoolean("show_in_chat"),
                rs.getBoolean("include_in_model"), rs.getBoolean("pending"), timestampToInstant(rs.getTimestamp("created_at")));
    }

    private ToolCallTraceRow mapToolCallTrace(ResultSet rs, int rowNum) throws SQLException {
        return new ToolCallTraceRow(rs.getLong("id"), rs.getLong("session_id"), rs.getLong("assistant_message_id"), rs.getLong("sequence"), rs.getString("tool_name"),
                rs.getBoolean("success"), rs.getString("args_json"), rs.getString("text_summary"), rs.getString("machine_summary_json"), timestampToInstant(rs.getTimestamp("created_at")));
    }

    private ChangedFileRow mapChangedFile(ResultSet rs, int rowNum) throws SQLException {
        return new ChangedFileRow(rs.getLong("id"), rs.getLong("session_id"), rs.getString("path"), rs.getString("diff"), rs.getLong("position"), timestampToInstant(rs.getTimestamp("created_at")));
    }

    private static Instant timestampToInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    record AppStateRow(Long activeProjectId, Long activeWorkspaceId, Long activeSessionId) {}
    record ProjectRow(long id, String name, String normalizedPath, long displayOrder, Instant closedAt, Instant createdAt, Instant lastOpenedAt) {}
    record WorkspaceRow(long id, long projectId, String name, String normalizedPath, long position, Instant createdAt, Instant lastOpenedAt) {}
    record SessionRow(long id, long workspaceId, String name, long position, boolean reviewPanelOpen, Long selectedChangedFileId, Instant createdAt, Instant lastOpenedAt) {}
    record ConversationMessageRow(long id, long sessionId, String publicId, String role, long turnId, long sequence, String content, String toolCallId, String toolCallsJson, boolean showInChat, boolean includeInModel, boolean pending, Instant createdAt) {}
    record ToolCallTraceRow(long id, long sessionId, long assistantMessageId, long sequence, String toolName, boolean success, String argsJson, String textSummary, String machineSummaryJson, Instant createdAt) {}
    record ChangedFileRow(long id, long sessionId, String path, String diff, long position, Instant createdAt) {}
}
