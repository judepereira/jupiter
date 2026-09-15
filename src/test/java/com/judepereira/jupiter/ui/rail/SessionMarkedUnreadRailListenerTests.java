package com.judepereira.jupiter.ui.rail;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.judepereira.jupiter.persistence.SessionMarkedUnreadEvent;
import org.junit.jupiter.api.Test;

class SessionMarkedUnreadRailListenerTests {

    @Test
    void sessionMarkedUnreadTriggersWorkspaceRailRefresh() {
        WorkspaceRailRefreshService refreshService = mock(WorkspaceRailRefreshService.class);
        SessionMarkedUnreadRailListener listener = new SessionMarkedUnreadRailListener(refreshService);

        listener.onSessionMarkedUnread(new SessionMarkedUnreadEvent(42L));

        verify(refreshService).publishWorkspaceRailRefresh();
    }
}
