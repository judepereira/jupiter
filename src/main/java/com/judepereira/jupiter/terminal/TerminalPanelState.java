package com.judepereira.jupiter.terminal;

import java.util.List;

public record TerminalPanelState(String bottomPanelMode, List<TerminalTab> terminalTabs, TerminalTab activeTerminal, boolean bottomPanelOpen) {

    public String panelMode() {
        return bottomPanelMode;
    }

    public boolean terminalPanelOpen() {
        return bottomPanelOpen;
    }
}
