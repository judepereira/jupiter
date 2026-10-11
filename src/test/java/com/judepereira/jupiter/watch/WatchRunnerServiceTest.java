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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.LongStream;
import org.assertj.core.api.Assertions;
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
    void interruptedHarnessCancellationClearsInterruptBeforeFinishingRun() throws Exception {
        when(repository.startRun(any(), eq(9L), any(), eq(0L))).thenReturn(Optional.of(49L));
        AtomicBoolean harnessThreadInterrupted = new AtomicBoolean();
        AtomicBoolean finishThreadInterrupted = new AtomicBoolean(true);
        CountDownLatch finished = new CountDownLatch(1);
        when(harness.runTurn(any())).thenAnswer(invocation -> {
            Thread.currentThread().interrupt();
            harnessThreadInterrupted.set(Thread.currentThread().isInterrupted());
            throw new CancellationException("cancelled");
        });
        doAnswer(invocation -> {
            finishThreadInterrupted.set(Thread.currentThread().isInterrupted());
            finished.countDown();
            return null;
        }).when(repository).finishRun(eq(49L), eq("CANCELLED"), isNull(), isNull(), eq("Evaluator cancelled"),
                eq(false), isNull());

        runner.tick(Instant.parse("2026-01-01T00:01:00Z"));

        assertThat(finished.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(harnessThreadInterrupted).isTrue();
        assertThat(finishThreadInterrupted).isFalse();
        verify(repository).finishRun(eq(49L), eq("CANCELLED"), isNull(), isNull(), eq("Evaluator cancelled"), eq(false),
                isNull());
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
    void newlyDueUnstartedFifthWatchRunsBeforeRepeatedFirstFour() throws Exception {
        var firstTick = Instant.parse("2026-01-01T00:01:00Z");
        var nextTick = firstTick.plusSeconds(60);
        var definitions = LongStream.rangeClosed(7, 11).mapToObj(id -> new WatchService.Definition(id, 3, "watch",
                "inspect", id == 11 ? 120 : 60, "agent", "agent", "command", 1)).toList();
        var activity = firstTick.minusSeconds(60);
        when(repository.enabledSessions()).thenReturn(List.of(9L));
        when(watches.qualifyingActivity(9)).thenReturn(Optional.of(activity));
        when(watches.listEnablements(9))
                .thenReturn(List.of(new WatchService.Enablement(7, 9, true, 0, firstTick.minusSeconds(120)),
                        new WatchService.Enablement(8, 9, true, 0, firstTick.minusSeconds(120)),
                        new WatchService.Enablement(9, 9, true, 0, firstTick.minusSeconds(120)),
                        new WatchService.Enablement(10, 9, true, 0, firstTick.minusSeconds(120)),
                        new WatchService.Enablement(11, 9, true, 0, null)));
        definitions.forEach(d -> when(repository.definitionFor(d.id())).thenReturn(Optional.of(d)));
        var enablements = new ConcurrentHashMap<Long, WatchService.Enablement>();
        definitions.forEach(d -> enablements.put(d.id(),
                new WatchService.Enablement(d.id(), 9, true, 0, d.id() == 11 ? null : firstTick.minusSeconds(60))));
        definitions.forEach(d -> when(repository.enablement(d.id(), 9))
                .thenAnswer(invocation -> Optional.of(enablements.get(d.id()))));
        when(repository.startRun(any(), eq(9L), any(), anyLong())).thenAnswer(invocation -> {
            var definition = (WatchService.Definition) invocation.getArgument(0);
            enablements.put(definition.id(),
                    new WatchService.Enablement(definition.id(), 9, true, 0, (Instant) invocation.getArgument(2)));
            return Optional.of(definition.id());
        });
        CountDownLatch entered = new CountDownLatch(4);
        CountDownLatch release = new CountDownLatch(1);
        when(harness.runTurn(any())).thenAnswer(invocation -> {
            entered.countDown();
            release.await(2, TimeUnit.SECONDS);
            return new AgentTurnResult("{\"actionable\":false}", List.of());
        });

        runner.tick(firstTick);
        assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
        verify(repository, times(4)).startRun(any(), eq(9L), any(), anyLong());
        release.countDown();
        for (long id = 7; id <= 10; id++) {
            verify(repository, timeout(1000)).finishRun(eq(id), eq("NONACTIONABLE"), eq(false), any(), any(), eq(false),
                    isNull());
        }

        when(watches.listEnablements(9)).thenReturn(List.of(new WatchService.Enablement(7, 9, true, 0, firstTick),
                new WatchService.Enablement(8, 9, true, 0, firstTick),
                new WatchService.Enablement(9, 9, true, 0, firstTick),
                new WatchService.Enablement(10, 9, true, 0, firstTick),
                new WatchService.Enablement(11, 9, true, 0, null)));
        definitions.forEach(d -> enablements.put(d.id(),
                new WatchService.Enablement(d.id(), 9, true, 0, d.id() == 11 ? null : firstTick)));
        var secondBatchEntered = new CountDownLatch(4);
        var secondBatchRelease = new CountDownLatch(1);
        when(harness.runTurn(any())).thenAnswer(invocation -> {
            secondBatchEntered.countDown();
            secondBatchRelease.await(2, TimeUnit.SECONDS);
            return new AgentTurnResult("{\"actionable\":false}", List.of());
        });
        runner.tick(nextTick);
        assertThat(secondBatchEntered.await(1, TimeUnit.SECONDS)).isTrue();

        var claimed = ArgumentCaptor.forClass(WatchService.Definition.class);
        verify(repository, timeout(1000).times(8)).startRun(claimed.capture(), eq(9L), any(), anyLong());
        assertThat(claimed.getAllValues().subList(4, 8)).extracting(WatchService.Definition::id).containsExactly(11L,
                7L, 8L, 9L);
        secondBatchRelease.countDown();
        verify(repository, timeout(1000)).startRun(eq(definitions.get(4)), eq(9L), eq(nextTick), eq(0L));
    }

    @Test
    void unstartedWatchGetsPermitBeforeAlreadyStartedWatches() throws Exception {
        var now = Instant.parse("2026-01-01T00:01:00Z");
        var startedAt = now.minusSeconds(60);
        var definitions = List.of(
                new WatchService.Definition(7, 3, "watch", "inspect", 60, "agent", "agent", "command", 1),
                new WatchService.Definition(8, 3, "watch", "inspect", 60, "agent", "agent", "command", 1));
        when(repository.enabledSessions()).thenReturn(List.of(9L, 10L));
        when(watches.qualifyingActivity(10)).thenReturn(Optional.of(now.minusSeconds(60)));
        when(activeStreams.hasActiveStreamForSession(10)).thenReturn(false);
        when(watches.listEnablements(9)).thenReturn(List.of(new WatchService.Enablement(7, 9, true, 0, startedAt)));
        when(repository.enablement(7, 9))
                .thenReturn(Optional.of(new WatchService.Enablement(7, 9, true, 0, startedAt)));
        when(repository.enablement(8, 10)).thenReturn(Optional.of(new WatchService.Enablement(8, 10, true, 0, null)));
        when(watches.listEnablements(10)).thenReturn(List.of(new WatchService.Enablement(8, 10, true, 0, null)));
        when(watches.qualifyingActivity(10)).thenReturn(Optional.of(now.minusSeconds(120)));
        when(state.loadSessionDetail(10))
                .thenReturn(new SessionDetailView(List.of(), List.of(), false, null, null, "/tmp", ""));
        when(repository.definitionFor(7)).thenReturn(Optional.of(definitions.get(0)));
        when(repository.definitionFor(8)).thenReturn(Optional.of(definitions.get(1)));
        when(repository.enablement(8, 10)).thenReturn(Optional.of(new WatchService.Enablement(8, 10, true, 0, null)));
        when(repository.startRun(any(), anyLong(), any(), anyLong())).thenReturn(Optional.of(51L), Optional.of(52L));
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        when(harness.runTurn(any())).thenAnswer(invocation -> {
            entered.countDown();
            release.await(2, TimeUnit.SECONDS);
            return new AgentTurnResult("{\"actionable\":false}", List.of());
        });

        runner.tick(now);

        assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
        var claimed = ArgumentCaptor.forClass(WatchService.Definition.class);
        verify(repository, times(2)).startRun(claimed.capture(), anyLong(), any(), anyLong());
        assertThat(claimed.getAllValues()).extracting(WatchService.Definition::id).containsExactly(8L, 7L);
        release.countDown();
    }

    @Test
    void failedClaimDoesNotLeaveJobBlockingRetry() {
        when(repository.startRun(any(), eq(9L), any(), anyLong())).thenThrow(new IllegalStateException("claim failed"))
                .thenReturn(Optional.of(49L));
        Assertions.assertThatThrownBy(() -> runner.tick(Instant.parse("2026-01-01T00:01:00Z")))
                .isInstanceOf(IllegalStateException.class);

        runner.tick(Instant.parse("2026-01-01T00:01:01Z"));

        verify(repository, times(2)).startRun(any(), eq(9L), any(), anyLong());
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
