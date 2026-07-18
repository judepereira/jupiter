package com.judepereira.jupiter2.terminal;

import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Log4j2
@Service
public class TerminalStateService implements TerminalManager.TerminalLifecycleListener {

    private final ConcurrentMap<Long, SessionState> states = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Long> terminalSessions = new ConcurrentHashMap<>();

    public TerminalPanelState snapshot(long sessionId) {
        SessionState state = states.get(sessionId);
        return state == null ? SessionState.emptySnapshot() : state.snapshot();
    }

    public TerminalPanelState openTerminalPane(long sessionId) {
        return state(sessionId).openTerminalPane();
    }

    public TerminalPanelState registerTerminal(long sessionId, TerminalHandle terminal) {
        terminalSessions.put(terminal.id(), sessionId);
        return state(sessionId).registerTerminal(terminal);
    }

    public TerminalPanelState activateTerminal(long sessionId, String terminalId) {
        return state(sessionId).activateTerminal(terminalId);
    }

    public TerminalPanelState closeTerminal(long sessionId, String terminalId) {
        terminalSessions.remove(terminalId);
        SessionState state = states.get(sessionId);
        return state == null ? SessionState.emptySnapshot() : state.closeTerminal(terminalId);
    }

    @Override
    public void onTerminalExited(String terminalId, int exitCode) {
        Long sessionId = terminalSessions.remove(terminalId);
        if (sessionId == null) {
            return;
        }
        log.info("Terminal {} exited with code {}", terminalId, exitCode);
        SessionState state = states.get(sessionId);
        if (state != null) {
            state.removeTerminal(terminalId);
        }
    }

    private SessionState state(long sessionId) {
        return states.computeIfAbsent(sessionId, ignored -> new SessionState());
    }

    private static final class SessionState {
        private String bottomPanelMode = "none";
        private String activeTerminalId;
        private int terminalSequence = 1;
        private final Map<String, String> terminals = new LinkedHashMap<>();

        private static TerminalPanelState emptySnapshot() {
            return new TerminalPanelState("none", List.of(), null, false);
        }

        private synchronized TerminalPanelState snapshot() {
            List<TerminalTab> tabs = new ArrayList<>(terminals.size());
            for (var entry : terminals.entrySet()) {
                tabs.add(new TerminalTab(entry.getKey(), entry.getValue(), entry.getKey().equals(activeTerminalId)));
            }
            TerminalTab active = activeTerminalId == null ? null : new TerminalTab(activeTerminalId, terminals.get(activeTerminalId), true);
            return new TerminalPanelState(bottomPanelMode, List.copyOf(tabs), active, "terminal".equals(bottomPanelMode) && active != null);
        }

        private synchronized TerminalPanelState openTerminalPane() {
            if (!terminals.isEmpty()) {
                bottomPanelMode = "terminal";
                if (activeTerminalId == null) {
                    activeTerminalId = terminals.keySet().iterator().next();
                }
            }
            return snapshot();
        }

        private synchronized TerminalPanelState registerTerminal(TerminalHandle terminal) {
            terminals.put(terminal.id(), terminal.title() == null || terminal.title().isBlank() ? nextTerminalTitle() : terminal.title());
            activeTerminalId = terminal.id();
            bottomPanelMode = "terminal";
            return snapshot();
        }

        private synchronized TerminalPanelState activateTerminal(String terminalId) {
            if (!terminals.containsKey(terminalId)) {
                throw new IllegalStateException("Unknown terminal: " + terminalId);
            }
            activeTerminalId = terminalId;
            bottomPanelMode = "terminal";
            return snapshot();
        }

        private synchronized TerminalPanelState closeTerminal(String terminalId) {
            if (!terminals.containsKey(terminalId)) {
                throw new IllegalStateException("Unknown terminal: " + terminalId);
            }
            removeTerminalInternal(terminalId);
            return snapshot();
        }

        private synchronized void removeTerminal(String terminalId) {
            if (!terminals.containsKey(terminalId)) {
                return;
            }
            removeTerminalInternal(terminalId);
        }

        private void removeTerminalInternal(String terminalId) {
            boolean removingActive = terminalId.equals(activeTerminalId);
            terminals.remove(terminalId);
            if (terminals.isEmpty()) {
                activeTerminalId = null;
                bottomPanelMode = "none";
                return;
            }
            if (removingActive || activeTerminalId == null) {
                activeTerminalId = terminals.keySet().iterator().next();
            }
        }

        private String nextTerminalTitle() {
            return "Terminal " + terminalSequence++;
        }
    }
}
