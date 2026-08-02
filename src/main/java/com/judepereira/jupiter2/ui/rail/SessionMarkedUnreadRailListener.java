package com.judepereira.jupiter2.ui.rail;

import com.judepereira.jupiter2.persistence.SessionMarkedUnreadEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.event.TransactionPhase;

@Component
@RequiredArgsConstructor
public class SessionMarkedUnreadRailListener {

    private final WorkspaceRailRefreshService workspaceRailRefreshService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSessionMarkedUnread(SessionMarkedUnreadEvent event) {
        workspaceRailRefreshService.publishWorkspaceRailRefresh();
    }
}
