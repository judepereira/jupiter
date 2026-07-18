package com.judepereira.jupiter2.terminal;

import java.util.List;

public record TerminalPanelState(String bottomPanelMode, List<TerminalTab> terminalTabs, TerminalTab activeTerminal, boolean bottomPanelOpen) {

    public String panelMode() {
        return bottomPanelMode;
    }

    public boolean terminalPanelOpen() {
        return bottomPanelOpen;
    }
}
