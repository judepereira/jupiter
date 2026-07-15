package com.judepereira.jupiter2.agent.llm.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.judepereira.jupiter2.agent.config.AgentProperties;
import com.judepereira.jupiter2.agent.config.OpenAiProperties;
import com.judepereira.jupiter2.agent.llm.dto.Message;
import com.judepereira.jupiter2.agent.llm.dto.ToolCall;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class OpenAiAgentModelClientTest {

    private final ObjectMapper om = new ObjectMapper();

    @Test
    public void modern_shape_function_name_and_arguments_is_parsed() throws Exception {
        String json = "{\"function_call\":{\"name\":\"read_file\",\"arguments\":{\"path\":\"/tmp/x\"}}}";
        JsonNode node = om.readTree(json);
        var partials = new LinkedHashMap<String, OpenAiAgentModelClient.ToolPartial>();
        OpenAiAgentModelClient.accumulateToolPartials(node, partials);
        assertFalse(partials.isEmpty());
        var chosen = OpenAiAgentModelClient.chooseNamedPartial(partials);
        assertNotNull(chosen);
        assertEquals("read_file", chosen.name);
        assertTrue(chosen.arguments.length() > 0);
        assertTrue(chosen.arguments.toString().contains("path"));
    }

    @Test
    public void stable_grouping_prefers_index_and_merges_fragments() throws Exception {
        // first chunk: id+index+function.name
        String c1 = "{\"tool_calls\":[{\"id\":\"abc\",\"index\":0,\"function\":{\"name\":\"read_file\"}}]}";
        // second chunk: index+function.arguments
        String c2 = "{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":{\"path\":\"/tmp/a\"}}}]}";
        var partials = new LinkedHashMap<String, OpenAiAgentModelClient.ToolPartial>();
        OpenAiAgentModelClient.accumulateToolPartials(om.readTree(c1), partials);
        OpenAiAgentModelClient.accumulateToolPartials(om.readTree(c2), partials);
        assertEquals(1, partials.size());
        var p = partials.values().iterator().next();
        assertEquals("read_file", p.name);
        assertTrue(p.arguments.toString().contains("/tmp/a"));
    }

    @Test
    public void nameless_partial_is_not_selected() throws Exception {
        String j = "{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":{\"path\":\"/x\"}}}]}";
        var partials = new LinkedHashMap<String, OpenAiAgentModelClient.ToolPartial>();
        OpenAiAgentModelClient.accumulateToolPartials(om.readTree(j), partials);
        assertFalse(partials.isEmpty());
        var chosen = OpenAiAgentModelClient.chooseNamedPartial(partials);
        assertNull(chosen);
    }

    @Test
    public void buildRawMessages_serializes_tool_message_and_preserves_tool_call_id() throws Exception {
        var client = new OpenAiAgentModelClient(new OpenAiProperties(), new AgentProperties());
        var conversation = List.of(
                new Message(Message.Role.SYSTEM, "sys"),
                new Message(Message.Role.USER, "user"),
                new Message(Message.Role.ASSISTANT, null, List.of(new ToolCall("call-123", "write_file", Map.of("path", "x.txt")))),
                new Message(Message.Role.TOOL, "written", "call-123")
        );

        Method m = OpenAiAgentModelClient.class.getDeclaredMethod("buildRawMessages", List.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> raw = (List<Map<String, Object>>) m.invoke(client, conversation);

        assertEquals("system", raw.get(0).get("role"));
        assertEquals("user", raw.get(1).get("role"));
        assertEquals("assistant", raw.get(2).get("role"));
        assertEquals("call-123", ((List<Map<String, Object>>) raw.get(2).get("tool_calls")).get(0).get("id"));
        assertEquals("tool", raw.get(3).get("role"));
        assertEquals("call-123", raw.get(3).get("tool_call_id"));
        assertEquals("written", raw.get(3).get("content"));
    }
}
