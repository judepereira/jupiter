package com.judepereira.jupiter.persistence;

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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class AppStateRepository {

    private final NamedParameterJdbcTemplate jdbc;

    AppStateRow loadAppState() {
        return jdbc.queryForObject("SELECT active_project_id, active_workspace_id, active_session_id FROM app_state WHERE id = 1", new MapSqlParameterSource(), (rs, rowNum) -> new AppStateRow(
                nullableLong(rs, "active_project_id"),
                nullableLong(rs, "active_workspace_id"),
                nullableLong(rs, "active_session_id")
        ));
    }

    AppStateLifecycleHookSettingsRow loadLifecycleHookSettings() {
        return jdbc.queryForObject("""
                SELECT assistant_completed_hook_script, assistant_errored_hook_script,
                       subagent_completed_hook_script, lifecycle_hook_timeout_seconds
                FROM app_state WHERE id = 1
                """, new MapSqlParameterSource(), (rs, rowNum) -> new AppStateLifecycleHookSettingsRow(
                rs.getString("assistant_completed_hook_script"),
                rs.getString("assistant_errored_hook_script"),
                rs.getString("subagent_completed_hook_script"),
                nullableInteger(rs, "lifecycle_hook_timeout_seconds")));
    }

    void updateLifecycleHookSettings(String assistantCompletedScript, String assistantErroredScript,
                                     String subagentCompletedScript, int timeoutSeconds) {
        jdbc.update("""
                UPDATE app_state SET assistant_completed_hook_script = :assistantCompletedScript,
                    assistant_errored_hook_script = :assistantErroredScript,
                    subagent_completed_hook_script = :subagentCompletedScript,
                    lifecycle_hook_timeout_seconds = :timeoutSeconds
                WHERE id = 1
                """, new MapSqlParameterSource()
                .addValue("assistantCompletedScript", assistantCompletedScript)
                .addValue("assistantErroredScript", assistantErroredScript)
                .addValue("subagentCompletedScript", subagentCompletedScript)
                .addValue("timeoutSeconds", timeoutSeconds));
    }

    void insertTokenUsageFact(Persistence.TokenUsageFact fact, String providerMetadataJson) {
        jdbc.update("""
                INSERT INTO token_usage_facts (session_usage_key, session_id_snapshot, workspace_id_snapshot, project_id_snapshot,
                    session_name_snapshot, workspace_name_snapshot, project_name_snapshot, workspace_path_snapshot, project_path_snapshot,
                    occurred_at, hour_start_utc, model_key, operation, input_token_count, output_token_count, total_token_count,
                    cached_input_token_count, cache_write_token_count, reasoning_token_count, response_id, response_model_id, finish_reason,
                    provider_metadata_json)
                VALUES (:sessionUsageKey, :sessionId, :workspaceId, :projectId, :sessionName, :workspaceName, :projectName,
                    :workspacePath, :projectPath, :occurredAt, :hourStart, :modelKey, :operation, :inputTokens, :outputTokens, :totalTokens,
                    :cachedTokens, :cacheWriteTokens, :reasoningTokens, :responseId, :responseModelId, :finishReason, :providerMetadataJson)
                """, usageParams(fact, providerMetadataJson));
    }

    void upsertTokenUsageHourly(Persistence.TokenUsageFact fact) {
        jdbc.update("""
                INSERT INTO token_usage_hourly (session_usage_key, session_id_snapshot, workspace_id_snapshot, project_id_snapshot,
                    session_name_snapshot, workspace_name_snapshot, project_name_snapshot, workspace_path_snapshot, project_path_snapshot,
                    hour_start_utc, model_key, request_count, input_token_count, output_token_count, total_token_count,
                    cached_input_token_count, cache_write_token_count, reasoning_token_count, last_occurred_at)
                VALUES (:sessionUsageKey, :sessionId, :workspaceId, :projectId, :sessionName, :workspaceName, :projectName,
                    :workspacePath, :projectPath, :hourStart, :modelKey, 1, :inputTokens, :outputTokens, :totalTokens,
                    :cachedTokens, :cacheWriteTokens, :reasoningTokens, :occurredAt)
                ON CONFLICT (session_usage_key, hour_start_utc, model_key) DO UPDATE SET
                    request_count = request_count + 1,
                    input_token_count = CASE WHEN excluded.input_token_count IS NULL THEN input_token_count ELSE COALESCE(input_token_count, 0) + excluded.input_token_count END,
                    output_token_count = CASE WHEN excluded.output_token_count IS NULL THEN output_token_count ELSE COALESCE(output_token_count, 0) + excluded.output_token_count END,
                    total_token_count = CASE WHEN excluded.total_token_count IS NULL THEN total_token_count ELSE COALESCE(total_token_count, 0) + excluded.total_token_count END,
                    cached_input_token_count = CASE WHEN excluded.cached_input_token_count IS NULL THEN cached_input_token_count ELSE COALESCE(cached_input_token_count, 0) + excluded.cached_input_token_count END,
                    cache_write_token_count = CASE WHEN excluded.cache_write_token_count IS NULL THEN cache_write_token_count ELSE COALESCE(cache_write_token_count, 0) + excluded.cache_write_token_count END,
                    reasoning_token_count = CASE WHEN excluded.reasoning_token_count IS NULL THEN reasoning_token_count ELSE COALESCE(reasoning_token_count, 0) + excluded.reasoning_token_count END,
                    last_occurred_at = MAX(last_occurred_at, excluded.last_occurred_at)
                """, usageParams(fact, null));
    }

    int deleteTokenUsageFactsBefore(Instant cutoff) {
        return jdbc.update("DELETE FROM token_usage_facts WHERE occurred_at < :cutoff",
                new MapSqlParameterSource("cutoff", Timestamp.from(cutoff)));
    }

    int deleteTokenUsageHourlyBefore(Instant cutoff) {
        return jdbc.update("DELETE FROM token_usage_hourly WHERE hour_start_utc < :cutoff",
                new MapSqlParameterSource("cutoff", Timestamp.from(cutoff)));
    }

    void rebuildTokenUsageHourlyAt(Instant cutoff, Instant hourStart) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("cutoff", Timestamp.from(cutoff))
                .addValue("hourStart", Timestamp.from(hourStart));
        jdbc.update("DELETE FROM token_usage_hourly WHERE hour_start_utc = :hourStart", params);
        jdbc.update("""
                INSERT INTO token_usage_hourly (session_usage_key, session_id_snapshot, workspace_id_snapshot, project_id_snapshot,
                    session_name_snapshot, workspace_name_snapshot, project_name_snapshot, workspace_path_snapshot, project_path_snapshot,
                    hour_start_utc, model_key, request_count, input_token_count, output_token_count, total_token_count,
                    cached_input_token_count, cache_write_token_count, reasoning_token_count, last_occurred_at)
                SELECT session_usage_key, MIN(session_id_snapshot), MIN(workspace_id_snapshot), MIN(project_id_snapshot),
                       MIN(session_name_snapshot), MIN(workspace_name_snapshot), MIN(project_name_snapshot), MIN(workspace_path_snapshot), MIN(project_path_snapshot),
                       hour_start_utc, model_key, COUNT(*), SUM(input_token_count), SUM(output_token_count), SUM(total_token_count),
                       SUM(cached_input_token_count), SUM(cache_write_token_count), SUM(reasoning_token_count), MAX(occurred_at)
                FROM token_usage_facts
                WHERE hour_start_utc = :hourStart AND occurred_at >= :cutoff
                GROUP BY session_usage_key, hour_start_utc, model_key
                """, params);
    }
    List<Persistence.ProjectTokenUsageHourly> findProjectHourlyTokenUsage(long projectId, Instant fromInclusive, Instant toExclusive) {
        return jdbc.query("""
                SELECT hour_start_utc, model_key, SUM(request_count) AS request_count,
                       SUM(input_token_count) AS input_token_count, SUM(output_token_count) AS output_token_count,
                       SUM(total_token_count) AS total_token_count
                FROM token_usage_hourly
                WHERE project_id_snapshot = :projectId
                  AND hour_start_utc >= :fromInclusive
                  AND hour_start_utc < :toExclusive
                GROUP BY hour_start_utc, model_key
                ORDER BY hour_start_utc ASC, model_key ASC
                """, new MapSqlParameterSource()
                        .addValue("projectId", projectId)
                        .addValue("fromInclusive", Timestamp.from(fromInclusive))
                        .addValue("toExclusive", Timestamp.from(toExclusive)), (rs, rowNum) ->
                new Persistence.ProjectTokenUsageHourly(
                        timestampToInstant(rs.getTimestamp("hour_start_utc")), rs.getString("model_key"),
                        rs.getLong("request_count"), nullableLong(rs, "input_token_count"),
                        nullableLong(rs, "output_token_count"), nullableLong(rs, "total_token_count")));
    }

    List<Persistence.TokenUsageHourly> findHourlyTokenUsage(String sessionUsageKey, Instant fromInclusive, Instant toExclusive) {
        return jdbc.query("""
                SELECT session_usage_key, hour_start_utc, model_key, request_count,
                       input_token_count, output_token_count, total_token_count, cached_input_token_count,
                       cache_write_token_count, reasoning_token_count, last_occurred_at
                FROM token_usage_hourly
                WHERE session_usage_key = :sessionUsageKey
                  AND hour_start_utc >= :fromInclusive
                  AND hour_start_utc < :toExclusive
                ORDER BY hour_start_utc ASC, model_key ASC
                """, new MapSqlParameterSource()
                        .addValue("sessionUsageKey", sessionUsageKey)
                        .addValue("fromInclusive", Timestamp.from(fromInclusive))
                        .addValue("toExclusive", Timestamp.from(toExclusive)), (rs, rowNum) ->
                new Persistence.TokenUsageHourly(
                        rs.getString("session_usage_key"), timestampToInstant(rs.getTimestamp("hour_start_utc")),
                        rs.getString("model_key"), rs.getLong("request_count"),
                        nullableLong(rs, "input_token_count"), nullableLong(rs, "output_token_count"), nullableLong(rs, "total_token_count"),
                        nullableLong(rs, "cached_input_token_count"), nullableLong(rs, "cache_write_token_count"),
                        nullableLong(rs, "reasoning_token_count"), timestampToInstant(rs.getTimestamp("last_occurred_at"))));
    }

    List<Persistence.TokenUsageFact> findTokenUsageFacts(String sessionUsageKey) {
        return jdbc.query("""
                SELECT session_usage_key, session_id_snapshot, workspace_id_snapshot, project_id_snapshot,
                       session_name_snapshot, workspace_name_snapshot, project_name_snapshot,
                       workspace_path_snapshot, project_path_snapshot, occurred_at, hour_start_utc,
                       model_key, operation, input_token_count, output_token_count, total_token_count,
                       cached_input_token_count, cache_write_token_count, reasoning_token_count,
                       response_id, response_model_id, finish_reason
                FROM token_usage_facts
                WHERE session_usage_key = :sessionUsageKey
                ORDER BY occurred_at ASC, id ASC
                """, new MapSqlParameterSource("sessionUsageKey", sessionUsageKey), (rs, rowNum) ->
                new Persistence.TokenUsageFact(
                        rs.getString("session_usage_key"), rs.getLong("session_id_snapshot"),
                        rs.getLong("workspace_id_snapshot"), rs.getLong("project_id_snapshot"),
                        rs.getString("session_name_snapshot"), rs.getString("workspace_name_snapshot"),
                        rs.getString("project_name_snapshot"), rs.getString("workspace_path_snapshot"),
                        rs.getString("project_path_snapshot"), timestampToInstant(rs.getTimestamp("occurred_at")),
                        timestampToInstant(rs.getTimestamp("hour_start_utc")), rs.getString("model_key"),
                        rs.getString("operation"), nullableInteger(rs, "input_token_count"),
                        nullableInteger(rs, "output_token_count"), nullableInteger(rs, "total_token_count"),
                        nullableInteger(rs, "cached_input_token_count"), nullableInteger(rs, "cache_write_token_count"),
                        nullableInteger(rs, "reasoning_token_count"), rs.getString("response_id"),
                        rs.getString("response_model_id"), rs.getString("finish_reason"), Map.of()));
    }

    private MapSqlParameterSource usageParams(Persistence.TokenUsageFact fact, String providerMetadataJson) {
        return new MapSqlParameterSource()
                .addValue("sessionUsageKey", fact.sessionUsageKey()).addValue("sessionId", fact.sessionIdSnapshot())
                .addValue("workspaceId", fact.workspaceIdSnapshot()).addValue("projectId", fact.projectIdSnapshot())
                .addValue("sessionName", fact.sessionNameSnapshot()).addValue("workspaceName", fact.workspaceNameSnapshot())
                .addValue("projectName", fact.projectNameSnapshot()).addValue("workspacePath", fact.workspacePathSnapshot())
                .addValue("projectPath", fact.projectPathSnapshot()).addValue("occurredAt", Timestamp.from(fact.occurredAt()))
                .addValue("hourStart", Timestamp.from(fact.hourStartUtc())).addValue("modelKey", fact.modelKey())
                .addValue("operation", fact.operation()).addValue("inputTokens", fact.inputTokenCount())
                .addValue("outputTokens", fact.outputTokenCount()).addValue("totalTokens", fact.totalTokenCount())
                .addValue("cachedTokens", fact.cachedInputTokenCount()).addValue("cacheWriteTokens", fact.cacheWriteTokenCount())
                .addValue("reasoningTokens", fact.reasoningTokenCount()).addValue("responseId", fact.responseId())
                .addValue("responseModelId", fact.responseModelId()).addValue("finishReason", fact.finishReason()).addValue("providerMetadataJson", providerMetadataJson == null ? "{}" : providerMetadataJson);
    }

    public Optional<OpenAiOAuthStateRow> loadOpenAiOAuthState() {
        return queryOne("SELECT openai_access_token, openai_refresh_token, openai_id_token, openai_account_id, openai_expires_at FROM app_state WHERE id = 1",
                new MapSqlParameterSource(), this::mapOpenAiOAuthState);
    }

    public void updateOpenAiOAuthState(String accessToken, String refreshToken, String idToken, String accountId, Instant expiresAt) {
        jdbc.update("UPDATE app_state SET openai_access_token = :accessToken, openai_refresh_token = :refreshToken, openai_id_token = :idToken, openai_account_id = :accountId, openai_expires_at = :expiresAt WHERE id = 1",
                new MapSqlParameterSource()
                        .addValue("accessToken", accessToken)
                        .addValue("refreshToken", refreshToken)
                        .addValue("idToken", idToken)
                        .addValue("accountId", accountId)
                        .addValue("expiresAt", Timestamp.from(expiresAt)));
    }

    public void clearOpenAiOAuthState() {
        jdbc.update("UPDATE app_state SET openai_access_token = NULL, openai_refresh_token = NULL, openai_id_token = NULL, openai_account_id = NULL, openai_expires_at = NULL WHERE id = 1",
                new MapSqlParameterSource());
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

    void updateProjectWorkspaceInitCommands(long projectId, String workspaceInitCommands) {
        jdbc.update("UPDATE projects SET workspace_init_commands = :workspaceInitCommands WHERE id = :projectId",
                new MapSqlParameterSource().addValue("projectId", projectId).addValue("workspaceInitCommands", workspaceInitCommands));
    }

    void updateProjectEnvironmentVariables(long projectId, String environmentVariablesJson) {
        jdbc.update("UPDATE projects SET environment_variables = :environmentVariables WHERE id = :projectId",
                new MapSqlParameterSource().addValue("projectId", projectId).addValue("environmentVariables", environmentVariablesJson));
    }

    long insertMcpServer(String name, String url, boolean enabled, String headersJson, Instant now) {
        return insertAndReturnId("""
                INSERT INTO mcp_servers (name, url, enabled, headers_json, created_at)
                VALUES (:name, :url, :enabled, :headersJson, :createdAt)
                """, params -> params
                .addValue("name", name)
                .addValue("url", url)
                .addValue("enabled", enabled)
                .addValue("headersJson", headersJson)
                .addValue("createdAt", Timestamp.from(now)));
    }

    void updateMcpServer(long mcpServerId, String name, String url, boolean enabled, String headersJson) {
        jdbc.update("UPDATE mcp_servers SET name = :name, url = :url, enabled = :enabled, headers_json = :headersJson WHERE id = :mcpServerId",
                new MapSqlParameterSource()
                        .addValue("mcpServerId", mcpServerId)
                        .addValue("name", name)
                        .addValue("url", url)
                        .addValue("enabled", enabled)
                        .addValue("headersJson", headersJson));
    }

    void deleteMcpServer(long mcpServerId) {
        jdbc.update("DELETE FROM project_mcp_servers WHERE mcp_server_id = :mcpServerId",
                new MapSqlParameterSource("mcpServerId", mcpServerId));
        jdbc.update("DELETE FROM mcp_servers WHERE id = :mcpServerId",
                new MapSqlParameterSource("mcpServerId", mcpServerId));
    }

    void replaceMcpServerProjectExposures(long mcpServerId, List<Long> projectIds) {
        jdbc.update("DELETE FROM project_mcp_servers WHERE mcp_server_id = :mcpServerId",
                new MapSqlParameterSource("mcpServerId", mcpServerId));
        if (projectIds == null || projectIds.isEmpty()) {
            return;
        }
        for (Long projectId : projectIds) {
            jdbc.update("INSERT INTO project_mcp_servers (mcp_server_id, project_id) VALUES (:mcpServerId, :projectId)",
                    new MapSqlParameterSource()
                            .addValue("mcpServerId", mcpServerId)
                            .addValue("projectId", projectId));
        }
    }

    Optional<McpServerRow> findMcpServer(long mcpServerId) {
        return queryOne("SELECT * FROM mcp_servers WHERE id = :mcpServerId",
                new MapSqlParameterSource("mcpServerId", mcpServerId), this::mapMcpServer)
                .map(this::attachMcpServerProjectIds);
    }

    List<McpServerRow> listMcpServers() {
        return attachMcpServerProjectIds(jdbc.query("SELECT * FROM mcp_servers ORDER BY name ASC, id ASC",
                new MapSqlParameterSource(), this::mapMcpServer));
    }

    List<McpServerRow> listEnabledMcpServersByProject(long projectId) {
        return attachMcpServerProjectIds(jdbc.query("""
                SELECT ms.*
                FROM mcp_servers ms
                JOIN project_mcp_servers pms ON pms.mcp_server_id = ms.id
                WHERE pms.project_id = :projectId AND ms.enabled = TRUE
                ORDER BY ms.name ASC, ms.id ASC
                """, new MapSqlParameterSource("projectId", projectId), this::mapMcpServer));
    }

    private McpServerRow attachMcpServerProjectIds(McpServerRow row) {
        return attachMcpServerProjectIds(List.of(row)).getFirst();
    }

    private List<McpServerRow> attachMcpServerProjectIds(List<McpServerRow> rows) {
        if (rows.isEmpty()) {
            return rows;
        }
        var ids = rows.stream().map(McpServerRow::id).toList();
        Map<Long, List<Long>> projectIdsByMcpServerId = new LinkedHashMap<>();
        jdbc.query("""
                SELECT mcp_server_id, project_id
                FROM project_mcp_servers
                WHERE mcp_server_id IN (:mcpServerIds)
                ORDER BY project_id ASC, mcp_server_id ASC
                """, new MapSqlParameterSource("mcpServerIds", ids), (java.sql.ResultSet rs) -> {
            while (rs.next()) {
                long mcpServerId = rs.getLong("mcp_server_id");
                projectIdsByMcpServerId.computeIfAbsent(mcpServerId, ignored -> new ArrayList<>()).add(rs.getLong("project_id"));
            }
            return null;
        });
        return rows.stream()
                .map(row -> new McpServerRow(row.id(), row.name(), row.url(), row.enabled(), row.headersJson(), row.createdAt(),
                        List.copyOf(projectIdsByMcpServerId.getOrDefault(row.id(), List.of()))))
                .toList();
    }

    WorkspaceRow findWorkspace(long workspaceId) {
        return queryRequired("""
                SELECT w.*, 
                       EXISTS(
                           SELECT 1
                           FROM sessions s
                           WHERE s.workspace_id = w.id AND s.hidden = FALSE AND s.unread = TRUE
                       ) AS unread,
                       EXISTS(
                           SELECT 1
                           FROM sessions s
                           JOIN conversation_messages m ON m.session_id = s.id
                           WHERE s.workspace_id = w.id AND s.hidden = FALSE AND m.role = 'assistant' AND m.show_in_chat = TRUE AND m.sequence = (SELECT MAX(m2.sequence) FROM conversation_messages m2 WHERE m2.session_id = s.id AND m2.role = 'assistant' AND m2.show_in_chat = TRUE) AND m.pending = TRUE
                       ) AS in_progress
                FROM workspaces w
                WHERE w.id = :id
                """, new MapSqlParameterSource("id", workspaceId), this::mapWorkspace, "workspace " + workspaceId);
    }

    List<WorkspaceRow> listWorkspacesByProject(long projectId) {
        return jdbc.query("""
                SELECT w.*, 
                       EXISTS(
                           SELECT 1
                           FROM sessions s
                           WHERE s.workspace_id = w.id AND s.hidden = FALSE AND s.unread = TRUE
                       ) AS unread,
                       EXISTS(
                           SELECT 1
                           FROM sessions s
                           JOIN conversation_messages m ON m.session_id = s.id
                           WHERE s.workspace_id = w.id AND s.hidden = FALSE AND m.role = 'assistant' AND m.show_in_chat = TRUE AND m.sequence = (SELECT MAX(m2.sequence) FROM conversation_messages m2 WHERE m2.session_id = s.id AND m2.role = 'assistant' AND m2.show_in_chat = TRUE) AND m.pending = TRUE
                       ) AS in_progress
                FROM workspaces w
                WHERE w.project_id = :projectId
                ORDER BY w.position ASC
                """, new MapSqlParameterSource("projectId", projectId), this::mapWorkspace);
    }

    WorkspaceRow findWorkspaceToActivate(long projectId) {
        return queryOne("""
                SELECT w.*, 
                       EXISTS(
                           SELECT 1
                           FROM sessions s
                           WHERE s.workspace_id = w.id AND s.hidden = FALSE AND s.unread = TRUE
                       ) AS unread,
                       EXISTS(
                           SELECT 1
                           FROM sessions s
                           JOIN conversation_messages m ON m.session_id = s.id
                           WHERE s.workspace_id = w.id AND s.hidden = FALSE AND m.role = 'assistant' AND m.show_in_chat = TRUE AND m.sequence = (SELECT MAX(m2.sequence) FROM conversation_messages m2 WHERE m2.session_id = s.id AND m2.role = 'assistant' AND m2.show_in_chat = TRUE) AND m.pending = TRUE
                       ) AS in_progress
                FROM workspaces w
                WHERE w.project_id = :projectId
                ORDER BY CASE WHEN w.last_opened_at IS NULL THEN 1 ELSE 0 END, w.last_opened_at DESC, w.position ASC
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

    Optional<SessionUsageContext> findSessionUsageContext(long sessionId) {
        return queryOne("""
                SELECT s.session_usage_key, s.id AS session_id, s.name AS session_name,
                       w.id AS workspace_id, w.name AS workspace_name, w.normalized_path AS workspace_path,
                       p.id AS project_id, p.name AS project_name, p.normalized_path AS project_path
                FROM sessions s
                JOIN workspaces w ON w.id = s.workspace_id
                JOIN projects p ON p.id = w.project_id
                WHERE s.id = :id
                """, new MapSqlParameterSource("id", sessionId), (rs, rowNum) -> new SessionUsageContext(
                rs.getString("session_usage_key"), rs.getLong("session_id"), rs.getLong("workspace_id"), rs.getLong("project_id"),
                rs.getString("session_name"), rs.getString("workspace_name"), rs.getString("project_name"),
                rs.getString("workspace_path"), rs.getString("project_path")));
    }

    Optional<LifecycleHookContextRow> findLifecycleHookContext(long sessionId) {
        return queryOne("""
                SELECT s.id AS session_id, s.name AS session_name, w.name AS workspace_name,
                       p.name AS project_name, p.environment_variables
                FROM sessions s
                JOIN workspaces w ON w.id = s.workspace_id
                JOIN projects p ON p.id = w.project_id
                WHERE s.id = :id
                """, new MapSqlParameterSource("id", sessionId), (rs, rowNum) -> new LifecycleHookContextRow(
                rs.getLong("session_id"), rs.getString("project_name"), rs.getString("workspace_name"),
                rs.getString("session_name"), rs.getString("environment_variables")));
    }

    SessionRow findSession(long sessionId) {
        return queryRequired("""
                SELECT s.*,
                       EXISTS(
                           SELECT 1
                           FROM conversation_messages m
                           WHERE m.session_id = s.id AND m.role = 'assistant' AND m.show_in_chat = TRUE AND m.sequence = (SELECT MAX(m2.sequence) FROM conversation_messages m2 WHERE m2.session_id = s.id AND m2.role = 'assistant' AND m2.show_in_chat = TRUE) AND m.pending = TRUE
                       ) AS in_progress
                FROM sessions s
                WHERE s.id = :id
                """, new MapSqlParameterSource("id", sessionId), this::mapSession, "session " + sessionId);
    }

    void updateSessionDraft(long sessionId, String draft) {
        jdbc.update("UPDATE sessions SET chat_draft = :draft WHERE id = :sessionId",
                new MapSqlParameterSource().addValue("sessionId", sessionId).addValue("draft", draft == null ? "" : draft));
    }

    void clearSessionDraft(long sessionId) {
        updateSessionDraft(sessionId, "");
    }

    Optional<ConversationMessageRow> findLatestAssistantMessage(long sessionId) {
        return queryOne("""
                SELECT *
                FROM conversation_messages
                WHERE session_id = :sessionId AND role = 'assistant'
                ORDER BY sequence DESC
                LIMIT 1
                """, new MapSqlParameterSource("sessionId", sessionId), this::mapConversationMessage);
    }

    Optional<ConversationMessageRow> findLatestVisibleAssistantMessage(long sessionId) {
        return queryOne("""
                SELECT *
                FROM conversation_messages
                WHERE session_id = :sessionId AND role = 'assistant' AND show_in_chat = TRUE
                ORDER BY sequence DESC
                LIMIT 1
                """, new MapSqlParameterSource("sessionId", sessionId), this::mapConversationMessage);
    }

    Optional<ConversationMessageRow> findLatestPendingVisibleAssistantMessage(long sessionId) {
        return queryOne("""
                SELECT *
                FROM conversation_messages
                WHERE session_id = :sessionId AND role = 'assistant' AND show_in_chat = TRUE AND pending = TRUE
                ORDER BY sequence DESC
                LIMIT 1
                """, new MapSqlParameterSource("sessionId", sessionId), this::mapConversationMessage);
    }

    List<SessionRow> listSessionsByWorkspace(long workspaceId) {
        return jdbc.query("""
                SELECT s.*,
                       EXISTS(
                           SELECT 1
                           FROM conversation_messages m
                           WHERE m.session_id = s.id AND m.role = 'assistant' AND m.show_in_chat = TRUE AND m.sequence = (SELECT MAX(m2.sequence) FROM conversation_messages m2 WHERE m2.session_id = s.id AND m2.role = 'assistant' AND m2.show_in_chat = TRUE) AND m.pending = TRUE
                       ) AS in_progress
                FROM sessions s
                WHERE s.workspace_id = :workspaceId AND s.hidden = FALSE
                ORDER BY s.position ASC
                """, new MapSqlParameterSource("workspaceId", workspaceId), this::mapSession);
    }

    Set<Long> listUnreadWorkspaceIds(long projectId) {
        return Set.copyOf(jdbc.queryForList("""
                SELECT DISTINCT s.workspace_id
                FROM sessions s
                JOIN workspaces w ON w.id = s.workspace_id
                WHERE w.project_id = :projectId AND s.hidden = FALSE AND s.unread = TRUE
                """, new MapSqlParameterSource("projectId", projectId), Long.class));
    }

    Set<Long> listPendingWorkspaceIds(long projectId) {
        return Set.copyOf(jdbc.queryForList("""
                SELECT DISTINCT w.id
                FROM workspaces w
                JOIN sessions s ON s.workspace_id = w.id
                JOIN conversation_messages m ON m.session_id = s.id
                WHERE w.project_id = :projectId AND s.hidden = FALSE AND m.role = 'assistant' AND m.show_in_chat = TRUE AND m.sequence = (SELECT MAX(m2.sequence) FROM conversation_messages m2 WHERE m2.session_id = s.id AND m2.role = 'assistant' AND m2.show_in_chat = TRUE) AND m.pending = TRUE
                """, new MapSqlParameterSource("projectId", projectId), Long.class));
    }

    Set<Long> listPendingSessionIds(long workspaceId) {
        return Set.copyOf(jdbc.queryForList("""
                SELECT DISTINCT s.id
                FROM sessions s
                JOIN conversation_messages m ON m.session_id = s.id
                WHERE s.workspace_id = :workspaceId AND s.hidden = FALSE AND m.role = 'assistant' AND m.show_in_chat = TRUE AND m.sequence = (SELECT MAX(m2.sequence) FROM conversation_messages m2 WHERE m2.session_id = s.id AND m2.role = 'assistant' AND m2.show_in_chat = TRUE) AND m.pending = TRUE
                """, new MapSqlParameterSource("workspaceId", workspaceId), Long.class));
    }

    List<SessionRow> listChildSessionsByParentSession(long parentSessionId) {
        return jdbc.query("""
                SELECT s.*,
                       EXISTS(
                           SELECT 1
                           FROM conversation_messages m
                           WHERE m.session_id = s.id AND m.role = 'assistant' AND m.show_in_chat = TRUE AND m.sequence = (SELECT MAX(m2.sequence) FROM conversation_messages m2 WHERE m2.session_id = s.id AND m2.role = 'assistant' AND m2.show_in_chat = TRUE) AND m.pending = TRUE
                       ) AS in_progress
                FROM sessions s
                WHERE s.parent_session_id = :parentSessionId
                ORDER BY s.position ASC
                """, new MapSqlParameterSource("parentSessionId", parentSessionId), this::mapSession);
    }

    SessionRow findNextSessionAfter(long workspaceId, long position) {
        return queryOne("""
                SELECT s.*,
                       EXISTS(
                           SELECT 1
                           FROM conversation_messages m
                           WHERE m.session_id = s.id AND m.role = 'assistant' AND m.show_in_chat = TRUE AND m.sequence = (SELECT MAX(m2.sequence) FROM conversation_messages m2 WHERE m2.session_id = s.id AND m2.role = 'assistant' AND m2.show_in_chat = TRUE) AND m.pending = TRUE
                       ) AS in_progress
                FROM sessions s
                WHERE s.workspace_id = :workspaceId AND s.hidden = FALSE AND s.position > :position
                ORDER BY s.position ASC
                LIMIT 1
                """, new MapSqlParameterSource().addValue("workspaceId", workspaceId).addValue("position", position), this::mapSession).orElse(null);
    }

    SessionRow findPreviousSessionBefore(long workspaceId, long position) {
        return queryOne("""
                SELECT s.*,
                       EXISTS(
                           SELECT 1
                           FROM conversation_messages m
                           WHERE m.session_id = s.id AND m.role = 'assistant' AND m.show_in_chat = TRUE AND m.sequence = (SELECT MAX(m2.sequence) FROM conversation_messages m2 WHERE m2.session_id = s.id AND m2.role = 'assistant' AND m2.show_in_chat = TRUE) AND m.pending = TRUE
                       ) AS in_progress
                FROM sessions s
                WHERE s.workspace_id = :workspaceId AND s.hidden = FALSE AND s.position < :position
                ORDER BY s.position DESC
                LIMIT 1
                """, new MapSqlParameterSource().addValue("workspaceId", workspaceId).addValue("position", position), this::mapSession).orElse(null);
    }

    SessionRow findSessionToActivate(long workspaceId) {
        return queryOne("""
                SELECT s.*,
                       EXISTS(
                           SELECT 1
                           FROM conversation_messages m
                           WHERE m.session_id = s.id AND m.role = 'assistant' AND m.show_in_chat = TRUE AND m.sequence = (SELECT MAX(m2.sequence) FROM conversation_messages m2 WHERE m2.session_id = s.id AND m2.role = 'assistant' AND m2.show_in_chat = TRUE) AND m.pending = TRUE
                       ) AS in_progress
                FROM sessions s
                WHERE s.workspace_id = :workspaceId AND s.hidden = FALSE
                ORDER BY CASE WHEN s.last_opened_at IS NULL THEN 1 ELSE 0 END, s.last_opened_at DESC, s.position ASC
                LIMIT 1
                """, new MapSqlParameterSource("workspaceId", workspaceId), this::mapSession).orElse(null);
    }

    long insertSession(long workspaceId, String name, long position, Instant now, boolean reviewPanelOpen, Persistence.ReviewSource reviewSource,
                       Long selectedChangedFileId) {
        return insertSession(workspaceId, name, position, now, reviewPanelOpen, reviewSource, selectedChangedFileId, false, null, null, null, null, null);
    }

    long insertSession(long workspaceId, String name, long position, Instant now, boolean reviewPanelOpen, Persistence.ReviewSource reviewSource,
                       Long selectedChangedFileId, boolean hidden, Long parentSessionId, String parentToolCallId, String subagentAgentId, String subagentAgentName,
                       Long parentAssistantMessageId) {
        return insertAndReturnId("""
                INSERT INTO sessions (workspace_id, name, position, review_panel_open, review_source, selected_changed_file_id, chat_draft, hidden, parent_session_id, parent_tool_call_id, subagent_agent_id, subagent_agent_name, parent_assistant_message_id, session_usage_key, created_at, last_opened_at)
                VALUES (:workspaceId, :name, :position, :reviewPanelOpen, :reviewSource, :selectedChangedFileId, '', :hidden, :parentSessionId, :parentToolCallId, :subagentAgentId, :subagentAgentName, :parentAssistantMessageId, :sessionUsageKey, :createdAt, :lastOpenedAt)
                """, params -> params
                .addValue("workspaceId", workspaceId)
                .addValue("name", name)
                .addValue("position", position)
                .addValue("reviewPanelOpen", reviewPanelOpen)
                .addValue("reviewSource", reviewSource.name())
                .addValue("selectedChangedFileId", selectedChangedFileId)
                .addValue("hidden", hidden)
                .addValue("parentSessionId", parentSessionId)
                .addValue("parentToolCallId", parentToolCallId)
                .addValue("subagentAgentId", subagentAgentId)
                .addValue("subagentAgentName", subagentAgentName)
                .addValue("parentAssistantMessageId", parentAssistantMessageId)
                .addValue("sessionUsageKey", UUID.randomUUID().toString())
                .addValue("createdAt", Timestamp.from(now))
                .addValue("lastOpenedAt", Timestamp.from(now)));
    }

    void updateSessionLastOpened(long sessionId, Instant now) {
        jdbc.update("UPDATE sessions SET last_opened_at = :lastOpenedAt WHERE id = :sessionId",
                new MapSqlParameterSource().addValue("sessionId", sessionId).addValue("lastOpenedAt", Timestamp.from(now)));
    }

    void updateSessionUnread(long sessionId, boolean unread) {
        jdbc.update("UPDATE sessions SET unread = :unread WHERE id = :sessionId",
                new MapSqlParameterSource().addValue("sessionId", sessionId).addValue("unread", unread));
    }

    void updateWorkspaceSessionsUnread(long workspaceId, boolean unread) {
        jdbc.update("UPDATE sessions SET unread = :unread WHERE workspace_id = :workspaceId AND hidden = FALSE",
                new MapSqlParameterSource().addValue("workspaceId", workspaceId).addValue("unread", unread));
    }

    void updateProjectSessionsUnread(long projectId, boolean unread) {
        jdbc.update("""
                UPDATE sessions
                SET unread = :unread
                WHERE hidden = FALSE
                  AND workspace_id IN (SELECT id FROM workspaces WHERE project_id = :projectId)
                """, new MapSqlParameterSource().addValue("projectId", projectId).addValue("unread", unread));
    }

    void updateSessionReviewState(long sessionId, boolean reviewPanelOpen, Persistence.ReviewSource reviewSource, Long selectedChangedFileId) {
        jdbc.update("UPDATE sessions SET review_panel_open = :reviewPanelOpen, review_source = :reviewSource, selected_changed_file_id = :selectedChangedFileId WHERE id = :sessionId",
                new MapSqlParameterSource()
                        .addValue("sessionId", sessionId)
                        .addValue("reviewPanelOpen", reviewPanelOpen)
                        .addValue("reviewSource", reviewSource.name())
                        .addValue("selectedChangedFileId", selectedChangedFileId));
    }

    void updateSessionSelectedChangedFile(long sessionId, Long selectedChangedFileId) {
        jdbc.update("UPDATE sessions SET review_source = :reviewSource, selected_changed_file_id = :selectedChangedFileId WHERE id = :sessionId",
                new MapSqlParameterSource()
                        .addValue("sessionId", sessionId)
                        .addValue("reviewSource", Persistence.ReviewSource.SESSION.name())
                        .addValue("selectedChangedFileId", selectedChangedFileId));
    }

    void updateSessionReviewSource(long sessionId, Persistence.ReviewSource reviewSource) {
        jdbc.update("UPDATE sessions SET review_source = :reviewSource WHERE id = :sessionId",
                new MapSqlParameterSource()
                        .addValue("sessionId", sessionId)
                        .addValue("reviewSource", reviewSource.name()));
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

    List<ConversationMessageRow> listMessagesThroughTurnId(long sessionId, long maxTurnId) {
        return jdbc.query("SELECT * FROM conversation_messages WHERE session_id = :sessionId AND turn_id <= :maxTurnId ORDER BY sequence ASC",
                new MapSqlParameterSource().addValue("sessionId", sessionId).addValue("maxTurnId", maxTurnId), this::mapConversationMessage);
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
        return insertConversationMessage(sessionId, publicId, role, turnId, sequence, content, toolCallId, toolCallsJson, showInChat, includeInModel, pending, null, null, null, null, null, null, now);
    }

    long insertConversationMessage(long sessionId, String publicId, String role, long turnId, long sequence, String content,
                                   String toolCallId, String toolCallsJson, boolean showInChat, boolean includeInModel, boolean pending,
                                   String agentId, String agentName, String modelId, String thinkingLevel, Long compactedThroughTurnId, Instant completedAt, Instant now) {
        return insertAndReturnId("""
                INSERT INTO conversation_messages
                (session_id, public_id, role, turn_id, sequence, content, tool_call_id, tool_calls_json, show_in_chat, include_in_model, pending, agent_id, agent_name, model_id, thinking_level, compacted_through_turn_id, completed_at, created_at)
                VALUES (:sessionId, :publicId, :role, :turnId, :sequence, :content, :toolCallId, :toolCallsJson, :showInChat, :includeInModel, :pending, :agentId, :agentName, :modelId, :thinkingLevel, :compactedThroughTurnId, :completedAt, :createdAt)
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
                .addValue("agentId", agentId)
                .addValue("agentName", agentName)
                .addValue("modelId", modelId)
                .addValue("thinkingLevel", thinkingLevel)
                .addValue("compactedThroughTurnId", compactedThroughTurnId)
                .addValue("completedAt", completedAt == null ? null : Timestamp.from(completedAt))
                .addValue("createdAt", Timestamp.from(now)));
    }

    void updateMessageContentAndPending(long messageId, String content, boolean pending, boolean includeInModel, Instant completedAt) {
        jdbc.update("UPDATE conversation_messages SET content = :content, pending = :pending, include_in_model = :includeInModel, completed_at = :completedAt WHERE id = :messageId",
                new MapSqlParameterSource().addValue("messageId", messageId).addValue("content", content).addValue("pending", pending).addValue("includeInModel", includeInModel)
                        .addValue("completedAt", completedAt == null ? null : Timestamp.from(completedAt)));
    }

    void updateConversationMessagesIncludeInModelUpToTurnId(long sessionId, long maxTurnId, boolean includeInModel) {
        if (maxTurnId <= 0) {
            return;
        }
        jdbc.update("UPDATE conversation_messages SET include_in_model = :includeInModel WHERE session_id = :sessionId AND turn_id <= :maxTurnId",
                new MapSqlParameterSource()
                        .addValue("sessionId", sessionId)
                        .addValue("maxTurnId", maxTurnId)
                        .addValue("includeInModel", includeInModel));
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

    void deleteChangedFilesBySession(long sessionId) {
        jdbc.update("DELETE FROM changed_files WHERE session_id = :sessionId",
                new MapSqlParameterSource("sessionId", sessionId));
    }

    void deleteToolCallTracesBySession(long sessionId) {
        jdbc.update("DELETE FROM tool_call_traces WHERE session_id = :sessionId",
                new MapSqlParameterSource("sessionId", sessionId));
    }

    void deleteConversationMessagesBySession(long sessionId) {
        jdbc.update("DELETE FROM conversation_messages WHERE session_id = :sessionId",
                new MapSqlParameterSource("sessionId", sessionId));
    }

    void deleteSession(long sessionId) {
        jdbc.update("DELETE FROM sessions WHERE id = :sessionId", new MapSqlParameterSource("sessionId", sessionId));
    }

    long insertStartedToolCallTrace(long sessionId, long assistantMessageId, long sequence, String toolCallId, String toolName, String argsJson, Instant now) {
        return insertAndReturnId("""
                INSERT INTO tool_call_traces (session_id, assistant_message_id, sequence, tool_call_id, tool_name, success, args_json, text_summary, machine_summary_json, completed_at, created_at)
                VALUES (:sessionId, :assistantMessageId, :sequence, :toolCallId, :toolName, NULL, :argsJson, NULL, NULL, NULL, :createdAt)
                """, params -> params
                .addValue("sessionId", sessionId)
                .addValue("assistantMessageId", assistantMessageId)
                .addValue("sequence", sequence)
                .addValue("toolCallId", toolCallId)
                .addValue("toolName", toolName)
                .addValue("argsJson", argsJson)
                .addValue("createdAt", Timestamp.from(now)));
    }

    void completeToolCallTrace(long toolCallTraceId, Boolean success, String argsJson, String textSummary, String machineSummaryJson, Instant completedAt) {
        jdbc.update("""
                UPDATE tool_call_traces
                SET success = :success,
                    args_json = :argsJson,
                    text_summary = :textSummary,
                    machine_summary_json = :machineSummaryJson,
                    completed_at = :completedAt
                WHERE id = :toolCallTraceId
                """, new MapSqlParameterSource()
                .addValue("toolCallTraceId", toolCallTraceId)
                .addValue("success", success)
                .addValue("argsJson", argsJson)
                .addValue("textSummary", textSummary)
                .addValue("machineSummaryJson", machineSummaryJson)
                .addValue("completedAt", Timestamp.from(completedAt)));
    }

    long insertToolCallTrace(long sessionId, long assistantMessageId, long sequence, String toolCallId, String toolName, Boolean success, String argsJson, String textSummary, String machineSummaryJson, Instant completedAt, Instant now) {
        return insertAndReturnId("""
                INSERT INTO tool_call_traces (session_id, assistant_message_id, sequence, tool_call_id, tool_name, success, args_json, text_summary, machine_summary_json, completed_at, created_at)
                VALUES (:sessionId, :assistantMessageId, :sequence, :toolCallId, :toolName, :success, :argsJson, :textSummary, :machineSummaryJson, :completedAt, :createdAt)
                """, params -> params
                .addValue("sessionId", sessionId)
                .addValue("assistantMessageId", assistantMessageId)
                .addValue("sequence", sequence)
                .addValue("toolCallId", toolCallId)
                .addValue("toolName", toolName)
                .addValue("success", success)
                .addValue("argsJson", argsJson)
                .addValue("textSummary", textSummary)
                .addValue("machineSummaryJson", machineSummaryJson)
                .addValue("completedAt", completedAt == null ? null : Timestamp.from(completedAt))
                .addValue("createdAt", Timestamp.from(now)));
    }

    List<ToolCallTraceRow> listToolCallTracesBySession(long sessionId) {
        return jdbc.query("SELECT * FROM tool_call_traces WHERE session_id = :sessionId ORDER BY sequence ASC",
                new MapSqlParameterSource("sessionId", sessionId), this::mapToolCallTrace);
    }

    List<ToolCallTraceRow> listToolCallTraceProjectionsBySession(long sessionId) {
        return jdbc.query("SELECT id, session_id, assistant_message_id, sequence, tool_call_id, tool_name, success, NULL AS args_json, NULL AS text_summary, NULL AS machine_summary_json, completed_at, created_at FROM tool_call_traces WHERE session_id = :sessionId ORDER BY sequence ASC",
                new MapSqlParameterSource("sessionId", sessionId), this::mapToolCallTrace);
    }

    List<ToolCallTraceRow> listToolCallTracesBySessionAndToolNames(long sessionId, Collection<String> toolNames) {
        if (toolNames.isEmpty()) {
            return List.of();
        }
        return jdbc.query("SELECT * FROM tool_call_traces WHERE session_id = :sessionId AND tool_name IN (:toolNames) ORDER BY sequence ASC",
                new MapSqlParameterSource().addValue("sessionId", sessionId).addValue("toolNames", toolNames), this::mapToolCallTrace);
    }

    List<TaskCallProjectionRow> listTaskCallProjectionsBySession(long sessionId) {
        return jdbc.query("""
                SELECT t.id, t.session_id, t.assistant_message_id, t.sequence, t.tool_call_id, t.success, t.completed_at,
                       CASE WHEN json_valid(t.args_json) THEN
                           substr(COALESCE(NULLIF(json_extract(t.args_json, '$.requestSummary'), ''), json_extract(t.args_json, '$.task')), 1, 500)
                       END AS request_summary,
                       child.id AS subagent_session_id, child.subagent_agent_id, child.subagent_agent_name,
                       EXISTS(
                           SELECT 1
                           FROM conversation_messages child_message
                           WHERE child_message.session_id = child.id AND child_message.role = 'assistant'
                             AND child_message.show_in_chat = TRUE
                             AND child_message.sequence = (SELECT MAX(latest.sequence) FROM conversation_messages latest WHERE latest.session_id = child.id AND latest.role = 'assistant' AND latest.show_in_chat = TRUE)
                             AND child_message.pending = TRUE
                       ) AS subagent_in_progress
                FROM tool_call_traces t
                LEFT JOIN sessions child ON child.parent_session_id = t.session_id
                    AND child.parent_tool_call_id = t.tool_call_id AND child.hidden = TRUE
                WHERE t.session_id = :sessionId AND t.tool_name = 'task'
                ORDER BY t.sequence ASC
                """, new MapSqlParameterSource("sessionId", sessionId), (rs, rowNum) -> new TaskCallProjectionRow(
                rs.getLong("id"), rs.getLong("session_id"), rs.getLong("assistant_message_id"), rs.getLong("sequence"),
                rs.getString("tool_call_id"), nullableBoolean(rs, "success"), timestampToInstant(rs.getTimestamp("completed_at")),
                rs.getString("request_summary"), nullableLong(rs, "subagent_session_id"), rs.getString("subagent_agent_id"),
                rs.getString("subagent_agent_name"), rs.getBoolean("subagent_in_progress")));
    }

    Optional<TaskCallProjectionRow> findTaskCallProjection(long sessionId, String toolCallId) {
        return listTaskCallProjectionsBySession(sessionId).stream()
                .filter(row -> toolCallId.equals(row.toolCallId()))
                .findFirst();
    }

    Optional<ConversationMessageRow> findMessageBySessionAndPublicIdOptional(long sessionId, String publicId) {
        return queryOne("SELECT * FROM conversation_messages WHERE session_id = :sessionId AND public_id = :publicId",
                new MapSqlParameterSource().addValue("sessionId", sessionId).addValue("publicId", publicId), this::mapConversationMessage);
    }

    List<ToolCallTraceRow> listToolCallTracesByAssistantMessage(long assistantMessageId) {
        return jdbc.query("SELECT * FROM tool_call_traces WHERE assistant_message_id = :assistantMessageId ORDER BY sequence ASC",
                new MapSqlParameterSource("assistantMessageId", assistantMessageId), this::mapToolCallTrace);
    }

    List<ToolCallTraceRow> listToolCallTracesByAssistantMessageAndToolCallIds(long assistantMessageId, Collection<String> toolCallIds) {
        if (toolCallIds.isEmpty()) {
            return List.of();
        }
        return jdbc.query("SELECT * FROM tool_call_traces WHERE assistant_message_id = :assistantMessageId AND tool_call_id IN (:toolCallIds) ORDER BY sequence ASC",
                new MapSqlParameterSource().addValue("assistantMessageId", assistantMessageId).addValue("toolCallIds", toolCallIds), this::mapToolCallTrace);
    }

    List<ToolCallTraceRow> listIncompleteToolCallTracesByAssistantMessage(long assistantMessageId) {
        return jdbc.query("SELECT * FROM tool_call_traces WHERE assistant_message_id = :assistantMessageId AND completed_at IS NULL ORDER BY sequence ASC",
                new MapSqlParameterSource("assistantMessageId", assistantMessageId), this::mapToolCallTrace);
    }

    void failIncompleteToolCallTracesByAssistantMessage(long assistantMessageId, String textSummary, Instant completedAt) {
        jdbc.update("""
                UPDATE tool_call_traces
                SET success = FALSE,
                    text_summary = :textSummary,
                    completed_at = :completedAt
                WHERE assistant_message_id = :assistantMessageId AND completed_at IS NULL
                """, new MapSqlParameterSource()
                .addValue("assistantMessageId", assistantMessageId)
                .addValue("textSummary", textSummary)
                .addValue("completedAt", Timestamp.from(completedAt)));
    }

    Optional<ToolCallTraceRow> findToolCallTraceBySessionAndToolCallId(long sessionId, String toolCallId) {
        return queryOne("""
                SELECT *
                FROM tool_call_traces
                WHERE session_id = :sessionId AND tool_call_id = :toolCallId
                ORDER BY sequence ASC
                LIMIT 1
                """, new MapSqlParameterSource().addValue("sessionId", sessionId).addValue("toolCallId", toolCallId), this::mapToolCallTrace);
    }

    boolean existsToolCallTraceBySessionAndToolCallId(long sessionId, String toolCallId) {
        return findToolCallTraceBySessionAndToolCallId(sessionId, toolCallId).isPresent();
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

    WorkspaceRow findNextWorkspaceAfter(long projectId, long position) {
        return queryOne("""
                SELECT w.*, 
                       EXISTS(
                           SELECT 1
                           FROM sessions s
                           WHERE s.workspace_id = w.id AND s.hidden = FALSE AND s.unread = TRUE
                       ) AS unread,
                       EXISTS(
                           SELECT 1
                           FROM sessions s
                           JOIN conversation_messages m ON m.session_id = s.id
                           WHERE s.workspace_id = w.id AND s.hidden = FALSE AND m.role = 'assistant' AND m.show_in_chat = TRUE AND m.sequence = (SELECT MAX(m2.sequence) FROM conversation_messages m2 WHERE m2.session_id = s.id AND m2.role = 'assistant' AND m2.show_in_chat = TRUE) AND m.pending = TRUE
                       ) AS in_progress
                FROM workspaces w
                WHERE w.project_id = :projectId AND w.position > :position
                ORDER BY w.position ASC
                LIMIT 1
                """,
                new MapSqlParameterSource().addValue("projectId", projectId).addValue("position", position), this::mapWorkspace).orElse(null);
    }

    WorkspaceRow findPreviousWorkspaceBefore(long projectId, long position) {
        return queryOne("""
                SELECT w.*, 
                       EXISTS(
                           SELECT 1
                           FROM sessions s
                           WHERE s.workspace_id = w.id AND s.hidden = FALSE AND s.unread = TRUE
                       ) AS unread,
                       EXISTS(
                           SELECT 1
                           FROM sessions s
                           JOIN conversation_messages m ON m.session_id = s.id
                           WHERE s.workspace_id = w.id AND s.hidden = FALSE AND m.role = 'assistant' AND m.show_in_chat = TRUE AND m.sequence = (SELECT MAX(m2.sequence) FROM conversation_messages m2 WHERE m2.session_id = s.id AND m2.role = 'assistant' AND m2.show_in_chat = TRUE) AND m.pending = TRUE
                       ) AS in_progress
                FROM workspaces w
                WHERE w.project_id = :projectId AND w.position < :position
                ORDER BY w.position DESC
                LIMIT 1
                """,
                new MapSqlParameterSource().addValue("projectId", projectId).addValue("position", position), this::mapWorkspace).orElse(null);
    }

    void deleteWorkspace(long workspaceId) {
        jdbc.update("DELETE FROM workspaces WHERE id = :workspaceId", new MapSqlParameterSource("workspaceId", workspaceId));
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
                timestampToInstant(rs.getTimestamp("closed_at")), timestampToInstant(rs.getTimestamp("created_at")), timestampToInstant(rs.getTimestamp("last_opened_at")),
                rs.getString("workspace_init_commands"), rs.getString("environment_variables"));
    }

    private WorkspaceRow mapWorkspace(ResultSet rs, int rowNum) throws SQLException {
        return new WorkspaceRow(rs.getLong("id"), rs.getLong("project_id"), rs.getString("name"), rs.getString("normalized_path"), rs.getLong("position"),
                timestampToInstant(rs.getTimestamp("created_at")), timestampToInstant(rs.getTimestamp("last_opened_at")), rs.getBoolean("unread"), rs.getBoolean("in_progress"));
    }

    private McpServerRow mapMcpServer(ResultSet rs, int rowNum) throws SQLException {
        return new McpServerRow(rs.getLong("id"), rs.getString("name"), rs.getString("url"), rs.getBoolean("enabled"), rs.getString("headers_json"), timestampToInstant(rs.getTimestamp("created_at")), List.of());
    }

    private SessionRow mapSession(ResultSet rs, int rowNum) throws SQLException {
        Long selectedChangedFileId = nullableLong(rs, "selected_changed_file_id");
        return new SessionRow(rs.getLong("id"), rs.getLong("workspace_id"), rs.getString("name"), rs.getLong("position"), rs.getBoolean("review_panel_open"),
                Persistence.ReviewSource.valueOf(rs.getString("review_source")), selectedChangedFileId, rs.getString("chat_draft"), rs.getBoolean("unread"), rs.getBoolean("hidden"),
                nullableLong(rs, "parent_session_id"), rs.getString("parent_tool_call_id"), rs.getString("subagent_agent_id"), rs.getString("subagent_agent_name"),
                nullableLong(rs, "parent_assistant_message_id"), timestampToInstant(rs.getTimestamp("created_at")), timestampToInstant(rs.getTimestamp("last_opened_at")), rs.getBoolean("in_progress"));
    }

    private ConversationMessageRow mapConversationMessage(ResultSet rs, int rowNum) throws SQLException {
        return new ConversationMessageRow(rs.getLong("id"), rs.getLong("session_id"), rs.getString("public_id"), rs.getString("role"), rs.getLong("turn_id"),
                rs.getLong("sequence"), rs.getString("content"), rs.getString("tool_call_id"), rs.getString("tool_calls_json"), rs.getBoolean("show_in_chat"),
                rs.getBoolean("include_in_model"), rs.getBoolean("pending"), rs.getString("agent_id"), rs.getString("agent_name"), rs.getString("model_id"),
                rs.getString("thinking_level"), nullableLong(rs, "compacted_through_turn_id"), timestampToInstant(rs.getTimestamp("completed_at")), timestampToInstant(rs.getTimestamp("created_at")));
    }

    private OpenAiOAuthStateRow mapOpenAiOAuthState(ResultSet rs, int rowNum) throws SQLException {
        return new OpenAiOAuthStateRow(rs.getString("openai_access_token"), rs.getString("openai_refresh_token"), rs.getString("openai_id_token"),
                rs.getString("openai_account_id"), timestampToInstant(rs.getTimestamp("openai_expires_at")));
    }

    private ToolCallTraceRow mapToolCallTrace(ResultSet rs, int rowNum) throws SQLException {
        return new ToolCallTraceRow(rs.getLong("id"), rs.getLong("session_id"), rs.getLong("assistant_message_id"), rs.getLong("sequence"), rs.getString("tool_call_id"),
                rs.getString("tool_name"), nullableBoolean(rs, "success"), rs.getString("args_json"), rs.getString("text_summary"), rs.getString("machine_summary_json"),
                timestampToInstant(rs.getTimestamp("completed_at")), timestampToInstant(rs.getTimestamp("created_at")));
    }

    private ChangedFileRow mapChangedFile(ResultSet rs, int rowNum) throws SQLException {
        return new ChangedFileRow(rs.getLong("id"), rs.getLong("session_id"), rs.getString("path"), rs.getString("diff"), rs.getLong("position"), timestampToInstant(rs.getTimestamp("created_at")));
    }

    private static Instant timestampToInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static Integer nullableInteger(ResultSet rs, String columnLabel) throws SQLException {
        int value = rs.getInt(columnLabel);
        return rs.wasNull() ? null : value;
    }

    private static Long nullableLong(ResultSet rs, String columnLabel) throws SQLException {
        long value = rs.getLong(columnLabel);
        return rs.wasNull() ? null : value;
    }

    private static Boolean nullableBoolean(ResultSet rs, String columnLabel) throws SQLException {
        boolean value = rs.getBoolean(columnLabel);
        return rs.wasNull() ? null : value;
    }

    record AppStateRow(Long activeProjectId, Long activeWorkspaceId, Long activeSessionId) {}
    record AppStateLifecycleHookSettingsRow(String assistantCompletedScript, String assistantErroredScript,
                                             String subagentCompletedScript, Integer timeoutSeconds) {}
    public record OpenAiOAuthStateRow(String accessToken, String refreshToken, String idToken, String accountId, Instant expiresAt) {}
    record ProjectRow(long id, String name, String normalizedPath, long displayOrder, Instant closedAt, Instant createdAt, Instant lastOpenedAt, String workspaceInitCommands, String environmentVariables) {}
    record McpServerRow(long id, String name, String url, boolean enabled, String headersJson, Instant createdAt, List<Long> exposedProjectIds) {}
    record WorkspaceRow(long id, long projectId, String name, String normalizedPath, long position, Instant createdAt, Instant lastOpenedAt, boolean unread, boolean inProgress) {}
    record SessionUsageContext(String sessionUsageKey, long sessionId, long workspaceId, long projectId, String sessionName,
                               String workspaceName, String projectName, String workspacePath, String projectPath) {}
    record LifecycleHookContextRow(long sessionId, String projectName, String workspaceName, String sessionName,
                                   String environmentVariables) {}
    record SessionRow(long id, long workspaceId, String name, long position, boolean reviewPanelOpen, Persistence.ReviewSource reviewSource, Long selectedChangedFileId,
                      String chatDraft, boolean unread, boolean hidden, Long parentSessionId, String parentToolCallId, String subagentAgentId, String subagentAgentName,
                      Long parentAssistantMessageId, Instant createdAt, Instant lastOpenedAt, boolean inProgress) {}
    record ConversationMessageRow(long id, long sessionId, String publicId, String role, long turnId, long sequence, String content, String toolCallId, String toolCallsJson, boolean showInChat, boolean includeInModel, boolean pending,
                                  String agentId, String agentName, String modelId, String thinkingLevel, Long compactedThroughTurnId, Instant completedAt, Instant createdAt) {}
    record ToolCallTraceRow(long id, long sessionId, long assistantMessageId, long sequence, String toolCallId, String toolName, Boolean success, String argsJson, String textSummary, String machineSummaryJson, Instant completedAt, Instant createdAt) {}
    record TaskCallProjectionRow(long id, long sessionId, long assistantMessageId, long sequence, String toolCallId, Boolean success,
                                 Instant completedAt, String requestSummary, Long subagentSessionId, String subagentAgentId,
                                 String subagentAgentName, boolean subagentInProgress) {}
    record ChangedFileRow(long id, long sessionId, String path, String diff, long position, Instant createdAt) {}
}
