package com.judepereira.jupiter.agent.harness;

import com.judepereira.jupiter.agent.config.AgentProperties;
import com.judepereira.jupiter.agent.llm.AgentModelClient;
import com.judepereira.jupiter.agent.llm.AgentModelClientFactory;
import com.judepereira.jupiter.agent.llm.dto.Message;
import com.judepereira.jupiter.agent.llm.dto.ModelResponse;
import com.judepereira.jupiter.agent.llm.dto.ToolCall;
import com.judepereira.jupiter.agent.llm.dto.ToolDefinition;
import com.judepereira.jupiter.agent.tools.ToolRegistry;
import com.judepereira.jupiter.agent.tools.impl.WriteFileTool;
import com.judepereira.jupiter.testsupport.SystemPromptTestSupport;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class CodingAgentHarnessFakeModelTest {

    private static AgentModelClientFactory fakeFactory(AgentModelClient client) {
        return new AgentModelClientFactory(null, new AgentProperties()) {
            @Override
            public AgentModelClient getClient() {
                return client;
            }
        };
    }

    @RequiredArgsConstructor
    static class SequenceModel implements AgentModelClient {
        private final List<ModelResponse> seq;
        private int idx = 0;

        @Override
        public ModelResponse chat(List<com.judepereira.jupiter.agent.llm.dto.Message> conversation, List<ToolDefinition> tools) {
            if (idx >= seq.size()) return new ModelResponse("", null, com.judepereira.jupiter.agent.llm.dto.ModelResponseMetadata.empty());
            return seq.get(idx++);
        }
    }

    @Test
    public void runTurn_usesStructuredHistoryInOrder(@TempDir Path tmp) {
        List<List<Message>> captured = new ArrayList<>();
        AgentModelClient model = new AgentModelClient() {
            @Override
            public ModelResponse chat(List<Message> conversation, List<ToolDefinition> tools) {
                return new ModelResponse("ok", null, com.judepereira.jupiter.agent.llm.dto.ModelResponseMetadata.empty());
            }

            @Override
            public ModelResponse chatStreaming(List<Message> conversation, List<ToolDefinition> tools, java.util.function.Consumer<String> onDelta) {
                captured.add(List.copyOf(conversation));
                return new ModelResponse("ok", null, com.judepereira.jupiter.agent.llm.dto.ModelResponseMetadata.empty());
            }
        };

        AgentProperties props = new AgentProperties();
        props.setMaxIterations(1);
        props.setWorkspaceRoot(tmp.toString());

        CodingAgentHarness harness = new CodingAgentHarness(fakeFactory(model), new ToolRegistry(), props, null, null, null, null, null, new com.judepereira.jupiter.agent.harness.SystemPromptComposer(new com.judepereira.jupiter.agent.skill.SkillCatalogRenderer()), new com.judepereira.jupiter.agent.skill.SkillDiscoveryService(new com.judepereira.jupiter.agent.skill.SkillParser(), System.getProperty("user.home")), new com.judepereira.jupiter.agent.skill.SkillInvocationResolver(), new com.judepereira.jupiter.agent.skill.SkillContextInjector(new com.judepereira.jupiter.agent.skill.SkillParser()));
        var req = new AgentTurnRequest("sys", List.of(
                new Message(Message.Role.USER, "u1", null, null),
                new Message(Message.Role.ASSISTANT, "a1", null, null),
                new Message(Message.Role.USER, "u2", null, null)
        ), null, null, null, null, null, null);

        var res = harness.runTurn(req);

        assertEquals("ok", res.getFinalText());
        assertEquals(1, captured.size());
        assertEquals(Message.Role.SYSTEM, captured.get(0).get(0).getRole());
        assertComposedSystemPrompt(captured.get(0).get(0).getContent(), "sys", tmp);
        assertEquals(List.of("USER:u1", "ASSISTANT:a1", "USER:u2"),
                captured.get(0).subList(1, captured.get(0).size()).stream().map(m -> m.getRole() + ":" + m.getContent()).toList());
    }

    @Test
    public void scenarioA_next_model_call_receives_structured_tool_history(@TempDir Path tmp) {
        List<List<Message>> captured = new ArrayList<>();
        SequenceModel model = new SequenceModel(List.of(
                new ModelResponse(null, new ToolCall(null, "write_file", Map.of("path", "x.txt", "content", "hello")), com.judepereira.jupiter.agent.llm.dto.ModelResponseMetadata.empty()),
                new ModelResponse("Done! final text.", null, com.judepereira.jupiter.agent.llm.dto.ModelResponseMetadata.empty())
        )) {
            @Override
            public ModelResponse chatStreaming(List<Message> conversation, List<ToolDefinition> tools, java.util.function.Consumer<String> onDelta) {
                captured.add(List.copyOf(conversation));
                return chat(conversation, tools);
            }
        };

        AgentProperties props = new AgentProperties();
        props.setMaxIterations(5);
        props.setWorkspaceRoot(tmp.toString());
        props.getTooling().setAllowWrite(true);

        ToolRegistry reg = new ToolRegistry();
        reg.register(new WriteFileTool());

        CodingAgentHarness harness = new CodingAgentHarness(fakeFactory(model), reg, props, null, null, null, null, null, new com.judepereira.jupiter.agent.harness.SystemPromptComposer(new com.judepereira.jupiter.agent.skill.SkillCatalogRenderer()), new com.judepereira.jupiter.agent.skill.SkillDiscoveryService(new com.judepereira.jupiter.agent.skill.SkillParser(), System.getProperty("user.home")), new com.judepereira.jupiter.agent.skill.SkillInvocationResolver(), new com.judepereira.jupiter.agent.skill.SkillContextInjector(new com.judepereira.jupiter.agent.skill.SkillParser()));

        var res = harness.runTurn(new AgentTurnRequest("sys", List.of(new Message(Message.Role.USER, "user", null, null)), null, null, null, null, null, null));

        assertEquals("Done! final text.", res.getFinalText());
        assertEquals(1, res.getTraces().size());
        assertEquals("write_file", res.getTraces().get(0).getToolName());
        assertTrue(Files.exists(tmp.resolve("x.txt")));

        assertEquals(2, captured.size());
        var second = captured.get(1);
        assertEquals(Message.Role.SYSTEM, second.get(0).getRole());
        assertComposedSystemPrompt(second.get(0).getContent(), "sys", tmp);
        assertEquals("USER:user", second.get(1).getRole() + ":" + second.get(1).getContent());
        assertEquals(Message.Role.ASSISTANT, second.get(2).getRole());
        assertNull(second.get(2).getContent());
        assertNotNull(second.get(2).getToolCalls());
        assertEquals(1, second.get(2).getToolCalls().size());
        assertEquals("write_file", second.get(2).getToolCalls().get(0).getToolName());
        assertNotNull(second.get(2).getToolCalls().get(0).getToolCallId());
        assertEquals(Message.Role.TOOL, second.get(3).getRole());
        assertEquals(second.get(2).getToolCalls().get(0).getToolCallId(), second.get(3).getToolCallId());
        assertTrue(second.stream().noneMatch(m -> m.getRole() == Message.Role.ASSISTANT
                && m.getContent() != null && m.getContent().startsWith("[tool_result]")));
    }

    @Test
    public void scenarioA_model_requests_write_then_final_text(@TempDir Path tmp) throws Exception {
        // model: call write_file, then final assistant text
        ToolCall call = new ToolCall(null, "write_file", Map.of("path", "x.txt", "content", "hello"));
        ModelResponse r1 = new ModelResponse(null, call, com.judepereira.jupiter.agent.llm.dto.ModelResponseMetadata.empty());
        ModelResponse r2 = new ModelResponse("Done! final text.", null, com.judepereira.jupiter.agent.llm.dto.ModelResponseMetadata.empty());
        SequenceModel model = new SequenceModel(List.of(r1, r2));

        AgentProperties props = new AgentProperties();
        props.setMaxIterations(5);
        props.setWorkspaceRoot(tmp.toString());
        props.getTooling().setAllowWrite(true);

        ToolRegistry reg = new ToolRegistry();
        reg.register(new WriteFileTool());

        CodingAgentHarness harness = new CodingAgentHarness(fakeFactory(model), reg, props, null, null, null, null, null, new com.judepereira.jupiter.agent.harness.SystemPromptComposer(new com.judepereira.jupiter.agent.skill.SkillCatalogRenderer()), new com.judepereira.jupiter.agent.skill.SkillDiscoveryService(new com.judepereira.jupiter.agent.skill.SkillParser(), System.getProperty("user.home")), new com.judepereira.jupiter.agent.skill.SkillInvocationResolver(), new com.judepereira.jupiter.agent.skill.SkillContextInjector(new com.judepereira.jupiter.agent.skill.SkillParser()));
        var req = new AgentTurnRequest("sys", List.of(new Message(Message.Role.USER, "user", null, null)), null, null, null, null, null, null);
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
        ToolCall call = new ToolCall(null, "no_such_tool", Map.of());
        ModelResponse r1 = new ModelResponse(null, call, com.judepereira.jupiter.agent.llm.dto.ModelResponseMetadata.empty());
        ModelResponse r2 = new ModelResponse("Recovered final.", null, com.judepereira.jupiter.agent.llm.dto.ModelResponseMetadata.empty());
        SequenceModel model = new SequenceModel(List.of(r1, r2));

        AgentProperties props = new AgentProperties();
        props.setMaxIterations(5);
        props.setWorkspaceRoot(tmp.toString());

        ToolRegistry reg = new ToolRegistry();

        CodingAgentHarness harness = new CodingAgentHarness(fakeFactory(model), reg, props, null, null, null, null, null, new com.judepereira.jupiter.agent.harness.SystemPromptComposer(new com.judepereira.jupiter.agent.skill.SkillCatalogRenderer()), new com.judepereira.jupiter.agent.skill.SkillDiscoveryService(new com.judepereira.jupiter.agent.skill.SkillParser(), System.getProperty("user.home")), new com.judepereira.jupiter.agent.skill.SkillInvocationResolver(), new com.judepereira.jupiter.agent.skill.SkillContextInjector(new com.judepereira.jupiter.agent.skill.SkillParser()));
        var res = harness.runTurn(new AgentTurnRequest("s", List.of(new Message(Message.Role.USER, "u", null, null)), null, null, null, null, null, null));
        assertEquals("Recovered final.", res.getFinalText());
        assertEquals(1, res.getTraces().size());
        assertFalse(res.getTraces().get(0).isSuccess());
        assertEquals("no_such_tool", res.getTraces().get(0).getToolName());
    }

    @Test
    public void scenarioC_max_iterations_reached_when_model_keeps_requesting_tools(@TempDir Path tmp) {
        ToolCall call = new ToolCall(null, "no_such_tool", Map.of());
        // model keeps asking for tool, never returns final text
        SequenceModel model = new SequenceModel(List.of(new ModelResponse(null, call, com.judepereira.jupiter.agent.llm.dto.ModelResponseMetadata.empty()), new ModelResponse(null, call, com.judepereira.jupiter.agent.llm.dto.ModelResponseMetadata.empty()), new ModelResponse(null, call, com.judepereira.jupiter.agent.llm.dto.ModelResponseMetadata.empty())));

        AgentProperties props = new AgentProperties();
        props.setMaxIterations(2);
        props.setWorkspaceRoot(tmp.toString());

        ToolRegistry reg = new ToolRegistry();

        CodingAgentHarness harness = new CodingAgentHarness(fakeFactory(model), reg, props, null, null, null, null, null, new com.judepereira.jupiter.agent.harness.SystemPromptComposer(new com.judepereira.jupiter.agent.skill.SkillCatalogRenderer()), new com.judepereira.jupiter.agent.skill.SkillDiscoveryService(new com.judepereira.jupiter.agent.skill.SkillParser(), System.getProperty("user.home")), new com.judepereira.jupiter.agent.skill.SkillInvocationResolver(), new com.judepereira.jupiter.agent.skill.SkillContextInjector(new com.judepereira.jupiter.agent.skill.SkillParser()));
        var res = harness.runTurn(new AgentTurnRequest("s", List.of(new Message(Message.Role.USER, "u", null, null)), null, null, null, null, null, null));
        assertTrue(res.getFinalText().toLowerCase().contains("max iterations"));
        // traces should be equal to maxIterations
        assertEquals(2, res.getTraces().size());
    }

    @Test
    public void runTurnStreaming_emits_deltas_and_completes(@TempDir Path tmp) throws Exception {
        // fake model that emits two deltas then final text via chatStreaming default impl
        class StreamingModel implements com.judepereira.jupiter.agent.llm.AgentModelClient {
            @Override
            public com.judepereira.jupiter.agent.llm.dto.ModelResponse chat(java.util.List<com.judepereira.jupiter.agent.llm.dto.Message> conversation, java.util.List<com.judepereira.jupiter.agent.llm.dto.ToolDefinition> tools) {
                return new com.judepereira.jupiter.agent.llm.dto.ModelResponse("Done!", null, com.judepereira.jupiter.agent.llm.dto.ModelResponseMetadata.empty());
            }

            @Override
            public com.judepereira.jupiter.agent.llm.dto.ModelResponse chatStreaming(java.util.List<com.judepereira.jupiter.agent.llm.dto.Message> conversation, java.util.List<com.judepereira.jupiter.agent.llm.dto.ToolDefinition> tools, java.util.function.Consumer<String> onDelta) {
                onDelta.accept("Done");
                onDelta.accept("!");
                return new com.judepereira.jupiter.agent.llm.dto.ModelResponse("Done!", null, com.judepereira.jupiter.agent.llm.dto.ModelResponseMetadata.empty());
            }
        }

        ToolRegistry reg = new ToolRegistry();
        AgentProperties props = new AgentProperties();
        props.setWorkspaceRoot(tmp.toString());
        props.setMaxIterations(5);
        CodingAgentHarness harness = new CodingAgentHarness(fakeFactory(new StreamingModel()), reg, props, null, null, null, null, null, new com.judepereira.jupiter.agent.harness.SystemPromptComposer(new com.judepereira.jupiter.agent.skill.SkillCatalogRenderer()), new com.judepereira.jupiter.agent.skill.SkillDiscoveryService(new com.judepereira.jupiter.agent.skill.SkillParser(), System.getProperty("user.home")), new com.judepereira.jupiter.agent.skill.SkillInvocationResolver(), new com.judepereira.jupiter.agent.skill.SkillContextInjector(new com.judepereira.jupiter.agent.skill.SkillParser()));

        var req = new AgentTurnRequest("sys", List.of(new Message(Message.Role.USER, "user", null, null)), null, null, null, null, null, null);
        // listener to capture deltas
        StringBuilder acc = new StringBuilder();
        com.judepereira.jupiter.agent.llm.AgentStreamListener listener = new com.judepereira.jupiter.agent.llm.AgentStreamListener() {
            @Override
            public void onTextDelta(String delta) {
                acc.append(delta);
            }
            @Override
            public void onToolCallTrace(com.judepereira.jupiter.agent.harness.ToolCallTrace trace) {
                // capture tool traces if any (not used in this scenario)
            }
        };

        var res = harness.runTurnStreaming(req, listener);
        assertEquals("Done!", res.getFinalText());
        assertEquals("Done!", acc.toString());
    }

    @Test
    public void runTurnStreaming_invokes_onToolCallTrace_for_successful_tool(@TempDir Path tmp) throws Exception {
        // model: requests write_file, then final text. Use existing WriteFileTool to succeed.
        ToolCall call = new ToolCall(null, "write_file", Map.of("path", "y.txt", "content", "hello"));
        ModelResponse r1 = new ModelResponse(null, call, com.judepereira.jupiter.agent.llm.dto.ModelResponseMetadata.empty());
        ModelResponse r2 = new ModelResponse("done", null, com.judepereira.jupiter.agent.llm.dto.ModelResponseMetadata.empty());
        SequenceModel model = new SequenceModel(List.of(r1, r2));

        AgentProperties props = new AgentProperties();
        props.setMaxIterations(5);
        props.setWorkspaceRoot(tmp.toString());
        props.getTooling().setAllowWrite(true);

        ToolRegistry reg = new ToolRegistry();
        reg.register(new WriteFileTool());

        CodingAgentHarness harness = new CodingAgentHarness(fakeFactory(model), reg, props, null, null, null, null, null, new com.judepereira.jupiter.agent.harness.SystemPromptComposer(new com.judepereira.jupiter.agent.skill.SkillCatalogRenderer()), new com.judepereira.jupiter.agent.skill.SkillDiscoveryService(new com.judepereira.jupiter.agent.skill.SkillParser(), System.getProperty("user.home")), new com.judepereira.jupiter.agent.skill.SkillInvocationResolver(), new com.judepereira.jupiter.agent.skill.SkillContextInjector(new com.judepereira.jupiter.agent.skill.SkillParser()));

        var req = new AgentTurnRequest("sys", List.of(new Message(Message.Role.USER, "user", null, null)), null, null, null, null, null, null);
        final boolean[] saw = new boolean[1];
        final ToolCallTrace[] captured = new ToolCallTrace[1];

        com.judepereira.jupiter.agent.llm.AgentStreamListener listener = new com.judepereira.jupiter.agent.llm.AgentStreamListener() {
            @Override
            public void onToolCallTrace(ToolCallTrace trace) {
                saw[0] = true;
                captured[0] = trace;
            }
        };

        var res = harness.runTurnStreaming(req, listener);
        assertEquals("done", res.getFinalText());
        assertTrue(saw[0]);
        assertNotNull(captured[0]);
        assertEquals("write_file", captured[0].getToolName());
        assertTrue(captured[0].isSuccess());
        // file created in workspace
        assertTrue(Files.exists(tmp.resolve("y.txt")));
    }

    @Test
    public void runTurnStreaming_preserves_newline_only_deltas(@TempDir Path tmp) throws Exception {
        // fake model that emits hello, then newline-only chunk, then world
        class StreamingModel implements com.judepereira.jupiter.agent.llm.AgentModelClient {
            @Override
            public com.judepereira.jupiter.agent.llm.dto.ModelResponse chat(java.util.List<com.judepereira.jupiter.agent.llm.dto.Message> conversation, java.util.List<com.judepereira.jupiter.agent.llm.dto.ToolDefinition> tools) {
                return new com.judepereira.jupiter.agent.llm.dto.ModelResponse("hello\n\nworld", null, com.judepereira.jupiter.agent.llm.dto.ModelResponseMetadata.empty());
            }

            @Override
            public com.judepereira.jupiter.agent.llm.dto.ModelResponse chatStreaming(java.util.List<com.judepereira.jupiter.agent.llm.dto.Message> conversation, java.util.List<com.judepereira.jupiter.agent.llm.dto.ToolDefinition> tools, java.util.function.Consumer<String> onDelta) {
                onDelta.accept("hello");
                onDelta.accept("\n\n");
                onDelta.accept("world");
                return new com.judepereira.jupiter.agent.llm.dto.ModelResponse("hello\n\nworld", null, com.judepereira.jupiter.agent.llm.dto.ModelResponseMetadata.empty());
            }
        }

        ToolRegistry reg = new ToolRegistry();
        AgentProperties props = new AgentProperties();
        props.setWorkspaceRoot(tmp.toString());
        props.setMaxIterations(5);
        CodingAgentHarness harness = new CodingAgentHarness(fakeFactory(new StreamingModel()), reg, props, null, null, null, null, null, new com.judepereira.jupiter.agent.harness.SystemPromptComposer(new com.judepereira.jupiter.agent.skill.SkillCatalogRenderer()), new com.judepereira.jupiter.agent.skill.SkillDiscoveryService(new com.judepereira.jupiter.agent.skill.SkillParser(), System.getProperty("user.home")), new com.judepereira.jupiter.agent.skill.SkillInvocationResolver(), new com.judepereira.jupiter.agent.skill.SkillContextInjector(new com.judepereira.jupiter.agent.skill.SkillParser()));

        var req = new AgentTurnRequest("sys", List.of(new Message(Message.Role.USER, "user", null, null)), null, null, null, null, null, null);
        StringBuilder acc = new StringBuilder();
        com.judepereira.jupiter.agent.llm.AgentStreamListener listener = new com.judepereira.jupiter.agent.llm.AgentStreamListener() {
            @Override
            public void onTextDelta(String delta) {
                acc.append(delta);
            }
        };

        var res = harness.runTurnStreaming(req, listener);
        assertEquals("hello\n\nworld", res.getFinalText());
        // Ensure captured deltas include the newline-only chunk
        assertEquals("hello\n\nworld", acc.toString());
    }

    @Test
    public void scenarioD_nameless_tool_call_is_handled_and_recovers(@TempDir Path tmp) {
        ToolCall call = new ToolCall(null, null, Map.of("path", "x"));
        ModelResponse r1 = new ModelResponse(null, call, com.judepereira.jupiter.agent.llm.dto.ModelResponseMetadata.empty());
        ModelResponse r2 = new ModelResponse("Final recovered text", null, com.judepereira.jupiter.agent.llm.dto.ModelResponseMetadata.empty());
        SequenceModel model = new SequenceModel(List.of(r1, r2));

        AgentProperties props = new AgentProperties();
        props.setMaxIterations(5);
        props.setWorkspaceRoot(tmp.toString());

        ToolRegistry reg = new ToolRegistry();

        CodingAgentHarness harness = new CodingAgentHarness(fakeFactory(model), reg, props, null, null, null, null, null, new com.judepereira.jupiter.agent.harness.SystemPromptComposer(new com.judepereira.jupiter.agent.skill.SkillCatalogRenderer()), new com.judepereira.jupiter.agent.skill.SkillDiscoveryService(new com.judepereira.jupiter.agent.skill.SkillParser(), System.getProperty("user.home")), new com.judepereira.jupiter.agent.skill.SkillInvocationResolver(), new com.judepereira.jupiter.agent.skill.SkillContextInjector(new com.judepereira.jupiter.agent.skill.SkillParser()));
        var res = harness.runTurn(new AgentTurnRequest("sys", List.of(new Message(Message.Role.USER, "user", null, null)), null, null, null, null, null, null));
        assertEquals("Final recovered text", res.getFinalText());
        assertEquals(1, res.getTraces().size());
        assertEquals("(missing_tool_name)", res.getTraces().get(0).getToolName());
        assertFalse(res.getTraces().get(0).isSuccess());
    }

    private static void assertComposedSystemPrompt(String actual, String appendage, Path workspaceRoot) {
        assertNotNull(actual);
        assertEquals(SystemPromptTestSupport.composeExpected(appendage, workspaceRoot), actual);
    }
}
