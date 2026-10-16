package com.judepereira.jupiter.watch;

import com.judepereira.jupiter.agent.catalog.AgentDefinition;
import com.judepereira.jupiter.agent.catalog.AgentDefinitionService;
import com.judepereira.jupiter.agent.catalog.AgentMode;
import com.judepereira.jupiter.command.CommandCatalogService.CommandDefinition;
import com.judepereira.jupiter.command.CommandCatalogService.CommandKind;
import com.judepereira.jupiter.persistence.AppStateService;
import com.judepereira.jupiter.ui.UiController;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Admits autonomous turns through the same stream and persistence path as chat.
 */
@Service
@RequiredArgsConstructor
public class AutonomousActionService {
    private final AppStateService appStateService;
    private final AgentDefinitionService agentDefinitionService;
    private final SessionActivityCoordinator activityCoordinator;
    private final UiController uiController;
    private final WatchRepository repository;
    private final TransactionTemplate transactionTemplate;

    public Optional<String> start(long sessionId, CommandDefinition command, String actionAgentId,
            long expectedActivityVersion, BooleanSupplier validation, long runId) {
        if (command == null || command.type() != CommandKind.PROMPT) {
            throw new IllegalArgumentException("Autonomous action must use a PROMPT command");
        }
        AgentDefinition agent = agentDefinitionService.getRequired(actionAgentId);
        if (agent.mode() != AgentMode.AGENT) {
            throw new IllegalArgumentException("Autonomous action agent must be a primary agent: " + actionAgentId);
        }
        String workspaceRoot = appStateService.loadSessionDetail(sessionId).workspaceRoot();
        UiController.RegisteredStream[] registration = new UiController.RegisteredStream[1];
        boolean admitted = activityCoordinator.admit(sessionId, expectedActivityVersion, () -> {
            try {
                Boolean committed = transactionTemplate.execute(status -> {
                    if (!validation.getAsBoolean()) {
                        status.setRollbackOnly();
                        return false;
                    }
                    registration[0] = uiController.prepareRegisteredStream(sessionId, workspaceRoot, agent, command);
                    if (!repository.markDispatched(runId, registration[0].assistantId())) {
                        status.setRollbackOnly();
                        return false;
                    }
                    return true;
                });
                if (!Boolean.TRUE.equals(committed)) {
                    uiController
                            .discardRegisteredStream(registration[0] == null ? null : registration[0].assistantId());
                    registration[0] = null;
                }
            } catch (RuntimeException failure) {
                uiController.discardRegisteredStream(registration[0] == null ? null : registration[0].assistantId());
                throw failure;
            }
        });
        if (!admitted || registration[0] == null) {
            return Optional.empty();
        }
        try {
            uiController.startRegisteredStream(registration[0]);
            return Optional.of(registration[0].assistantId());
        } catch (RuntimeException failure) {
            uiController.discardRegisteredStream(registration[0].assistantId());
            throw failure;
        }
    }
}
