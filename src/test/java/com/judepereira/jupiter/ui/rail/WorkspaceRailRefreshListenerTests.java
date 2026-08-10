package com.judepereira.jupiter.ui.rail;

import com.judepereira.jupiter.persistence.WorkspaceRailRefreshEvent;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class WorkspaceRailRefreshListenerTests {

    @Test
    void workspaceRailRefreshDelegatesToService() {
        WorkspaceRailRefreshService refreshService = mock(WorkspaceRailRefreshService.class);
        WorkspaceRailRefreshListener listener = new WorkspaceRailRefreshListener(refreshService);

        listener.onWorkspaceRailRefresh(new WorkspaceRailRefreshEvent());

        verify(refreshService).publishWorkspaceRailRefresh();
    }
}
