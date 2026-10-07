package com.judepereira.jupiter.watch;

import static org.assertj.core.api.Assertions.assertThat;

import com.judepereira.jupiter.persistence.TestAppStateSupport;
import com.judepereira.jupiter.testsupport.TestEncryptionSupport;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class WatchRepositoryIntegrationTest {
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
