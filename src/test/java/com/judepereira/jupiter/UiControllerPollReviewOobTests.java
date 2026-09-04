package com.judepereira.jupiter;

import com.judepereira.jupiter.agent.harness.AgentTurnRequest;
import com.judepereira.jupiter.agent.harness.AgentTurnResult;
import com.judepereira.jupiter.agent.harness.CodingAgentHarness;
import com.judepereira.jupiter.agent.harness.ToolCallTrace;
import com.judepereira.jupiter.persistence.TestAppStateSupport;
import com.judepereira.jupiter.ui.UiController;
import com.judepereira.jupiter.ui.ChatPresentationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public class UiControllerPollReviewOobTests {

    @Test
    public void streamingCompletion_setsChangedFilesAndSelectionWithoutOpeningReviewPanel() throws Exception {
        // prepare a harness that emits a write_file trace on completion
        CodingAgentHarness fake = new CodingAgentHarness(null, null, null, null, null, null, null, null, new com.judepereira.jupiter.agent.harness.SystemPromptComposer(new com.judepereira.jupiter.agent.skill.SkillCatalogRenderer()), new com.judepereira.jupiter.agent.skill.SkillDiscoveryService(new com.judepereira.jupiter.agent.skill.SkillParser(), System.getProperty("user.home")), new com.judepereira.jupiter.agent.skill.SkillInvocationResolver(), new com.judepereira.jupiter.agent.skill.SkillContextInjector(new com.judepereira.jupiter.agent.skill.SkillParser())) {
            @Override
            public AgentTurnResult runTurnStreaming(AgentTurnRequest request, com.judepereira.jupiter.agent.llm.AgentStreamListener listener) {
                // simulate some streaming deltas
                listener.onTextDelta("do");
                listener.onTextDelta("ne");
                ToolCallTrace t = new ToolCallTrace("tool-1-0", "write_file", Map.of("path", "out.txt"), true, "wrote", Map.of("path", "out.txt"));
                AgentTurnResult res = new AgentTurnResult("done", List.of(t));
                listener.onComplete(res);
                return res;
            }
        };

        var props = new com.judepereira.jupiter.agent.config.AgentProperties();
        Path tmp = Files.createTempDirectory("jup-test-ws");
        props.setWorkspaceRoot(tmp.toString());
        UiController ctrl = TestAppStateSupport.controller(fake, props);

        // send a message to register pending
        Model m1 = new ConcurrentModel();
        ctrl.sendMessage("do it", m1, null);

        // find assistant id from model
        List<?> msgs = (List<?>) ((ConcurrentModel)m1).getAttribute("chatMessages");
        Object last = msgs.get(msgs.size()-1);
        String assistantId = ((ChatPresentationService.ChatMessage) last).id();
        assertThat(assistantId).isNotNull();

        // call stream endpoint which will run same-thread executor in test constructor
        var emitter = ctrl.streamChat(assistantId);
        assertThat(emitter).isNotNull();
        TestAppStateSupport.awaitAssistantCompletion(ctrl, assistantId);

        // wait for changed files and selection to be persisted before asserting
        ConcurrentModel m2 = TestAppStateSupport.awaitChangedFilesAndSelection(ctrl);
        Boolean reviewOpen = (Boolean) m2.getAttribute("reviewPanelOpen");
        assertThat(reviewOpen).isFalse();
        List<?> changed = (List<?>) m2.getAttribute("changedFiles");
        assertThat(changed).isNotEmpty();
        // selected file should be set
        Object sel = m2.getAttribute("selectedFile");
        assertThat(sel).isNotNull();
    }

    @Test
    public void toggleReviewKeepsResponseInBand() throws Exception {
        CodingAgentHarness fake = new CodingAgentHarness(null, null, null, null, null, null, null, null, new com.judepereira.jupiter.agent.harness.SystemPromptComposer(new com.judepereira.jupiter.agent.skill.SkillCatalogRenderer()), new com.judepereira.jupiter.agent.skill.SkillDiscoveryService(new com.judepereira.jupiter.agent.skill.SkillParser(), System.getProperty("user.home")), new com.judepereira.jupiter.agent.skill.SkillInvocationResolver(), new com.judepereira.jupiter.agent.skill.SkillContextInjector(new com.judepereira.jupiter.agent.skill.SkillParser())) {
            @Override
            public AgentTurnResult runTurnStreaming(AgentTurnRequest request, com.judepereira.jupiter.agent.llm.AgentStreamListener listener) {
                return new AgentTurnResult("done", List.of());
            }
        };

        var props = new com.judepereira.jupiter.agent.config.AgentProperties();
        props.setWorkspaceRoot(".");
        UiController ctrl = TestAppStateSupport.controller(fake, props);

        ctrl.addProject("p", Files.createTempDirectory("jup-toggle").toString(), new ConcurrentModel());

        Model openModel = new ConcurrentModel();
        String openView = ctrl.toggleReview(openModel);

        Model closedModel = new ConcurrentModel();
        String closedView = ctrl.toggleReview(closedModel);

        assertThat(openView).isEqualTo("fragments/review :: panel");
        assertThat(openModel.getAttribute("reviewOob")).isEqualTo(false);
        assertThat(openModel.getAttribute("reviewPanelOpen")).isEqualTo(true);
        assertThat(closedView).isEqualTo("fragments/review :: panel");
        assertThat(closedModel.getAttribute("reviewOob")).isEqualTo(false);
        assertThat(closedModel.getAttribute("reviewPanelOpen")).isEqualTo(false);
    }

    @Test
    public void openingReviewPanelDoesNotCloseOpenTerminalBottomPanel(@TempDir Path workspaceRoot) throws Exception {
        CodingAgentHarness fake = new CodingAgentHarness(null, null, null, null, null, null, null, null, new com.judepereira.jupiter.agent.harness.SystemPromptComposer(new com.judepereira.jupiter.agent.skill.SkillCatalogRenderer()), new com.judepereira.jupiter.agent.skill.SkillDiscoveryService(new com.judepereira.jupiter.agent.skill.SkillParser(), System.getProperty("user.home")), new com.judepereira.jupiter.agent.skill.SkillInvocationResolver(), new com.judepereira.jupiter.agent.skill.SkillContextInjector(new com.judepereira.jupiter.agent.skill.SkillParser())) {
            @Override
            public AgentTurnResult runTurnStreaming(AgentTurnRequest request, com.judepereira.jupiter.agent.llm.AgentStreamListener listener) {
                return new AgentTurnResult("done", List.of());
            }
        };

        var props = new com.judepereira.jupiter.agent.config.AgentProperties();
        props.setWorkspaceRoot(workspaceRoot.toString());
        UiController ctrl = TestAppStateSupport.controller(fake, props);

        ctrl.addProject("p", Files.createTempDirectory(workspaceRoot, "project").toString(), new ConcurrentModel());
        ctrl.openTerminalPanel(new ConcurrentModel());

        Model model = new ConcurrentModel();
        String view = ctrl.openReviewPanel(model);

        Model closedModel = new ConcurrentModel();
        String closedView = ctrl.toggleReview(closedModel);

        assertThat(view).isEqualTo("fragments/review :: panel");
        assertThat(model.getAttribute("reviewPanelOpen")).isEqualTo(true);
        assertThat(model.getAttribute("bottomPanelMode")).isEqualTo("terminal");
        assertThat(model.getAttribute("bottomPanelOpen")).isEqualTo(true);
        assertThat(model.getAttribute("activeTerminal")).isNotNull();
        assertThat(((com.judepereira.jupiter.terminal.TerminalTab) model.getAttribute("activeTerminal")).id()).isEqualTo("terminal-1");
        assertThat(closedView).isEqualTo("fragments/review :: panel");
        assertThat(closedModel.getAttribute("reviewPanelOpen")).isEqualTo(false);
        assertThat(closedModel.getAttribute("bottomPanelMode")).isEqualTo("terminal");
        assertThat(closedModel.getAttribute("bottomPanelOpen")).isEqualTo(true);
        assertThat(closedModel.getAttribute("activeTerminal")).isNotNull();
    }
}
