package com.judepereira.jupiter2.agent.llm.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;

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
}
