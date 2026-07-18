package com.judepereira.jupiter2.terminal;

import java.util.List;

public record TerminalPanelState(String panelMode, List<TerminalTab> terminalTabs, TerminalTab activeTerminal, boolean terminalPanelOpen) {}
