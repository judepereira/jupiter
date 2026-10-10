package com.judepereira.jupiter.watch;

import static org.assertj.core.api.Assertions.assertThat;

import com.judepereira.jupiter.persistence.Persistence.ChatMessageMetadata;
import com.judepereira.jupiter.persistence.TestAppStateSupport;
import com.judepereira.jupiter.testsupport.SQLiteTestSupport;
import com.judepereira.jupiter.testsupport.TestEncryptionSupport;
import java.nio.file.Files;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class WatchRepositoryIntegrationTest {
    @Test
    void v33CreatesFinalWatchSchemaFromV32AndPreservesExistingData() throws Exception {
        DataSource dataSource = SQLiteTestSupport
                .fileBackedDataSource(Files.createTempDirectory("watch-v32-").resolve("state.db"));
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").target("32").load().migrate();
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.update(
                "INSERT INTO projects(id,name,normalized_path,display_order,created_at) VALUES(1,'p','/p',1,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO workspaces(id,project_id,name,normalized_path,position) VALUES(1,1,'w','/w',1)");
        jdbc.update("INSERT INTO sessions(id,workspace_id,name,position) VALUES(1,1,'s',1)");

        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();

        assertThat(jdbc.queryForObject("SELECT watch_panel_open FROM sessions WHERE id=1", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT watch_panel_height FROM sessions WHERE id=1", Integer.class))
                .isEqualTo(320);
        jdbc.update(
                "INSERT INTO watch_definitions(id,project_id,name,prompt,interval_seconds,evaluator_agent_id,action_agent_id,action_command_id,created_at,updated_at) VALUES(999,1,'w','p',60,'e','a','c',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO watch_runs(id,watch_id,session_id,started_at,status,config_version,prompt,"
                + "evaluator_agent_id,action_agent_id,action_command_id,output,error,watch_name,chat_id) "
                + "VALUES(77,999,1,'2026-01-01T00:00:00Z','FAILED',4,'cipher-p','cipher-e','cipher-a',"
                + "'cipher-c','cipher-o','cipher-x','Legacy watch','chat-77')");

        var row = jdbc.queryForMap("SELECT watch_id,config_version,prompt,output,error,watch_name,chat_id "
                + "FROM watch_runs WHERE id=77");
        assertThat(row).containsEntry("watch_id", 999).containsEntry("config_version", 4)
                .containsEntry("prompt", "cipher-p").containsEntry("output", "cipher-o")
                .containsEntry("error", "cipher-x").containsEntry("watch_name", "Legacy watch")
                .containsEntry("chat_id", "chat-77");

        jdbc.update("DELETE FROM watch_definitions WHERE id=999");
        assertThat(jdbc.queryForObject("SELECT watch_id FROM watch_runs WHERE id=77", Integer.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM watch_runs WHERE id=77", Integer.class)).isEqualTo(1);
    }

    @Test
    void definitionsEncryptSensitiveColumnsAndRoundTripThroughFlywaySchema() {
        var context = TestAppStateSupport.appStateContext(event -> {
        });
        var project = context.service().addOrReopenProject("Watch project", "/tmp/" + UUID.randomUUID());
        long session = context.service().loadViewData().activeSession().id();
        var repository = new WatchRepository(new NamedParameterJdbcTemplate(context.dataSource()),
                TestEncryptionSupport.encryptor());
        var definition = new WatchService.Definition(0, project.id(), "Secret name", "private prompt", 60, "plan",
                "plan", "act", 1);
        long id = repository.insert(definition, Instant.parse("2026-01-01T00:00:00Z"));

        var raw = new NamedParameterJdbcTemplate(context.dataSource()).queryForMap(
                "SELECT name,prompt,evaluator_agent_id FROM watch_definitions WHERE id=:id",
                new MapSqlParameterSource("id", id));
        assertThat(raw.get("name").toString()).doesNotContain("Secret name");
        assertThat(raw.get("prompt").toString()).doesNotContain("private prompt");
        assertThat(repository.definition(id)).contains(withId(definition, id));

        repository.enable(id, session);
        assertThat(repository.enablements(session)).singleElement().satisfies(e -> {
            assertThat(e.enabled()).isTrue();
            assertThat(e.activityGeneration()).isZero();
        });
        assertThat(repository.startRun(withId(definition, id), session, Instant.now(), 0)).isPresent();
        assertThat(repository.runs(session)).singleElement().extracting(WatchService.Run::status).isEqualTo("RUNNING");
    }

    @Test
    void activityGenerationResetsClaimAndRunHistoryIsNewestHundred() {
        var context = TestAppStateSupport.appStateContext(event -> {
        });
        var project = context.service().addOrReopenProject("P", "/tmp/" + UUID.randomUUID());
        long session = context.service().loadViewData().activeSession().id();
        var repository = new WatchRepository(new NamedParameterJdbcTemplate(context.dataSource()),
                TestEncryptionSupport.encryptor());
        var definition = new WatchService.Definition(0, project.id(), "w", "p", 60, "plan", "plan", "act", 1);
        long id = repository.insert(definition, Instant.now());
        repository.enable(id, session);
        assertThat(repository.startRun(withId(definition, id), session, Instant.now(), 0)).isPresent();
        repository.activity(session);
        assertThat(repository.enablements(session).getFirst().activityGeneration()).isEqualTo(1);
        assertThat(repository.startRun(withId(definition, id), session, Instant.now(), 0)).isEmpty();
        assertThat(repository.startRun(withId(definition, id), session, Instant.now(), 1)).isPresent();
        assertThat(repository.markDispatched(1, "chat")).isTrue();
        assertThat(repository.markDispatched(1, "chat-again")).isFalse();
    }

    @Test
    void watchGeneratedTurnDoesNotBecomeQualifyingActivity() {
        var context = TestAppStateSupport.appStateContext(event -> {
        });
        var project = context.service().addOrReopenProject("P", "/tmp/" + UUID.randomUUID());
        var session = context.service().loadViewData().activeSession().id();
        var before = context.service().activityCoordinator().snapshot(session);
        context.service().appendWatchGeneratedTurn(session, "watch-user", "watch-assistant", "watch prompt",
                new ChatMessageMetadata("agent", "Agent", "model", "LOW", null));
        context.service().completeAssistantMessage(session, "watch-assistant", "watch result", null);

        var rows = new JdbcTemplate(context.dataSource()).queryForList(
                "SELECT role,watch_generated FROM conversation_messages WHERE session_id=? AND role IN ('user','assistant') ORDER BY sequence",
                session);
        assertThat(rows).extracting(row -> row.get("watch_generated")).containsExactly(1, 1);
        assertThat(context.service().activityCoordinator().snapshot(session).version()).isEqualTo(before.version());
        assertThat(new WatchRepository(new NamedParameterJdbcTemplate(context.dataSource()),
                TestEncryptionSupport.encryptor()).qualifyingActivity(session)).isEmpty();
    }

    @Test
    void deletingWatchRetainsRunSnapshotAndInvalidatesEnablement() {
        var context = TestAppStateSupport.appStateContext(event -> {
        });
        var project = context.service().addOrReopenProject("P", "/tmp/" + UUID.randomUUID());
        long session = context.service().loadViewData().activeSession().id();
        var repository = new WatchRepository(new NamedParameterJdbcTemplate(context.dataSource()),
                TestEncryptionSupport.encryptor());
        var definition = new WatchService.Definition(0, project.id(), "saved", "prompt", 60, "plan", "plan", "act", 1);
        long watchId = repository.insert(definition, Instant.now());
        repository.enable(watchId, session);
        long runId = repository.startRun(withId(definition, watchId), session, Instant.now(), 0).orElseThrow();
        repository.finishRun(runId, "NONACTIONABLE", false, "output", null, false, null);

        repository.delete(watchId);

        assertThat(repository.runs(session)).singleElement().satisfies(run -> {
            assertThat(run.id()).isEqualTo(runId);
            assertThat(run.watchId()).isNull();
            assertThat(run.watchName()).isEqualTo("saved");
        });
        assertThat(repository.enablement(watchId, session)).isEmpty();
        assertThat(repository.enablements(session)).isEmpty();
    }

    @Test
    void restartRecoveryMarksRunningRunsInterruptedAndEncryptsError() {
        var context = TestAppStateSupport.appStateContext(event -> {
        });
        var project = context.service().addOrReopenProject("Recovery", "/tmp/" + UUID.randomUUID());
        long session = context.service().loadViewData().activeSession().id();
        var repository = new WatchRepository(new NamedParameterJdbcTemplate(context.dataSource()),
                TestEncryptionSupport.encryptor());
        var definition = new WatchService.Definition(0, project.id(), "w", "p", 60, "plan", "plan", "act", 1);
        long id = repository.insert(definition, Instant.now());
        repository.enable(id, session);
        assertThat(repository.startRun(withId(definition, id), session, Instant.now(), 0)).isPresent();
        repository.recoverRunningRuns();
        var run = repository.runs(session).getFirst();
        assertThat(run.status()).isEqualTo("INTERRUPTED");
        assertThat(run.finishedAt()).isNotNull();
        assertThat(run.error()).contains("Interrupted");
    }

    private static WatchService.Definition withId(WatchService.Definition d, long id) {
        return new WatchService.Definition(id, d.projectId(), d.name(), d.prompt(), d.intervalSeconds(),
                d.evaluatorAgentId(), d.actionAgentId(), d.actionCommandId(), d.configVersion());
    }
}
