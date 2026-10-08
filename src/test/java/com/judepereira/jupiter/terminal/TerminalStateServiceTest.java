package com.judepereira.jupiter.terminal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class TerminalStateServiceTest {
    @Test
    void openingWatchesClosesTerminalButRetainsTerminalState() {
        var service = new TerminalStateService();
        service.registerTerminal(9, new TerminalHandle("term-1", "Shell"));

        var watches = service.openWatchesPane(9);
        assertThat(watches.bottomPanelMode()).isEqualTo("watches");
        assertThat(watches.terminalTabs()).hasSize(1);
        assertThat(watches.activeTerminal()).isNotNull();
        assertThat(watches.terminalPanelOpen()).isFalse();

        var terminal = service.openTerminalPane(9);
        assertThat(terminal.bottomPanelMode()).isEqualTo("terminal");
        assertThat(terminal.activeTerminal().id()).isEqualTo("term-1");
        assertThat(terminal.terminalPanelOpen()).isTrue();
    }

    @Test
    void closingWatchesOnlyClosesWatchesPanel() {
        var service = new TerminalStateService();
        service.openWatchesPane(9);
        assertThat(service.closeWatchesPane(9).bottomPanelMode()).isEqualTo("none");
        assertThat(service.closeWatchesPane(9).bottomPanelMode()).isEqualTo("none");
    }

    @Test
    void unknownTerminalCannotBeActivatedOrClosed() {
        var service = new TerminalStateService();
        assertThatThrownBy(() -> service.activateTerminal(9, "missing")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.closeTerminal(9, "missing")).isInstanceOf(IllegalStateException.class);
    }
}
