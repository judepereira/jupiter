package com.judepereira.jupiter.watch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.judepereira.jupiter.agent.catalog.AgentDefinitionService;
import com.judepereira.jupiter.agent.catalog.AgentMode;
import com.judepereira.jupiter.command.CommandCatalogService.CommandDefinition;
import com.judepereira.jupiter.command.CommandCatalogService.CommandKind;
import com.judepereira.jupiter.persistence.TestAppStateSupport;
import com.judepereira.jupiter.persistence.TestAppStateSupport.AppStateTestContext;
import com.judepereira.jupiter.testsupport.TestEncryptionSupport;
import com.judepereira.jupiter.ui.UiController;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class AutonomousActionServiceTest {
    private static final CommandDefinition COMMAND = new CommandDefinition("act", "Act", "", CommandKind.PROMPT,
            "do the thing", null, null);
    private static final CommandDefinition SCRIPT = new CommandDefinition("script", "Script", "", CommandKind.SCRIPT,
            "echo no", null, null);

    @Test
    void rejectsNonPromptAndSubagentBeforeAdmission() {
        var context = context();
        var ui = mock(UiController.class);
        var agents = new AgentDefinitionService(new ObjectMapper());
        var service = service(context, ui, agents);
        assertThatThrownBy(() -> service.start(session(context), null, "plan", 0, () -> true, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.start(session(context), SCRIPT, "plan", 0, () -> true, 1))
                .isInstanceOf(IllegalArgumentException.class);
        var subagent = agents.list().stream().filter(a -> a.mode() == AgentMode.SUBAGENT).findFirst().orElseThrow();
        assertThatThrownBy(() -> service.start(session(context), COMMAND, subagent.id(), 0, () -> true, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validationFalseAndStaleActivityDoNotPersistOrRegister() {
        var context = context();
        long session = session(context);
        var ui = mock(UiController.class);
        var service = service(context, ui, new AgentDefinitionService(new ObjectMapper()));
        var run = run(context, session);
        var before = context.service().loadSessionDetail(session).chatMessages().size();
        assertThat(service.start(session, COMMAND, "plan", 0, () -> false, run)).isEmpty();
        assertThat(context.service().loadSessionDetail(session).chatMessages()).hasSize(before);
        assertThat(service.start(session, COMMAND, "plan", 1, () -> true, run)).isEmpty();
        verify(ui, Mockito.never()).prepareRegisteredStream(any(Long.class), anyString(), any(), any());
    }

    @Test
    void markDispatchedFailureRollsBackTurnAndDiscardsPreparedStream() {
        var context = context();
        long session = session(context);
        var ui = mock(UiController.class);
        when(ui.prepareRegisteredStream(any(Long.class), anyString(), any(), any())).thenReturn("assistant");
        var service = service(context, ui, new AgentDefinitionService(new ObjectMapper()));
        int before = context.service().loadSessionDetail(session).chatMessages().size();
        assertThat(service.start(session, COMMAND, "plan", 0, () -> true, 999999L)).isEmpty();
        assertThat(context.service().loadSessionDetail(session).chatMessages()).hasSize(before);
        verify(ui).discardRegisteredStream("assistant");
        assertThat(new WatchRepository(new NamedParameterJdbcTemplate(context.dataSource()),
                TestEncryptionSupport.encryptor()).runs(session)).isEmpty();
    }

    @Test
    void successfulAdmissionMarksRunAndStartsRegisteredStream() {
        var context = context();
        long session = session(context);
        var ui = mock(UiController.class);
        when(ui.prepareRegisteredStream(any(Long.class), anyString(), any(), any())).thenReturn("assistant");
        var service = service(context, ui, new AgentDefinitionService(new ObjectMapper()));
        long run = run(context, session);
        assertThat(service.start(session, COMMAND, "plan", 0, () -> true, run)).contains("assistant");
        verify(ui).startRegisteredStream("assistant");
        assertThat(new WatchRepository(new NamedParameterJdbcTemplate(context.dataSource()),
                TestEncryptionSupport.encryptor()).runs(session)).singleElement()
                .extracting(WatchService.Run::dispatched).isEqualTo(true);
        assertThat(context.service().loadSessionDetail(session).chatMessages()).hasSize(1);
    }

    @Test
    void startFailureDiscardsRegistration() {
        var context = context();
        long session = session(context);
        var ui = mock(UiController.class);
        when(ui.prepareRegisteredStream(any(Long.class), anyString(), any(), any())).thenReturn("assistant");
        Mockito.doThrow(new IllegalStateException("boom")).when(ui).startRegisteredStream("assistant");
        var service = service(context, ui, new AgentDefinitionService(new ObjectMapper()));
        assertThatThrownBy(() -> service.start(session, COMMAND, "plan", 0, () -> true, run(context, session)))
                .isInstanceOf(IllegalStateException.class);
        verify(ui).discardRegisteredStream("assistant");
    }

    private static AppStateTestContext context() {
        return TestAppStateSupport.appStateContext(event -> {
        });
    }

    private static long session(AppStateTestContext context) {
        var project = context.service().addOrReopenProject("autonomous", "/tmp/auto-" + UUID.randomUUID());
        return context.service().loadViewData().activeSession().id();
    }

    private static long run(AppStateTestContext context, long session) {
        var repo = new WatchRepository(new NamedParameterJdbcTemplate(context.dataSource()),
                TestEncryptionSupport.encryptor());
        long project = context.service().loadViewData().activeProject().id();
        var definition = new WatchService.Definition(0, project, "watch", "prompt", 60, "plan", "plan", "act", 1);
        long watch = repo.insert(definition, Instant.now());
        repo.enable(watch, session);
        return repo.startRun(definitionWithId(definition, watch), session, Instant.now(), 0).orElseThrow();
    }

    private static WatchService.Definition definitionWithId(WatchService.Definition d, long id) {
        return new WatchService.Definition(id, d.projectId(), d.name(), d.prompt(), d.intervalSeconds(),
                d.evaluatorAgentId(), d.actionAgentId(), d.actionCommandId(), d.configVersion());
    }

    private static AutonomousActionService service(AppStateTestContext context, UiController ui,
            AgentDefinitionService agents) {
        var repo = new WatchRepository(new NamedParameterJdbcTemplate(context.dataSource()),
                TestEncryptionSupport.encryptor());
        return new AutonomousActionService(context.service(), agents, context.activityCoordinator(), ui, repo,
                new TransactionTemplate(new DataSourceTransactionManager(context.dataSource())));
    }
}
