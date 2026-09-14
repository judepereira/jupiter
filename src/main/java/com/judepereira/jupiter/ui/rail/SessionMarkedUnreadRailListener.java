package com.judepereira.jupiter.ui.rail;

import com.judepereira.jupiter.persistence.SessionMarkedUnreadEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
public class SessionMarkedUnreadRailListener {

    private final WorkspaceRailRefreshService workspaceRailRefreshService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onSessionMarkedUnread(SessionMarkedUnreadEvent event) {
        workspaceRailRefreshService.publishWorkspaceRailRefresh();
    }
}
