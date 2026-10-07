package com.judepereira.jupiter.watch;

import com.judepereira.jupiter.security.TextEncryptor;
import jakarta.annotation.PostConstruct;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
public class WatchRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final TextEncryptor crypto;

    private String enc(String column, String value) {
        return crypto.encrypt(value, "watch_" + column);
    }
    private String dec(String column, String value) {
        return value == null ? null : crypto.decrypt(value, "watch_" + column);
    }
    private MapSqlParameterSource p() {
        return new MapSqlParameterSource();
    }

    List<WatchService.Definition> definitions(long projectId) {
        return jdbc.query("SELECT * FROM watch_definitions WHERE project_id=:projectId ORDER BY id",
                p().addValue("projectId", projectId), (r, n) -> definition(r));
    }
    Optional<WatchService.Definition> definition(long id) {
        return jdbc
                .query("SELECT * FROM watch_definitions WHERE id=:id", p().addValue("id", id), (r, n) -> definition(r))
                .stream().findFirst();
    }
    private WatchService.Definition definition(ResultSet r) throws SQLException {
        return new WatchService.Definition(r.getLong("id"), r.getLong("project_id"), dec("name", r.getString("name")),
                dec("prompt", r.getString("prompt")), r.getInt("interval_seconds"),
                dec("evaluator_agent_id", r.getString("evaluator_agent_id")),
                dec("action_agent_id", r.getString("action_agent_id")),
                dec("action_command_id", r.getString("action_command_id")), r.getInt("config_version"));
    }
    long insert(WatchService.Definition d, Instant now) {
        var key = new GeneratedKeyHolder();
        jdbc.getJdbcTemplate().update(c -> {
            var s = c.prepareStatement(
                    "INSERT INTO watch_definitions(project_id,name,prompt,interval_seconds,evaluator_agent_id,action_agent_id,action_command_id,config_version,created_at,updated_at) VALUES (?,?,?,?,?,?,?,1,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            s.setLong(1, d.projectId());
            s.setString(2, enc("name", d.name()));
            s.setString(3, enc("prompt", d.prompt()));
            s.setInt(4, d.intervalSeconds());
            s.setString(5, enc("evaluator_agent_id", d.evaluatorAgentId()));
            s.setString(6, enc("action_agent_id", d.actionAgentId()));
            s.setString(7, enc("action_command_id", d.actionCommandId()));
            s.setTimestamp(8, Timestamp.from(now));
            s.setTimestamp(9, Timestamp.from(now));
            return s;
        }, key);
        return key.getKey().longValue();
    }
    void update(WatchService.Definition d) {
        jdbc.update(
                "UPDATE watch_definitions SET name=:name,prompt=:prompt,interval_seconds=:interval,evaluator_agent_id=:eval,action_agent_id=:action,action_command_id=:command,config_version=config_version+1,updated_at=CURRENT_TIMESTAMP WHERE id=:id",
                p().addValue("id", d.id()).addValue("name", enc("name", d.name()))
                        .addValue("prompt", enc("prompt", d.prompt())).addValue("interval", d.intervalSeconds())
                        .addValue("eval", enc("evaluator_agent_id", d.evaluatorAgentId()))
                        .addValue("action", enc("action_agent_id", d.actionAgentId()))
                        .addValue("command", enc("action_command_id", d.actionCommandId())));
    }
    void delete(long id) {
        jdbc.update("UPDATE watch_enablements SET enabled=FALSE,invalidated_at=CURRENT_TIMESTAMP WHERE watch_id=:id",
                p().addValue("id", id));
        jdbc.update("DELETE FROM watch_definitions WHERE id=:id", p().addValue("id", id));
    }
    void enable(long watchId, long sessionId) {
        jdbc.update(
                "INSERT INTO watch_enablements(watch_id,session_id,enabled,last_evaluation_started_at) SELECT :w,:s,TRUE,NULL WHERE EXISTS(SELECT 1 FROM watch_definitions w JOIN sessions s ON s.workspace_id IN (SELECT id FROM workspaces WHERE project_id=w.project_id) WHERE w.id=:w AND s.id=:s) ON CONFLICT(watch_id,session_id) DO UPDATE SET enabled=TRUE,invalidated_at=NULL,last_evaluation_started_at=NULL",
                p().addValue("w", watchId).addValue("s", sessionId));
    }
    void disable(long watchId, long sessionId) {
        jdbc.update(
                "UPDATE watch_enablements SET enabled=FALSE,invalidated_at=CURRENT_TIMESTAMP WHERE watch_id=:w AND session_id=:s",
                p().addValue("w", watchId).addValue("s", sessionId));
    }
    Optional<Long> sessionProjectId(long sessionId) {
        return jdbc.query(
                "SELECT w.project_id FROM sessions s JOIN workspaces w ON w.id = s.workspace_id WHERE s.id=:id AND s.hidden=FALSE AND s.parent_session_id IS NULL",
                p().addValue("id", sessionId), (r, n) -> r.getLong(1)).stream().findFirst();
    }

    List<WatchService.Enablement> enablementsForWatch(long watchId) {
        return jdbc.query(
                "SELECT watch_id,session_id,enabled,activity_generation,last_evaluation_started_at FROM watch_enablements WHERE watch_id=:w",
                p().addValue("w", watchId),
                (r, n) -> new WatchService.Enablement(r.getLong(1), r.getLong(2), r.getBoolean(3), r.getLong(4),
                        r.getTimestamp(5) == null ? null : r.getTimestamp(5).toInstant()));
    }

    Optional<WatchService.Enablement> enablement(long watchId, long sessionId) {
        return enablements(sessionId).stream().filter(e -> e.watchId() == watchId).findFirst();
    }

    List<WatchService.Enablement> enablements(long sessionId) {
        return jdbc.query(
                "SELECT watch_id,session_id,enabled,activity_generation,last_evaluation_started_at FROM watch_enablements WHERE session_id=:s ORDER BY watch_id",
                p().addValue("s", sessionId),
                (r, n) -> new WatchService.Enablement(r.getLong(1), r.getLong(2), r.getBoolean(3), r.getLong(4),
                        r.getTimestamp(5) == null ? null : r.getTimestamp(5).toInstant()));
    }
    List<WatchService.Run> runs(long sessionId) {
        return jdbc.query(
                "SELECT id,watch_id,session_id,started_at,finished_at,status,actionable,dispatched,error,watch_name FROM watch_runs WHERE session_id=:s ORDER BY started_at DESC LIMIT 100",
                p().addValue("s", sessionId),
                (r, n) -> new WatchService.Run(r.getLong(1), r.getLong(2), r.getLong(3), r.getTimestamp(4).toInstant(),
                        r.getTimestamp(5) == null ? null : r.getTimestamp(5).toInstant(), r.getString(6),
                        r.getObject(7) == null ? null : r.getBoolean(7), r.getBoolean(8), dec("error", r.getString(9)),
                        dec("watch_name", r.getString(10))));
    }
    void activity(long sessionId) {
        jdbc.update(
                "UPDATE watch_enablements SET activity_generation=activity_generation+1,last_evaluation_started_at=NULL,invalidated_at=CURRENT_TIMESTAMP WHERE session_id=:s AND enabled=TRUE",
                p().addValue("s", sessionId));
    }

    List<Long> enabledSessions() {
        return jdbc.queryForList(
                "SELECT DISTINCT e.session_id FROM watch_enablements e JOIN sessions s ON s.id=e.session_id WHERE e.enabled=TRUE AND s.hidden=FALSE AND s.parent_session_id IS NULL",
                p(), Long.class);
    }

    Optional<WatchService.Definition> definitionFor(long watchId) {
        return definition(watchId);
    }

    @PostConstruct
    void recoverRunningRuns() {
        jdbc.update(
                "UPDATE watch_runs SET status='INTERRUPTED',finished_at=CURRENT_TIMESTAMP,error=:error WHERE status='RUNNING'",
                p().addValue("error", enc("error", "Interrupted during application restart")));
    }

    @Transactional
    Optional<Long> startRun(WatchService.Definition d, long sessionId, Instant now, long generation) {
        int claimed = jdbc.update(
                "UPDATE watch_enablements SET last_evaluation_started_at=:now WHERE watch_id=:w AND session_id=:s AND enabled=TRUE AND activity_generation=:g",
                p().addValue("now", Timestamp.from(now)).addValue("w", d.id()).addValue("s", sessionId).addValue("g",
                        generation));
        if (claimed != 1)
            return Optional.empty();
        var key = new GeneratedKeyHolder();
        jdbc.getJdbcTemplate().update(c -> {
            var s = c.prepareStatement(
                    "INSERT INTO watch_runs(watch_id,session_id,started_at,status,config_version,prompt,evaluator_agent_id,action_agent_id,action_command_id,watch_name) VALUES(?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            s.setLong(1, d.id());
            s.setLong(2, sessionId);
            s.setTimestamp(3, Timestamp.from(now));
            s.setString(4, "RUNNING");
            s.setInt(5, d.configVersion());
            s.setString(6, enc("prompt", d.prompt()));
            s.setString(7, enc("evaluator_agent_id", d.evaluatorAgentId()));
            s.setString(8, enc("action_agent_id", d.actionAgentId()));
            s.setString(9, enc("action_command_id", d.actionCommandId()));
            s.setString(10, enc("watch_name", d.name()));
            return s;
        }, key);
        return Optional.of(key.getKey().longValue());
    }

    boolean markDispatched(long runId, String chatId) {
        return jdbc.update(
                "UPDATE watch_runs SET dispatched=TRUE,chat_id=:chat WHERE id=:id AND status='RUNNING' AND dispatched=FALSE",
                p().addValue("id", runId).addValue("chat", chatId)) == 1;
    }

    void finishRun(long id, String status, Boolean actionable, String output, String error, boolean dispatched,
            String chatId) {
        jdbc.update(
                "UPDATE watch_runs SET finished_at=CURRENT_TIMESTAMP,status=:status,actionable=:actionable,output=:output,error=:error,dispatched=:dispatched,chat_id=:chat WHERE id=:id AND status='RUNNING'",
                p().addValue("id", id).addValue("status", status).addValue("actionable", actionable)
                        .addValue("output", output == null ? null : enc("output", output))
                        .addValue("error", error == null ? null : enc("error", error))
                        .addValue("dispatched", dispatched).addValue("chat", chatId));
    }

    long activityGeneration(long watchId, long sessionId) {
        Long generation = jdbc.queryForObject(
                "SELECT activity_generation FROM watch_enablements WHERE watch_id=:w AND session_id=:s",
                p().addValue("w", watchId).addValue("s", sessionId), Long.class);
        return generation == null ? 0L : generation;
    }

    boolean qualifyingAssistantActivity(long sessionId, String publicId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM conversation_messages m JOIN sessions s ON s.id = m.session_id
                WHERE m.session_id=:s AND m.public_id=:p AND m.role='assistant' AND m.show_in_chat=TRUE
                  AND m.watch_generated=FALSE AND m.pending=FALSE AND m.completed_at IS NOT NULL
                  AND m.include_in_model=TRUE AND s.hidden=FALSE AND s.parent_session_id IS NULL
                  AND m.role != 'info'
                """, p().addValue("s", sessionId).addValue("p", publicId), Integer.class);
        return count != null && count > 0;
    }

    boolean qualifyingUserActivity(long sessionId, String publicId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM conversation_messages m JOIN sessions s ON s.id = m.session_id
                WHERE m.session_id=:s AND m.public_id=:p AND m.role='user' AND m.show_in_chat=TRUE
                  AND m.watch_generated=FALSE AND s.hidden=FALSE AND s.parent_session_id IS NULL
                  AND m.role != 'info'
                """, p().addValue("s", sessionId).addValue("p", publicId), Integer.class);
        return count != null && count > 0;
    }

    Optional<Instant> qualifyingActivity(long sessionId) {
        List<Instant> rows = jdbc.query("""
                SELECT MAX(CASE WHEN m.role = 'assistant' THEN m.completed_at ELSE m.created_at END)
                FROM conversation_messages m
                JOIN sessions s ON s.id = m.session_id
                WHERE m.session_id = :s AND s.hidden = FALSE AND s.parent_session_id IS NULL
                  AND m.show_in_chat = TRUE AND m.watch_generated = FALSE
                  AND m.role != 'info'
                  AND (m.role = 'user' OR (m.role = 'assistant' AND m.pending = FALSE
                      AND m.completed_at IS NOT NULL AND m.include_in_model = TRUE))
                """, p().addValue("s", sessionId),
                (r, n) -> r.getTimestamp(1) == null ? null : r.getTimestamp(1).toInstant());
        return rows.isEmpty() || rows.get(0) == null ? Optional.empty() : Optional.of(rows.get(0));
    }
}
