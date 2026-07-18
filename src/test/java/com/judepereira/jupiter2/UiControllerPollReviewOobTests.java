package com.judepereira.jupiter2;

import com.judepereira.jupiter2.agent.harness.AgentTurnRequest;
import com.judepereira.jupiter2.agent.harness.AgentTurnResult;
import com.judepereira.jupiter2.agent.harness.CodingAgentHarness;
import com.judepereira.jupiter2.agent.harness.ToolCallTrace;
import com.judepereira.jupiter2.ui.UiController;
import com.judepereira.jupiter2.persistence.TestAppStateSupport;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public class UiControllerPollReviewOobTests {

    @Test
    public void streamingCompletion_setsReviewAndChangedFiles() throws Exception {
        // prepare a harness that emits a write_file trace on completion
        CodingAgentHarness fake = new CodingAgentHarness(null, null, null) {
            @Override
            public AgentTurnResult runTurnStreaming(AgentTurnRequest request, com.judepereira.jupiter2.agent.llm.AgentStreamListener listener) {
                // simulate some streaming deltas
                listener.onTextDelta("do");
                listener.onTextDelta("ne");
                ToolCallTrace t = new ToolCallTrace("tool-1-0", "write_file", Map.of("path", "out.txt"), true, "wrote", Map.of("path", "out.txt"));
                AgentTurnResult res = new AgentTurnResult("done", List.of(t));
                listener.onComplete(res);
                return res;
            }
        };

        var props = new com.judepereira.jupiter2.agent.config.AgentProperties();
        Path tmp = Files.createTempDirectory("jup-test-ws");
        props.setWorkspaceRoot(tmp.toString());
        UiController ctrl = TestAppStateSupport.controller(fake, props);

        // send a message to register pending
        Model m1 = new ConcurrentModel();
        ctrl.sendMessage("do it", m1, null);

        // find assistant id from model
        List<?> msgs = (List<?>) ((ConcurrentModel)m1).getAttribute("chatMessages");
        Object last = msgs.get(msgs.size()-1);
        String assistantId = null;
        // attempt to extract id via toString or reflection
        try {
            var cls = last.getClass();
            var f = cls.getDeclaredField("id");
            f.setAccessible(true);
            assistantId = (String) f.get(last);
        } catch (Exception e) {
            // fallback to toString parsing
            String s = last.toString();
            int i = s.indexOf("id=");
            if (i >= 0) {
                // hyphen placed at the end of the character class doesn't need escaping
                assistantId = s.substring(i+3).replaceAll("[^a-zA-Z0-9-]", "");
            }
        }
        assertThat(assistantId).isNotNull();

        // call stream endpoint which will run same-thread executor in test constructor
        var emitter = ctrl.streamChat(assistantId);
        assertThat(emitter).isNotNull();

        // after streaming completes, index should reflect changed files and review open
        Model m2 = new ConcurrentModel();
        ctrl.index(m2);
        Boolean reviewOpen = (Boolean) ((ConcurrentModel)m2).getAttribute("reviewPanelOpen");
        assertThat(reviewOpen).isTrue();
        List<?> changed = (List<?>) ((ConcurrentModel)m2).getAttribute("changedFiles");
        assertThat(changed).isNotEmpty();
        // selected file should be set
        Object sel = ((ConcurrentModel)m2).getAttribute("selectedFile");
        assertThat(sel).isNotNull();
    }

    @Test
    public void toggleReviewKeepsResponseInBand() throws Exception {
        CodingAgentHarness fake = new CodingAgentHarness(null, null, null) {
            @Override
            public AgentTurnResult runTurnStreaming(AgentTurnRequest request, com.judepereira.jupiter2.agent.llm.AgentStreamListener listener) {
                return new AgentTurnResult("done", List.of());
            }
        };

        var props = new com.judepereira.jupiter2.agent.config.AgentProperties();
        props.setWorkspaceRoot(".");
        UiController ctrl = TestAppStateSupport.controller(fake, props);

        ctrl.addProject("p", Files.createTempDirectory("jup-toggle").toString(), new ConcurrentModel());

        Model model = new ConcurrentModel();
        String view = ctrl.toggleReview(model);

        assertThat(view).isEqualTo("fragments/review :: panel");
        assertThat(model.getAttribute("reviewOob")).isEqualTo(false);
    }
}
