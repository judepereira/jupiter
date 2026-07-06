package com.judepereira.jupiter2;

import com.judepereira.jupiter2.agent.harness.AgentTurnRequest;
import com.judepereira.jupiter2.agent.harness.AgentTurnResult;
import com.judepereira.jupiter2.agent.harness.CodingAgentHarness;
import com.judepereira.jupiter2.agent.harness.ToolCallTrace;
import com.judepereira.jupiter2.ui.UiController;
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
    public void pollSetsReviewOobAfterSuccessfulWriteFile() throws Exception {
        // prepare a harness that returns a write_file trace
        CodingAgentHarness fake = new CodingAgentHarness(null, null, null) {
            @Override
            public AgentTurnResult runTurn(AgentTurnRequest request) {
                ToolCallTrace t = new ToolCallTrace("write_file", Map.of("path", "out.txt"), true, "wrote", Map.of("path", "out.txt"));
                return new AgentTurnResult("done", List.of(t));
            }
        };

        var props = new com.judepereira.jupiter2.agent.config.AgentProperties();
        Path tmp = Files.createTempDirectory("jup-test-ws");
        props.setWorkspaceRoot(tmp.toString());
        UiController ctrl = new UiController(fake, props);

        // send a message to kick off background run
        Model m1 = new ConcurrentModel();
        ctrl.sendMessage("do it", m1, null);

        // wait for background to finish
        Thread.sleep(300);

        Model pollModel = new ConcurrentModel();
        String out = ctrl.pollChat(pollModel);
        // should be composite
        assertThat(out).contains("fragments/chat-response :: response");
        Boolean reviewOob = (Boolean) ((ConcurrentModel)pollModel).getAttribute("reviewOob");
        assertThat(reviewOob).isTrue();
    }
}
