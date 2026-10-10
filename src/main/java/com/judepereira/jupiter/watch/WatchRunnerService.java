package com.judepereira.jupiter.watch;

import com.judepereira.jupiter.agent.catalog.AgentDefinition;
import com.judepereira.jupiter.agent.catalog.AgentDefinitionService;
import com.judepereira.jupiter.agent.catalog.AgentMode;
import com.judepereira.jupiter.agent.harness.AgentTurnRequest;
import com.judepereira.jupiter.agent.harness.CancellationToken;
import com.judepereira.jupiter.agent.harness.CodingAgentHarness;
import com.judepereira.jupiter.agent.harness.StreamCancelledException;
import com.judepereira.jupiter.agent.llm.dto.Message;
import com.judepereira.jupiter.command.CommandCatalogService;
import com.judepereira.jupiter.persistence.AppStateService;
import com.judepereira.jupiter.ui.ActiveStreamRegistryService;
import jakarta.annotation.PreDestroy;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.log4j.Log4j2;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Log4j2
@Service
public class WatchRunnerService {
    private static final Duration DORMANT = Duration.ofHours(72);
    private static final Duration MAX_EVALUATION = Duration.ofMinutes(2);
    private final WatchRepository repository;
    private final WatchService watches;
    private final CodingAgentHarness harness;
    private final AgentDefinitionService agents;
    private final CommandCatalogService commands;
    private final AppStateService state;
    private final AutonomousActionService actions;
    private final ActiveStreamRegistryService activeStreams;
    private final SessionActivityCoordinator coordinator;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService timeouts = Executors.newScheduledThreadPool(1);
    private final Semaphore permits = new Semaphore(4);
    private final ConcurrentHashMap<Key, Job> jobs = new ConcurrentHashMap<>();
    private final AtomicBoolean shuttingDown = new AtomicBoolean();

    public WatchRunnerService(WatchRepository repository, WatchService watches, CodingAgentHarness harness,
            AgentDefinitionService agents, CommandCatalogService commands, AppStateService state,
            AutonomousActionService actions, ActiveStreamRegistryService activeStreams,
            SessionActivityCoordinator coordinator) {
        this.repository = repository;
        this.watches = watches;
        this.harness = harness;
        this.agents = agents;
        this.commands = commands;
        this.state = state;
        this.actions = actions;
        this.activeStreams = activeStreams;
        this.coordinator = coordinator;
        coordinator.addActivityListener(this::cancelSession);
        watches.addChangeListener(this::cancelSession);
    }

    @Scheduled(fixedDelayString = "PT15S")
    void tick() {
        tick(Instant.now());
    }

    void tick(Instant now) {
        if (shuttingDown.get())
            return;
        jobs.forEach((key, job) -> {
            var activity = watches.qualifyingActivity(key.sessionId);
            var enablement = repository.enablement(key.watchId, key.sessionId).orElse(null);
            var current = repository.definitionFor(key.watchId).orElse(null);
            if (enablement == null || !enablement.enabled() || current == null
                    || current.configVersion() != job.configVersion
                    || coordinator.snapshot(key.sessionId).version() != job.activityVersion || activity.isEmpty()
                    || !activity.get().equals(job.activityTimestamp) || !now.isBefore(activity.get().plus(DORMANT)))
                cancelJob(job);
        });
        for (long session : repository.enabledSessions()) {
            var activity = watches.qualifyingActivity(session);
            if (activity.isEmpty() || !now.isBefore(activity.get().plus(DORMANT))
                    || activeStreams.hasActiveStreamForSession(session)) {
                cancelSession(session);
                continue;
            }
            for (var enablement : watches.listEnablements(session)) {
                if (!enablement.enabled() || jobs.containsKey(new Key(enablement.watchId(), session)))
                    continue;
                var definition = repository.definitionFor(enablement.watchId()).orElse(null);
                if (definition == null)
                    continue;
                Instant due = enablement.lastEvaluationStartedAt() == null
                        ? activity.get().plusSeconds(definition.intervalSeconds())
                        : enablement.lastEvaluationStartedAt().plusSeconds(definition.intervalSeconds());
                if (!now.isBefore(due) && !activeStreams.hasActiveStreamForSession(session))
                    start(definition, session, now);
            }
        }
    }

    private void start(WatchService.Definition definition, long session, Instant tickNow) {
        Key key = new Key(definition.id(), session);
        if (!permits.tryAcquire())
            return;
        Job[] captured = new Job[1];
        try {
            coordinator.withLock(session, () -> {
                var activity = watches.qualifyingActivity(session);
                var enablement = repository.enablement(definition.id(), session).orElse(null);
                var current = repository.definitionFor(definition.id()).orElse(null);
                if (enablement == null || !enablement.enabled() || current == null
                        || current.configVersion() != definition.configVersion() || activity.isEmpty()
                        || !tickNow.isBefore(activity.get().plus(DORMANT))
                        || activeStreams.hasActiveStreamForSession(session))
                    return;
                Instant due = enablement.lastEvaluationStartedAt() == null
                        ? activity.get().plusSeconds(current.intervalSeconds())
                        : enablement.lastEvaluationStartedAt().plusSeconds(current.intervalSeconds());
                if (tickNow.isBefore(due))
                    return;
                var job = new Job(new CancellationToken(), current.configVersion(), enablement.activityGeneration(),
                        activity.get(), coordinator.snapshot(session).version());
                if (jobs.putIfAbsent(key, job) != null)
                    return;
                var run = repository.startRun(current, session, tickNow, job.generation);
                if (run.isEmpty()) {
                    jobs.remove(key, job);
                    return;
                }
                job.runId.set(run.get());
                captured[0] = job;
            });
        } catch (RuntimeException e) {
            permits.release();
            throw e;
        }
        Job job = captured[0];
        if (job == null) {
            permits.release();
            return;
        }
        try {
            job.timeout.set(timeouts.schedule(() -> cancelJob(job), MAX_EVALUATION.toSeconds(), TimeUnit.SECONDS));
            executor.submit(() -> {
                job.thread.set(Thread.currentThread());
                evaluate(key, job, definition, session);
            });
        } catch (RuntimeException e) {
            ScheduledFuture<?> timeout = job.timeout.getAndSet(null);
            if (timeout != null)
                timeout.cancel(false);
            repository.finishRun(job.runId.get(), "FAILED", null, null, e.toString(), false, null);
            jobs.remove(key, job);
            permits.release();
            throw e;
        }
    }

