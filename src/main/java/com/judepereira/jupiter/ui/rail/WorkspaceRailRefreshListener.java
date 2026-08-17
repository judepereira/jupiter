package com.judepereira.jupiter.ui.rail;

import com.judepereira.jupiter.persistence.WorkspaceRailRefreshEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
public class WorkspaceRailRefreshListener {

    private final WorkspaceRailRefreshService workspaceRailRefreshService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onWorkspaceRailRefresh(WorkspaceRailRefreshEvent event) {
        workspaceRailRefreshService.publishWorkspaceRailRefresh();
    }
}
