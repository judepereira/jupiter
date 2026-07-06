package com.judepereira.jupiter2;

import com.judepereira.jupiter2.agent.harness.AgentTurnRequest;
import com.judepereira.jupiter2.agent.harness.AgentTurnResult;
import com.judepereira.jupiter2.agent.harness.CodingAgentHarness;
import com.judepereira.jupiter2.ui.UiController;
import org.junit.jupiter.api.Test;
import org.springframework.ui.Model;
import org.springframework.ui.ConcurrentModel;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class UiControllerAsyncTests {

    @Test
    public void sendReturnsWithPending_whenHarnessBlocks() throws Exception {
        CodingAgentHarness fake = new CodingAgentHarness(null, null, null) {
            @Override
            public AgentTurnResult runTurn(AgentTurnRequest request) {
                try { Thread.sleep(500); } catch (InterruptedException e) {}
                return new AgentTurnResult("final reply", List.of());
            }
        };

        var props = new com.judepereira.jupiter2.agent.config.AgentProperties();
        props.setWorkspaceRoot(".");
        UiController ctrl = new UiController(fake, props);

        Model model = new ConcurrentModel();
        long start = Instant.now().toEpochMilli();
        String frag = ctrl.sendMessage("hello", model, null);
        long dur = Instant.now().toEpochMilli() - start;
        // should return quickly (<200ms)
        assertThat(dur).isLessThan(2000);
        List<?> msgs = (List<?>) ((ConcurrentModel)model).getAttribute("chatMessages");
        assertThat(msgs).isNotEmpty();
        // last message should be pending assistant
        Object last = msgs.get(msgs.size()-1);
        assertThat(last.toString()).contains("Thinking");

        // after waiting, poll should show final text and include composite response
        Thread.sleep(800);
        Model model2 = new ConcurrentModel();
        String out = ctrl.pollChat(model2);
        // poll should return composite fragment
        assertThat(out).contains("fragments/chat-response :: response");
        List<?> msgs2 = (List<?>) ((ConcurrentModel)model2).getAttribute("chatMessages");
        Object last2 = msgs2.get(msgs2.size()-1);
        assertThat(last2.toString()).contains("final reply");
        // after completion there should be no pending
        Boolean hasPending = (Boolean) ((ConcurrentModel)model2).getAttribute("hasPending");
        assertThat(hasPending).isFalse();
    }
}
