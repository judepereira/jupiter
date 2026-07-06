package com.judepereira.jupiter2.agent.harness;

import com.judepereira.jupiter2.agent.config.AgentProperties;
import com.judepereira.jupiter2.agent.llm.AgentModelClient;
import com.judepereira.jupiter2.agent.llm.AgentModelClientFactory;
import com.judepereira.jupiter2.agent.llm.dto.ModelResponse;
import com.judepereira.jupiter2.agent.llm.dto.ToolCall;
import com.judepereira.jupiter2.agent.llm.dto.ToolDefinition;
import com.judepereira.jupiter2.agent.tools.ToolExecutionContext;
import com.judepereira.jupiter2.agent.tools.ToolExecutionResult;
import com.judepereira.jupiter2.agent.tools.ToolRegistry;
import com.judepereira.jupiter2.agent.tools.impl.WriteFileTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class CodingAgentHarnessFakeModelTest {

    static class FakeFactory extends AgentModelClientFactory {
        private final AgentModelClient client;

        public FakeFactory(AgentModelClient client) {
            super(null, new AgentProperties());
            this.client = client;
        }

        @Override
        public AgentModelClient getClient() {
            return client;
        }
    }

    static class SequenceModel implements AgentModelClient {
        private final List<ModelResponse> seq;
        private int idx = 0;

        public SequenceModel(List<ModelResponse> seq) {
            this.seq = seq;
        }

        @Override
        public ModelResponse chat(List<com.judepereira.jupiter2.agent.llm.dto.Message> conversation, List<ToolDefinition> tools) {
            if (idx >= seq.size()) return new ModelResponse("", null);
            return seq.get(idx++);
        }
    }

    @Test
    public void scenarioA_model_requests_write_then_final_text(@TempDir Path tmp) throws Exception {
        // model: call write_file, then final assistant text
        ToolCall call = new ToolCall("write_file", Map.of("path", "x.txt", "content", "hello"));
        ModelResponse r1 = new ModelResponse(null, call);
        ModelResponse r2 = new ModelResponse("Done! final text.", null);
        SequenceModel model = new SequenceModel(List.of(r1, r2));

        AgentProperties props = new AgentProperties();
        props.setMaxIterations(5);
        props.setWorkspaceRoot(tmp.toString());
        props.getTooling().setAllowWrite(true);

        ToolRegistry reg = new ToolRegistry();
        reg.register(new WriteFileTool());

        CodingAgentHarness harness = new CodingAgentHarness(new FakeFactory(model), reg, props);
        var req = new AgentTurnRequest("sys", "user");
        var res = harness.runTurn(req);
        assertEquals("Done! final text.", res.getFinalText());
        // trace should contain one write_file
        assertEquals(1, res.getTraces().size());
        assertEquals("write_file", res.getTraces().get(0).getToolName());
        // file created in workspace
        assertTrue(Files.exists(tmp.resolve("x.txt")));
    }

    @Test
    public void scenarioB_unknown_tool_then_recovers_with_final_text(@TempDir Path tmp) {
        ToolCall call = new ToolCall("no_such_tool", Map.of());
        ModelResponse r1 = new ModelResponse(null, call);
        ModelResponse r2 = new ModelResponse("Recovered final.", null);
        SequenceModel model = new SequenceModel(List.of(r1, r2));

        AgentProperties props = new AgentProperties();
        props.setMaxIterations(5);
        props.setWorkspaceRoot(tmp.toString());

        ToolRegistry reg = new ToolRegistry();

        CodingAgentHarness harness = new CodingAgentHarness(new FakeFactory(model), reg, props);
        var res = harness.runTurn(new AgentTurnRequest("s", "u"));
        assertEquals("Recovered final.", res.getFinalText());
        assertEquals(1, res.getTraces().size());
        assertFalse(res.getTraces().get(0).isSuccess());
        assertEquals("no_such_tool", res.getTraces().get(0).getToolName());
    }

    @Test
    public void scenarioC_max_iterations_reached_when_model_keeps_requesting_tools(@TempDir Path tmp) {
        ToolCall call = new ToolCall("no_such_tool", Map.of());
        // model keeps asking for tool, never returns final text
        SequenceModel model = new SequenceModel(List.of(new ModelResponse(null, call), new ModelResponse(null, call), new ModelResponse(null, call)));

        AgentProperties props = new AgentProperties();
        props.setMaxIterations(2);
        props.setWorkspaceRoot(tmp.toString());

        ToolRegistry reg = new ToolRegistry();

        CodingAgentHarness harness = new CodingAgentHarness(new FakeFactory(model), reg, props);
        var res = harness.runTurn(new AgentTurnRequest("s", "u"));
        assertTrue(res.getFinalText().toLowerCase().contains("max iterations"));
        // traces should be equal to maxIterations
        assertEquals(2, res.getTraces().size());
    }
}
