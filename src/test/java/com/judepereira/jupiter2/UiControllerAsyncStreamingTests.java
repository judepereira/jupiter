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
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

public class UiControllerAsyncStreamingTests {

    @Test
    public void sendReturnsQuickly_withPending_andDoesNotRunHarnessSynchronously() throws Exception {
        AtomicBoolean runCalled = new AtomicBoolean(false);
        CodingAgentHarness fake = new CodingAgentHarness(null, null, null) {
            @Override
            public AgentTurnResult runTurn(AgentTurnRequest request) {
                runCalled.set(true);
                return new AgentTurnResult("final reply", List.of());
            }

            @Override
            public AgentTurnResult runTurnStreaming(AgentTurnRequest request, com.judepereira.jupiter2.agent.llm.AgentStreamListener listener) {
                runCalled.set(true);
                return new AgentTurnResult("final reply", List.of());
            }
        };

        var props = new com.judepereira.jupiter2.agent.config.AgentProperties();
        props.setWorkspaceRoot(".");
        UiController ctrl = new UiController(fake, props, (Runnable r) -> r.run());

        Model model = new ConcurrentModel();
        long start = Instant.now().toEpochMilli();
        String frag = ctrl.sendMessage("hello", model, null);
        long dur = Instant.now().toEpochMilli() - start;
        // should return quickly (<2s)
        assertThat(dur).isLessThan(2000);
        List<?> msgs = (List<?>) ((ConcurrentModel)model).getAttribute("chatMessages");
        assertThat(msgs).isNotEmpty();
        // last message should be pending assistant
        Object last = msgs.get(msgs.size()-1);
        assertThat(last.toString()).contains("Thinking");

        // harness should NOT have been called during send
        assertThat(runCalled.get()).isFalse();

        // fragment should be the chat response composite
        assertThat(frag).contains("fragments/chat-response :: response");
        // model should include only the newly created rows for append responses
        List<?> newRows = (List<?>) ((ConcurrentModel)model).getAttribute("newChatMessages");
        assertThat(newRows).isNotNull();
        assertThat(newRows.stream().anyMatch(o -> o.toString().contains("Thinking"))).isTrue();
        Boolean hasPending = (Boolean) ((ConcurrentModel)model).getAttribute("hasPending");
        assertThat(hasPending).isTrue();
    }

    @Test
    public void streaming_preserves_spaces_and_newlines_in_final_text() throws Exception {
        CodingAgentHarness fake = new CodingAgentHarness(null, null, null) {
            @Override
            public AgentTurnResult runTurn(AgentTurnRequest request) {
                return new AgentTurnResult("", List.of());
            }

            @Override
            public AgentTurnResult runTurnStreaming(AgentTurnRequest request, com.judepereira.jupiter2.agent.llm.AgentStreamListener listener) {
                // emit deltas including space and newline chunks
                listener.onTextDelta("hello");
                listener.onTextDelta(" ");
                listener.onTextDelta("world");
                listener.onTextDelta("\n");
                listener.onTextDelta("next");
                AgentTurnResult res = new AgentTurnResult("hello world\nnext", List.of());
                listener.onComplete(res);
                return res;
            }
        };

        var props = new com.judepereira.jupiter2.agent.config.AgentProperties();
        props.setWorkspaceRoot(".");
        UiController ctrl = new UiController(fake, props, (Runnable r) -> r.run());

        // register pending assistant
        Model m1 = new ConcurrentModel();
        String respFrag = ctrl.sendMessage("go", m1, null);
        assertThat(respFrag).contains("fragments/chat-response :: response");
        List<?> newRows1 = (List<?>) ((ConcurrentModel)m1).getAttribute("newChatMessages");
        assertThat(newRows1).isNotNull();
        assertThat(newRows1.size()).isGreaterThanOrEqualTo(1);

        List<?> msgs = (List<?>) ((ConcurrentModel)m1).getAttribute("chatMessages");
        Object last = msgs.get(msgs.size()-1);
        String assistantId = null;
        try {
            var cls = last.getClass();
            var f = cls.getDeclaredField("id");
            f.setAccessible(true);
            assistantId = (String) f.get(last);
        } catch (Exception e) {
            String s = last.toString();
            int i = s.indexOf("id=");
            if (i >= 0) assistantId = s.substring(i+3).replaceAll("[^a-zA-Z0-9-]", "");
        }
        assertThat(assistantId).isNotNull();
        final String finalAssistantId = assistantId;

        // run stream
        var emitter = ctrl.streamChat(assistantId);
        assertThat(emitter).isNotNull();

        // after streaming completes, index should reflect final assistant text
        Model m2 = new ConcurrentModel();
        ctrl.index(m2);
        List<?> after = (List<?>) ((ConcurrentModel)m2).getAttribute("chatMessages");
        // find assistant by id
        Object found = after.stream().filter(o -> {
            try {
                var cls = o.getClass();
                var f = cls.getDeclaredField("id");
                f.setAccessible(true);
                return finalAssistantId.equals((String) f.get(o));
            } catch (Exception e) {
                return o.toString().contains(finalAssistantId);
            }
        }).findFirst().orElse(null);
        assertThat(found).isNotNull();
        // inspect text and pending
        String text = null;
        boolean pending = true;
        try {
            var cls = found.getClass();
            var ft = cls.getDeclaredField("text"); ft.setAccessible(true); text = (String) ft.get(found);
            var fp = cls.getDeclaredField("pending"); fp.setAccessible(true); pending = fp.getBoolean(found);
        } catch (Exception e) {
            String s = found.toString();
            int ti = s.indexOf("text="); if(ti>=0){ text = s.substring(ti+5).replaceAll("[,}].*$","\"").replaceAll("\"","\""); }
        }
        assertThat(text).isEqualTo("hello world\nnext");
        assertThat(pending).isFalse();

        // final assistant message should include toolCalls list when present (none in this fake), but ensure field exists via reflection
        Object foundMsg = found;
        try {
            var cls = foundMsg.getClass();
            var f = cls.getDeclaredField("toolCalls");
            f.setAccessible(true);
            Object tc = f.get(foundMsg);
            // allow null or empty list here; assert extraction is robust
            assertThat(tc == null || (tc instanceof java.util.List<?>)).isTrue();
        } catch (NoSuchFieldException nsf) {
            // ignore: some toString-based fallbacks may not expose fields
        }
    }

