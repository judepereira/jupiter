package com.judepereira.jupiter.watch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.judepereira.jupiter.agent.catalog.AgentDefinition;
import com.judepereira.jupiter.agent.catalog.AgentDefinitionService;
import com.judepereira.jupiter.agent.catalog.AgentMode;
import com.judepereira.jupiter.agent.catalog.ThinkingLevel;
import com.judepereira.jupiter.agent.harness.AgentTurnRequest;
import com.judepereira.jupiter.agent.harness.AgentTurnResult;
import com.judepereira.jupiter.agent.harness.CodingAgentHarness;
import com.judepereira.jupiter.agent.llm.dto.Message;
import com.judepereira.jupiter.command.CommandCatalogService;
import com.judepereira.jupiter.persistence.AppStateService;
import com.judepereira.jupiter.persistence.Persistence.SessionDetailView;
import com.judepereira.jupiter.ui.ActiveStreamRegistryService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class WatchRunnerServiceTest {
    private final WatchRepository repository = mock(WatchRepository.class);
    private final WatchService watches = mock(WatchService.class);
    private final CodingAgentHarness harness = mock(CodingAgentHarness.class);
    private final AgentDefinitionService agents = mock(AgentDefinitionService.class);
    private final CommandCatalogService commands = mock(CommandCatalogService.class);
    private final AppStateService state = mock(AppStateService.class);
    private final AutonomousActionService actions = mock(AutonomousActionService.class);
    private final ActiveStreamRegistryService activeStreams = mock(ActiveStreamRegistryService.class);
    private final SessionActivityCoordinator coordinator = new SessionActivityCoordinator(activeStreams, repository);
    private WatchRunnerService runner;
    private final WatchService.Definition definition = new WatchService.Definition(7, 3, "watch", "inspect", 60,
            "agent", "agent", "command", 1);
    private final WatchService.Enablement enablement = new WatchService.Enablement(7, 9, true, 0, null);
    private final AgentDefinition agent = new AgentDefinition("agent", "Agent", "", "", AgentMode.AGENT, List.of(),
            ThinkingLevel.LOW, "", false, false, List.of());

    @BeforeEach
    void setUp() {
        runner = new WatchRunnerService(repository, watches, harness, agents, commands, state, actions, activeStreams,
                coordinator);
        when(repository.enabledSessions()).thenReturn(List.of(9L));
        when(repository.definitionFor(7)).thenReturn(Optional.of(definition));
        when(watches.listEnablements(9)).thenReturn(List.of(enablement));
        when(watches.qualifyingActivity(9)).thenReturn(Optional.of(Instant.parse("2026-01-01T00:00:00Z")));
        when(activeStreams.hasActiveStreamForSession(9)).thenReturn(false);
        when(repository.enablement(7, 9)).thenReturn(Optional.of(enablement));
        when(agents.getRequired("agent")).thenReturn(agent);
        when(state.loadSessionDetail(9))
                .thenReturn(new SessionDetailView(List.of(), List.of(), false, null, null, "/tmp", ""));
        when(commands.list(any())).thenReturn(List.of(new CommandCatalogService.CommandDefinition("command", "Command",
                "", CommandCatalogService.CommandKind.PROMPT, "body", null, null)));
        when(watches.parseActionable("{\"actionable\":false}")).thenReturn(true);
        when(watches.actionableValue("{\"actionable\":false}")).thenReturn(false);
    }

    @AfterEach
    void close() {
        runner.shutdown();
    }

    @Test
    void noLastActivityMeansNoEvaluationAndNullMaxIsSafe() {
        when(watches.qualifyingActivity(9)).thenReturn(Optional.empty());
        runner.tick(Instant.parse("2026-01-10T00:00:00Z"));
        verify(harness, after(100).never()).runTurn(any());
        verify(repository, never()).startRun(any(), anyLong(), any(), anyLong());
    }

    @Test
    void firstEvaluationIsDueAtExactlyActivityPlusInterval() throws Exception {
        when(repository.startRun(any(), eq(9L), any(), eq(0L))).thenReturn(Optional.of(41L));
        when(harness.runTurn(any())).thenReturn(new AgentTurnResult("{\"actionable\":false}", List.of()));
        runner.tick(Instant.parse("2026-01-01T00:01:00Z"));
        verify(harness, timeout(1000)).runTurn(any());
        verify(repository, timeout(1000)).finishRun(eq(41L), eq("NONACTIONABLE"), eq(false), any(),
                nullable(String.class), eq(false), isNull());
    }

    @Test
    void evaluatorReceivesWorkspaceWatchDefinitionAndStrictJsonInstructionAsUserPrompt() throws Exception {
        when(repository.startRun(any(), eq(9L), any(), eq(0L))).thenReturn(Optional.of(44L));
        when(harness.runTurn(any())).thenReturn(new AgentTurnResult("{\"actionable\":false}", List.of()));

        runner.tick(Instant.parse("2026-01-01T00:01:00Z"));

        var request = ArgumentCaptor.forClass(AgentTurnRequest.class);
        verify(harness, timeout(1000)).runTurn(request.capture());
        var message = request.getValue().getConversationHistory().getFirst();
        assertThat(message.getRole()).isEqualTo(Message.Role.USER);
        assertThat(message.getContent()).contains("Return exactly JSON, no markdown", definition.prompt());
    }

    @Test
    void busySessionDoesNotEvaluate() {
        when(activeStreams.hasActiveStreamForSession(9)).thenReturn(true);
        runner.tick(Instant.parse("2026-01-01T00:10:00Z"));
        verify(harness, after(100).never()).runTurn(any());
    }

    @Test
    void activityDuringEvaluationCancelsBeforeAdmission() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(repository.startRun(any(), eq(9L), any(), eq(0L))).thenReturn(Optional.of(42L));
        when(harness.runTurn(any())).thenAnswer(invocation -> {
            entered.countDown();
            release.await(2, TimeUnit.SECONDS);
            return new AgentTurnResult("{\"actionable\":true}", List.of());
        });
        runner.tick(Instant.parse("2026-01-01T00:01:00Z"));
        assert entered.await(1, TimeUnit.SECONDS);
        coordinator.recordInteractiveActivity(9);
        release.countDown();
        verify(repository, timeout(1000)).finishRun(eq(42L), eq("CANCELLED"), any(), any(), any(), eq(false), isNull());
        verify(actions, never()).start(anyLong(), any(), any(), anyLong(), any(), anyLong());
    }

    @Test
    void malformedEvaluatorOutputIsFailedWithNoActionableValue() throws Exception {
        when(repository.startRun(any(), eq(9L), any(), eq(0L))).thenReturn(Optional.of(43L));
        when(harness.runTurn(any())).thenReturn(new AgentTurnResult("not json", List.of()));
        runner.tick(Instant.parse("2026-01-01T00:01:00Z"));
        verify(repository, timeout(1000)).finishRun(eq(43L), eq("FAILED"), isNull(), eq("not json"),
                nullable(String.class), eq(false), isNull());
    }

    @Test
    void harnessCancellationExceptionFinishesRunAsCancelled() throws Exception {
        when(repository.startRun(any(), eq(9L), any(), eq(0L))).thenReturn(Optional.of(45L));
        when(harness.runTurn(any())).thenThrow(new CancellationException("cancelled"));

        runner.tick(Instant.parse("2026-01-01T00:01:00Z"));

        verify(repository, timeout(1000)).finishRun(eq(45L), eq("CANCELLED"), isNull(), isNull(),
                eq("Evaluator cancelled"), eq(false), isNull());
    }

    @Test
    void unexpectedRuntimeFailureFinishesRunAsFailed() throws Exception {
        when(repository.startRun(any(), eq(9L), any(), eq(0L))).thenReturn(Optional.of(46L));
        when(harness.runTurn(any())).thenThrow(new IllegalStateException("provider down"));

        runner.tick(Instant.parse("2026-01-01T00:01:00Z"));

        verify(repository, timeout(1000)).finishRun(eq(46L), eq("FAILED"), isNull(), isNull(),
                contains("provider down"), eq(false), isNull());
    }

    @Test
    void cancelledEvaluationReleasesPermitForNextEvaluation() throws Exception {
        when(repository.startRun(any(), eq(9L), any(), anyLong())).thenReturn(Optional.of(47L), Optional.of(48L));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean first = new AtomicBoolean(true);
        when(harness.runTurn(any())).thenAnswer(invocation -> {
            if (first.getAndSet(false)) {
                entered.countDown();
                release.await(2, TimeUnit.SECONDS);
                throw new CancellationException();
            }
            return new AgentTurnResult("{\"actionable\":false}", List.of());
        });

        runner.tick(Instant.parse("2026-01-01T00:01:00Z"));
        assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
        coordinator.recordInteractiveActivity(9);
        release.countDown();
        verify(repository, timeout(1000)).finishRun(eq(47L), eq("CANCELLED"), any(), any(), any(), eq(false), isNull());

        when(watches.qualifyingActivity(9)).thenReturn(Optional.of(Instant.parse("2026-01-02T00:00:00Z")));
        when(repository.enablement(7, 9)).thenReturn(Optional.of(new WatchService.Enablement(7, 9, true, 1, null)));
        when(watches.listEnablements(9)).thenReturn(List.of(new WatchService.Enablement(7, 9, true, 1, null)));
        when(harness.runTurn(any())).thenReturn(new AgentTurnResult("{\"actionable\":false}", List.of()));
        runner.tick(Instant.parse("2026-01-02T00:01:00Z"));
        verify(repository, timeout(1000)).finishRun(eq(48L), eq("NONACTIONABLE"), eq(false), any(), any(), eq(false),
                isNull());
    }

    @Test
    void dormantBoundaryDoesNotStartEvaluation() {
        runner.tick(Instant.parse("2026-01-04T00:00:00Z"));
        verify(repository, never()).startRun(any(), anyLong(), any(), anyLong());
    }

    @Test
    void disabledOrChangedEnablementIsNotStarted() {
        when(watches.listEnablements(9)).thenReturn(List.of(new WatchService.Enablement(7, 9, false, 0, null)));
        runner.tick(Instant.parse("2026-01-01T00:01:00Z"));
        verify(repository, never()).startRun(any(), anyLong(), any(), anyLong());
    }
}
