package com.judepereira.jupiter.watch;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.judepereira.jupiter.agent.catalog.AgentDefinitionService;
import com.judepereira.jupiter.agent.catalog.AgentMode;
import com.judepereira.jupiter.command.CommandCatalogService;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.LongConsumer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class WatchService {
    private static final int MIN_INTERVAL = 60;
    private static final int MAX_INTERVAL = 86400;
    private final WatchRepository repository;
    private final CommandCatalogService commands;
    private final ObjectMapper objectMapper;
    private final AgentDefinitionService agents;
    private final CopyOnWriteArrayList<LongConsumer> changeListeners = new CopyOnWriteArrayList<>();

    public void addChangeListener(LongConsumer listener) {
        changeListeners.add(listener);
    }

    private void notifyChange(long watchId) {
        repository.enablementsForWatch(watchId)
                .forEach(e -> changeListeners.forEach(listener -> listener.accept(e.sessionId())));
    }

    public record Definition(long id, long projectId, String name, String prompt, int intervalSeconds,
            String evaluatorAgentId, String actionAgentId, String actionCommandId, int configVersion) {
    }
    public record Enablement(long watchId, long sessionId, boolean enabled, long activityGeneration,
            Instant lastEvaluationStartedAt) {
    }
    public record Run(long id, Long watchId, long sessionId, Instant startedAt, Instant finishedAt, String status,
            Boolean actionable, boolean dispatched, String error, String watchName) {
    }

    public List<Definition> listDefinitions(long projectId) {
        return repository.definitions(projectId);
    }
    public List<Enablement> listEnablements(long sessionId) {
        return repository.enablements(sessionId);
    }
    public List<Run> latestRuns(long sessionId) {
        return repository.runs(sessionId);
    }

    @Transactional
    public Definition create(long projectId, String name, String prompt, int intervalSeconds, String evaluatorAgentId,
            String actionAgentId, String actionCommandId) {
        validate(name, prompt, intervalSeconds, evaluatorAgentId, actionAgentId, actionCommandId);
        long id = repository.insert(new Definition(0, projectId, name, prompt, intervalSeconds, evaluatorAgentId,
                actionAgentId, actionCommandId, 1), Instant.now());
        return repository.definition(id).orElseThrow();
    }
    @Transactional
    public Definition update(Definition definition) {
        validate(definition.name(), definition.prompt(), definition.intervalSeconds(), definition.evaluatorAgentId(),
                definition.actionAgentId(), definition.actionCommandId());
        repository.update(definition);
        notifyChange(definition.id());
        return repository.definition(definition.id()).orElseThrow();
    }
    @Transactional
    public void delete(long id) {
        Set<Long> sessionIds = new LinkedHashSet<>(
                repository.enablementsForWatch(id).stream().map(Enablement::sessionId).toList());
        repository.delete(id);
        sessionIds.forEach(sessionId -> changeListeners.forEach(listener -> listener.accept(sessionId)));
    }
    @Transactional
    public void enable(long watchId, long sessionId) {
        validateSessionBelongsToWatchProject(watchId, sessionId);
        repository.enable(watchId, sessionId);
        changeListeners.forEach(listener -> listener.accept(sessionId));
    }
    @Transactional
    public void disable(long watchId, long sessionId) {
        validateSessionBelongsToWatchProject(watchId, sessionId);
        repository.disable(watchId, sessionId);
        changeListeners.forEach(listener -> listener.accept(sessionId));
    }

    private void validateSessionBelongsToWatchProject(long watchId, long sessionId) {
        var definition = repository.definition(watchId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown watch"));
        var session = repository.sessionProjectId(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown session"));
        if (definition.projectId() != session) {
            throw new IllegalArgumentException("Watch and session must belong to the same project");
        }
    }
    public void qualifyingUserActivity(long sessionId) {
        repository.activity(sessionId);
    }
    public long activityGeneration(long watchId, long sessionId) {
        return repository.activityGeneration(watchId, sessionId);
    }
    public Optional<Instant> qualifyingActivity(long sessionId) {
        return repository.qualifyingActivity(sessionId);
    }

    public boolean parseActionable(String raw) {
        try (JsonParser parser = objectMapper.getFactory().createParser(raw)) {
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            JsonNode node = objectMapper.readTree(parser);
            return node != null && parser.nextToken() == null && node.isObject() && node.size() == 1
                    && node.has("actionable") && node.get("actionable").isBoolean();
        } catch (Exception ignored) {
            return false;
        }
    }
    public boolean actionableValue(String raw) {
        try (JsonParser parser = objectMapper.getFactory().createParser(raw)) {
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            JsonNode node = objectMapper.readTree(parser);
            if (parser.nextToken() != null || node == null || !node.isObject() || node.size() != 1
                    || !node.has("actionable") || !node.get("actionable").isBoolean()) {
                throw new IllegalArgumentException("Invalid actionable JSON");
            }
            return node.get("actionable").booleanValue();
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid actionable JSON", e);
        }
    }
    private void validate(String name, String prompt, int interval, String evaluator, String action, String command) {
        if (name == null || name.isBlank() || prompt == null || prompt.isBlank() || evaluator == null
                || evaluator.isBlank() || action == null || action.isBlank() || command == null || command.isBlank())
            throw new IllegalArgumentException("Watch fields are required");
        if (interval < MIN_INTERVAL || interval > MAX_INTERVAL)
            throw new IllegalArgumentException("Watch interval must be 60..86400 seconds");
        if (agents.getRequired(evaluator).mode() != AgentMode.AGENT
                || agents.getRequired(action).mode() != AgentMode.AGENT)
            throw new IllegalArgumentException("Watch agents must be primary agents");
        if (!commands.list().stream()
                .anyMatch(c -> c.id().equals(command) && c.type() == CommandCatalogService.CommandKind.PROMPT)
                && !commands.listCustom().stream()
                        .anyMatch(c -> c.id().equals(command) && c.type() == CommandCatalogService.CommandKind.PROMPT))
            throw new IllegalArgumentException("Watch action must be a PROMPT command");
    }
}