    @Test
    public void streaming_error_normalizes_openai_json_message() throws Exception {
        String quotaJson = "{\"error\":{\"message\":\"You exceeded your current quota, please check your plan and billing details.\",\"type\":\"insufficient_quota\",\"param\":null,\"code\":\"insufficient_quota\"}}";

        CodingAgentHarness fake = new CodingAgentHarness(null, null, null) {
            @Override
            public AgentTurnResult runTurn(AgentTurnRequest request) {
                return new AgentTurnResult("", List.of());
            }

            @Override
            public AgentTurnResult runTurnStreaming(AgentTurnRequest request, com.judepereira.jupiter2.agent.llm.AgentStreamListener listener) {
                throw new RuntimeException("provider error: " + quotaJson);
            }
        };

        var props = new com.judepereira.jupiter2.agent.config.AgentProperties();
        props.setWorkspaceRoot(".");
        UiController ctrl = new UiController(fake, props, (Runnable r) -> r.run());

        Model model = new ConcurrentModel();
        ctrl.sendMessage("go", model, null);
        List<?> msgs = (List<?>) ((ConcurrentModel) model).getAttribute("chatMessages");
        Object last = msgs.get(msgs.size() - 1);

        String assistantId = null;
        try {
            var cls = last.getClass();
            var f = cls.getDeclaredField("id");
            f.setAccessible(true);
            assistantId = (String) f.get(last);
        } catch (Exception e) {
            String s = last.toString();
            int i = s.indexOf("id=");
            if (i >= 0) assistantId = s.substring(i + 3).replaceAll("[^a-zA-Z0-9-]", "");
        }
        assertThat(assistantId).isNotNull();
        final String finalAssistantId = assistantId;

        ctrl.streamChat(assistantId);

        Model afterModel = new ConcurrentModel();
        ctrl.index(afterModel);
        List<?> after = (List<?>) ((ConcurrentModel) afterModel).getAttribute("chatMessages");
        Object found = after.stream().filter(o -> {
            try {
                var cls = o.getClass();
                var f = cls.getDeclaredField("id");
                f.setAccessible(true);
                return finalAssistantId.equals((String) f.get(o));
            } catch (Exception e) {
                return o.toString().contains(finalAssistantId);
            }
        }).findFirst().orElse(null);

        assertThat(found).isNotNull();

        String text = null;
        try {
            var cls = found.getClass();
            var f = cls.getDeclaredField("text");
            f.setAccessible(true);
            text = (String) f.get(found);
        } catch (Exception e) {
            String s = found.toString();
            int i = s.indexOf("text=");
            if (i >= 0) text = s.substring(i + 5).replaceAll(",.*$", "");
        }

        assertThat(text).contains("You exceeded your current quota, please check your plan and billing details.");
        assertThat(text).doesNotContain("insufficient_quota");
        assertThat(text).doesNotContain("\"error\"");
    }
}
