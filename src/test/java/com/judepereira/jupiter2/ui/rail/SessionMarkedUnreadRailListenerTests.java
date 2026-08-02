package com.judepereira.jupiter2.ui.rail;

import com.judepereira.jupiter2.persistence.SessionMarkedUnreadEvent;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SessionMarkedUnreadRailListenerTests {

    @Test
    void sessionMarkedUnreadTriggersWorkspaceRailRefresh() {
        WorkspaceRailRefreshService refreshService = mock(WorkspaceRailRefreshService.class);
        SessionMarkedUnreadRailListener listener = new SessionMarkedUnreadRailListener(refreshService);

        listener.onSessionMarkedUnread(new SessionMarkedUnreadEvent(42L));

        verify(refreshService).publishWorkspaceRailRefresh();
    }
}
