package com.judepereira.jupiter2;

import com.judepereira.jupiter2.agent.config.AgentProperties;
import com.judepereira.jupiter2.agent.harness.CodingAgentHarness;
import com.judepereira.jupiter2.persistence.TestAppStateSupport;
import com.judepereira.jupiter2.terminal.TerminalHandle;
import com.judepereira.jupiter2.terminal.TerminalManager;
import com.judepereira.jupiter2.terminal.TerminalStateService;
import com.judepereira.jupiter2.terminal.TerminalTab;
import com.judepereira.jupiter2.ui.UiController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ui.ConcurrentModel;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class UiControllerTerminalTests {

    @Test
    public void openingTerminalPanelCreatesInitialTerminalTab(@TempDir Path workspaceRoot) {
        TestContext context = newContext(workspaceRoot);
        UiController controller = context.controller();

        controller.addProject("Alpha", workspaceRoot.toString(), new ConcurrentModel());

        ConcurrentModel model = new ConcurrentModel();
        String view = controller.openTerminalPanel(model);

        assertThat(view).isEqualTo("fragments/terminal :: panel");
        assertThat(terminalTabs(model)).extracting(TerminalTab::title, TerminalTab::active)
                .containsExactly(org.assertj.core.api.Assertions.tuple("Terminal 1", true));
        assertThat(activeTerminal(model).id()).isEqualTo("terminal-1");
        assertThat(bottomPanelMode(model)).isEqualTo("terminal");
        assertThat(bottomPanelOpen(model)).isTrue();
        verify(context.terminalManager()).createTerminal(workspaceRoot.toAbsolutePath().normalize().toString());
    }

    @Test
    public void openingTerminalPanelTogglesClosedAndReopensExistingTabs(@TempDir Path workspaceRoot) {
        TestContext context = newContext(workspaceRoot);
        UiController controller = context.controller();

        controller.addProject("Alpha", workspaceRoot.toString(), new ConcurrentModel());

        controller.openTerminalPanel(new ConcurrentModel());

        ConcurrentModel closedModel = new ConcurrentModel();
        controller.openTerminalPanel(closedModel);

        assertThat(terminalTabs(closedModel)).extracting(TerminalTab::title, TerminalTab::active)
                .containsExactly(org.assertj.core.api.Assertions.tuple("Terminal 1", true));
        assertThat(activeTerminal(closedModel).id()).isEqualTo("terminal-1");
        assertThat(bottomPanelMode(closedModel)).isEqualTo("none");
        assertThat(bottomPanelOpen(closedModel)).isFalse();

        ConcurrentModel reopenedModel = new ConcurrentModel();
        controller.openTerminalPanel(reopenedModel);

        assertThat(terminalTabs(reopenedModel)).extracting(TerminalTab::title, TerminalTab::active)
                .containsExactly(org.assertj.core.api.Assertions.tuple("Terminal 1", true));
        assertThat(activeTerminal(reopenedModel).id()).isEqualTo("terminal-1");
        assertThat(bottomPanelMode(reopenedModel)).isEqualTo("terminal");
        assertThat(bottomPanelOpen(reopenedModel)).isTrue();
        verify(context.terminalManager()).createTerminal(workspaceRoot.toAbsolutePath().normalize().toString());
    }

    @Test
    public void creatingNewTerminalAddsTabAndActivatesNewest(@TempDir Path workspaceRoot) {
        TestContext context = newContext(workspaceRoot);
        UiController controller = context.controller();

        controller.addProject("Alpha", workspaceRoot.toString(), new ConcurrentModel());
        controller.openTerminalPanel(new ConcurrentModel());

        ConcurrentModel model = new ConcurrentModel();
        String view = controller.newTerminal(model);

        assertThat(view).isEqualTo("fragments/terminal :: panel");
        assertThat(terminalTabs(model)).extracting(TerminalTab::title, TerminalTab::active)
                .containsExactly(
                        org.assertj.core.api.Assertions.tuple("Terminal 1", false),
                        org.assertj.core.api.Assertions.tuple("Terminal 2", true));
        assertThat(activeTerminal(model).id()).isEqualTo("terminal-2");
        assertThat(bottomPanelMode(model)).isEqualTo("terminal");
        assertThat(bottomPanelOpen(model)).isTrue();
        verify(context.terminalManager(), times(2)).createTerminal(workspaceRoot.toAbsolutePath().normalize().toString());
    }

    @Test
    public void activatingTerminalSwitchesActiveId(@TempDir Path workspaceRoot) {
        TestContext context = newContext(workspaceRoot);
        UiController controller = context.controller();

        controller.addProject("Alpha", workspaceRoot.toString(), new ConcurrentModel());
        controller.openTerminalPanel(new ConcurrentModel());
        controller.newTerminal(new ConcurrentModel());

        ConcurrentModel model = new ConcurrentModel();
        controller.activateTerminal("terminal-1", model);

        assertThat(activeTerminal(model).id()).isEqualTo("terminal-1");
        assertThat(terminalTabs(model)).extracting(TerminalTab::id, TerminalTab::active)
                .containsExactly(
                        org.assertj.core.api.Assertions.tuple("terminal-1", true),
                        org.assertj.core.api.Assertions.tuple("terminal-2", false));
        assertThat(bottomPanelMode(model)).isEqualTo("terminal");
        assertThat(bottomPanelOpen(model)).isTrue();
    }

    @Test
    public void closingNonLastTerminalLeavesPaneOpenWithRemainingActiveTab(@TempDir Path workspaceRoot) {
        TestContext context = newContext(workspaceRoot);
        UiController controller = context.controller();

        controller.addProject("Alpha", workspaceRoot.toString(), new ConcurrentModel());
        controller.openTerminalPanel(new ConcurrentModel());
        controller.newTerminal(new ConcurrentModel());

        ConcurrentModel model = new ConcurrentModel();
        String view = controller.closeTerminal("terminal-2", model);

        assertThat(view).isEqualTo("fragments/terminal :: panel");
        assertThat(terminalTabs(model)).extracting(TerminalTab::id, TerminalTab::active)
                .containsExactly(org.assertj.core.api.Assertions.tuple("terminal-1", true));
        assertThat(activeTerminal(model).id()).isEqualTo("terminal-1");
        assertThat(bottomPanelMode(model)).isEqualTo("terminal");
        assertThat(bottomPanelOpen(model)).isTrue();
        verify(context.terminalManager()).closeTerminal("terminal-2");
    }

    @Test
    public void closingLastTerminalClosesTerminalPane(@TempDir Path workspaceRoot) {
        TestContext context = newContext(workspaceRoot);
        UiController controller = context.controller();

        controller.addProject("Alpha", workspaceRoot.toString(), new ConcurrentModel());
        controller.openTerminalPanel(new ConcurrentModel());

        ConcurrentModel model = new ConcurrentModel();
        String view = controller.closeTerminal("terminal-1", model);

        assertThat(view).isEqualTo("fragments/terminal :: panel");
        assertThat(terminalTabs(model)).isEmpty();
        assertThat(activeTerminal(model)).isNull();
        assertThat(bottomPanelMode(model)).isEqualTo("none");
        assertThat(bottomPanelOpen(model)).isFalse();
        verify(context.terminalManager()).closeTerminal("terminal-1");
    }

    private static TestContext newContext(Path workspaceRoot) {
        TerminalManager terminalManager = mock(TerminalManager.class);
        AtomicInteger sequence = new AtomicInteger();
        when(terminalManager.createTerminal(anyString())).thenAnswer(invocation -> {
            int n = sequence.incrementAndGet();
            return new TerminalHandle("terminal-" + n, "Terminal " + n);
        });

        AgentProperties properties = new AgentProperties();
        properties.setWorkspaceRoot(workspaceRoot.toAbsolutePath().normalize().toString());

        return new TestContext(
                TestAppStateSupport.appStateService(),
                new TerminalStateService(),
                terminalManager,
                properties,
                Runnable::run);
    }

    @SuppressWarnings("unchecked")
    private static List<TerminalTab> terminalTabs(ConcurrentModel model) {
        return (List<TerminalTab>) model.getAttribute("terminalTabs");
    }

    private static TerminalTab activeTerminal(ConcurrentModel model) {
        return (TerminalTab) model.getAttribute("activeTerminal");
    }

    private static String bottomPanelMode(ConcurrentModel model) {
        return (String) model.getAttribute("bottomPanelMode");
    }

    private static boolean bottomPanelOpen(ConcurrentModel model) {
        return Boolean.TRUE.equals(model.getAttribute("bottomPanelOpen"));
    }

    private record TestContext(
            com.judepereira.jupiter2.persistence.AppStateService appStateService,
            TerminalStateService terminalStateService,
            TerminalManager terminalManager,
            AgentProperties properties,
            Executor executor) {

        private UiController controller() {
            return new UiController(mock(CodingAgentHarness.class), properties, appStateService, terminalManager, terminalStateService, executor);
        }
    }
}