    private void evaluate(Key key, Job job, WatchService.Definition definition, long session) {
        try {
            job.token.throwIfCancelled();
            AgentDefinition evaluator = agents.getRequired(definition.evaluatorAgentId());
            if (evaluator.mode() != AgentMode.AGENT)
                throw new IllegalArgumentException("Evaluator must be a primary agent");
            String root = state.loadSessionDetail(session).workspaceRoot();
            String prompt = "Inspect the workspace using read-only tools and evaluate this watch. Return exactly JSON, no markdown: {\"actionable\":true|false}. Watch: "
                    + definition.prompt();
            var result = harness.runTurn(
                    new AgentTurnRequest(null, List.of(new Message(Message.Role.USER, prompt, null, null, null)), root,
                            evaluator.id(), null, evaluator.defaultThinkingLevel(), session, job.token,
                            Set.of("list_files", "read_file", "search_code", "display_image")));
            job.token.throwIfCancelled();
            String output = result.getFinalText();
            if (!watches.parseActionable(output)) {
                repository.finishRun(job.runId.get(), "FAILED", null, output,
                        "Evaluator did not return strict actionable JSON", false, null);
                return;
            }
            if (!watches.actionableValue(output)) {
                repository.finishRun(job.runId.get(), "NONACTIONABLE", false, output, null, false, null);
                return;
            }
            var current = repository.definitionFor(definition.id()).orElse(null);
            var command = current == null
                    ? null
                    : commands.list(Path.of(root)).stream().filter(c -> c.id().equals(current.actionCommandId())
                            && c.type() == CommandCatalogService.CommandKind.PROMPT).findFirst().orElse(null);
            var chat = isStillEligible(job, definition, session, current)
                    ? actions.start(session, command, definition.actionAgentId(), job.activityVersion,
                            () -> isStillEligible(job, definition, session,
                                    repository.definitionFor(definition.id()).orElse(null)),
                            job.runId.get())
                    : Optional.<String>empty();
            repository.finishRun(job.runId.get(), chat.isPresent() ? "DISPATCHED" : "CANCELLED", true, output,
                    chat.isEmpty() ? "Admission rejected" : null, chat.isPresent(), chat.orElse(null));
        } catch (StreamCancelledException e) {
            repository.finishRun(job.runId.get(), "CANCELLED", null, null, "Evaluator cancelled", false, null);
        } catch (CancellationException e) {
            repository.finishRun(job.runId.get(), "CANCELLED", null, null, "Evaluator cancelled", false, null);
        } catch (Exception e) {
            if (job.token.isCancelled() || Thread.currentThread().isInterrupted()) {
                repository.finishRun(job.runId.get(), "CANCELLED", null, null, "Evaluator cancelled", false, null);
                return;
            }
            log.error("Watch evaluator failed for run {}", job.runId.get(), e);
            repository.finishRun(job.runId.get(), "FAILED", null, null, e.toString(), false, null);
        } finally {
            ScheduledFuture<?> timeout = job.timeout.getAndSet(null);
            if (timeout != null)
                timeout.cancel(false);
            permits.release();
            jobs.remove(key, job);
        }
    }

    private boolean isStillEligible(Job job, WatchService.Definition definition, long session,
            WatchService.Definition current) {
        var activity = watches.qualifyingActivity(session);
        var enablement = repository.enablement(definition.id(), session).orElse(null);
        return !job.token.isCancelled() && coordinator.snapshot(session).version() == job.activityVersion
                && current != null && current.configVersion() == job.configVersion && enablement != null
                && enablement.enabled() && enablement.activityGeneration() == job.generation && activity.isPresent()
                && activity.get().equals(job.activityTimestamp) && Instant.now().isBefore(activity.get().plus(DORMANT));
    }

    private void cancelJob(Job job) {
        job.token.cancel();
        Thread thread = job.thread.get();
        if (thread != null)
            thread.interrupt();
    }
    private void cancelSession(long session) {
        jobs.forEach((key, job) -> {
            if (key.sessionId == session)
                cancelJob(job);
        });
    }

    @PreDestroy
    void shutdown() {
        shuttingDown.set(true);
        jobs.values().forEach(this::cancelJob);
        timeouts.shutdownNow();
        executor.shutdownNow();
    }
    private record Key(long watchId, long sessionId) {
    }
    private static final class Job {
        final CancellationToken token;
        final int configVersion;
        final long generation;
        final Instant activityTimestamp;
        final long activityVersion;
        final AtomicLong runId = new AtomicLong();
        final AtomicReference<ScheduledFuture<?>> timeout = new AtomicReference<>();
        final AtomicReference<Thread> thread = new AtomicReference<>();
        Job(CancellationToken token, int configVersion, long generation, Instant activityTimestamp,
                long activityVersion) {
            this.token = token;
            this.configVersion = configVersion;
            this.generation = generation;
            this.activityTimestamp = activityTimestamp;
            this.activityVersion = activityVersion;
        }
    }
}
