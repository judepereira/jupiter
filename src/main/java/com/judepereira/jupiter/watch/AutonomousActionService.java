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
        String[] assistantId = new String[1];
        boolean admitted = activityCoordinator.admit(sessionId, expectedActivityVersion, () -> {
            try {
                Boolean committed = transactionTemplate.execute(status -> {
                    if (!validation.getAsBoolean()) {
                        status.setRollbackOnly();
                        return false;
                    }
                    assistantId[0] = uiController.prepareRegisteredStream(sessionId, workspaceRoot, agent, command);
                    if (!repository.markDispatched(runId, assistantId[0])) {
                        status.setRollbackOnly();
                        return false;
                    }
                    return true;
                });
                if (!Boolean.TRUE.equals(committed)) {
                    uiController.discardRegisteredStream(assistantId[0]);
                    assistantId[0] = null;
                }
            } catch (RuntimeException failure) {
                uiController.discardRegisteredStream(assistantId[0]);
                throw failure;
            }
        });
        if (!admitted || assistantId[0] == null) {
            return Optional.empty();
        }
        try {
            uiController.startRegisteredStream(assistantId[0]);
            return Optional.of(assistantId[0]);
        } catch (RuntimeException failure) {
            uiController.discardRegisteredStream(assistantId[0]);
            throw failure;
        }
    }
}
