package com.judepereira.jupiter.agent.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.judepereira.jupiter.agent.catalog.AgentDefinition;
import com.judepereira.jupiter.agent.catalog.AgentDefinitionService;
import com.judepereira.jupiter.agent.catalog.AgentMode;
import com.judepereira.jupiter.agent.catalog.ThinkingLevel;
import com.judepereira.jupiter.agent.harness.AgentTurnRequest;
import com.judepereira.jupiter.agent.harness.AgentTurnResult;
import com.judepereira.jupiter.agent.harness.CodingAgentHarness;
import com.judepereira.jupiter.agent.harness.ToolCallTrace;
import com.judepereira.jupiter.agent.llm.AgentStreamListener;
import com.judepereira.jupiter.agent.tools.ToolExecutionContext;
import com.judepereira.jupiter.agent.tools.ToolExecutionResult;
import com.judepereira.jupiter.agent.tools.ToolProgressSink;
import com.judepereira.jupiter.agent.tools.impl.TaskTool;
import com.judepereira.jupiter.persistence.AppStateService;
import com.judepereira.jupiter.persistence.Persistence.ChatMessageView;
import com.judepereira.jupiter.persistence.Persistence.SubagentSessionDetailView;
import com.judepereira.jupiter.persistence.TestAppStateSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public class TaskToolAndSubagentServiceTests {

    @Test
    public void taskToolCreatesHiddenChildSessionPersistsChildTranscriptAndReturnsMetadata(@TempDir Path workspaceRoot) {
        AppStateService appStateService = TestAppStateSupport.appStateService();
        appStateService.addOrReopenProject("Alpha", workspaceRoot.toString());
        long parentSessionId = appStateService.loadViewData().activeSession().id();

        AgentDefinition subagent = new AgentDefinition("engineer", "Engineer", "", "Subagent system prompt", AgentMode.SUBAGENT,
                "openai/gpt-5.5", ThinkingLevel.MEDIUM, "low", true, true, List.of("write_file"));
        AgentDefinitionService agentDefinitionService = agentService(subagent);

        CodingAgentHarness childHarness = new CodingAgentHarness(null, null, null) {
            @Override
            public AgentTurnResult runTurnStreaming(AgentTurnRequest request, AgentStreamListener listener) {
                assertThat(request.getAgentId()).isEqualTo("engineer");
                ToolCallTrace trace = new ToolCallTrace("child-tool-1", "write_file", Map.of("path", "child.txt", "content", "hello"), true,
                        "wrote child.txt", Map.of("path", "child.txt"));
                listener.onTextDelta("child final");
                listener.onToolCallTrace(trace);
                AgentTurnResult result = new AgentTurnResult("child final", List.of(trace));
                listener.onComplete(result);
                return result;
            }
        };

        SubagentTaskService service = new SubagentTaskService(appStateService, agentDefinitionService, childHarnessProvider(childHarness));
        TaskTool taskTool = new TaskTool(agentDefinitionService, service);

        ToolExecutionResult result = taskTool.execute(Map.of(
                "agentId", "engineer",
                "task", "write a file",
                "expectedOutput", "child final"
        ), new ToolExecutionContext(workspaceRoot, false, false, 30, parentSessionId, "parent-tool-call", AgentMode.AGENT, "parent-tool-call"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getText()).isEqualTo("child final");
        assertThat(result.getMachine()).containsEntry("subagentAgentId", "engineer")
                .containsEntry("subagentAgentName", "Engineer");
        Long childSessionId = (Long) result.getMachine().get("subagentSessionId");
        assertThat(childSessionId).isNotNull();

        SubagentSessionDetailView child = appStateService.loadSubagentSessionDetail(childSessionId);
        assertThat(child.parentSessionId()).isEqualTo(parentSessionId);
        assertThat(child.parentToolCallId()).isEqualTo("parent-tool-call");
        assertThat(child.subagentAgentId()).isEqualTo("engineer");
        assertThat(child.subagentAgentName()).isEqualTo("Engineer");

        List<ChatMessageView> childMessages = child.sessionDetail().chatMessages();
        assertThat(childMessages).isNotEmpty();
        assertThat(childMessages).anySatisfy(message -> assertThat(message.text()).contains("Primary task:\nwrite a file"));
        assertThat(childMessages).anySatisfy(message -> assertThat(message.text()).isEqualTo("child final"));
        assertThat(childMessages).anySatisfy(message -> assertThat(message.toolCalls())
                .isNotEmpty()
                .extracting(call -> call.outputPreview())
                .anySatisfy(output -> assertThat(output).contains("wrote child.txt")));

        assertThat(appStateService.loadViewData().sessions()).hasSize(1);
    }

    @Test
    public void taskToolStreamsSubagentLifecycleThroughTheToolProgressSink(@TempDir Path workspaceRoot) {
        AppStateService appStateService = TestAppStateSupport.appStateService();
        appStateService.addOrReopenProject("Alpha", workspaceRoot.toString());
        long parentSessionId = appStateService.loadViewData().activeSession().id();

        AgentDefinition subagent = new AgentDefinition("engineer", "Engineer", "", "Subagent system prompt", AgentMode.SUBAGENT,
                "openai/gpt-5.5", ThinkingLevel.MEDIUM, "low", true, true, List.of("write_file"));
        AgentDefinitionService agentDefinitionService = agentService(subagent);

        CodingAgentHarness childHarness = new CodingAgentHarness(null, null, null) {
            @Override
            public AgentTurnResult runTurnStreaming(AgentTurnRequest request, AgentStreamListener listener) {
                ToolCallTrace trace = new ToolCallTrace("child-tool-1", "write_file", Map.of("path", "child.txt", "content", "hello"), true,
                        "wrote child.txt", Map.of("path", "child.txt"));
                listener.onTextDelta("child final");
                listener.onToolCallTrace(trace);
                AgentTurnResult result = new AgentTurnResult("child final", List.of(trace));
                listener.onComplete(result);
                return result;
            }
        };

        SubagentTaskService service = new SubagentTaskService(appStateService, agentDefinitionService, childHarnessProvider(childHarness));
        TaskTool taskTool = new TaskTool(agentDefinitionService, service);

        List<String> events = new ArrayList<>();
        ToolProgressSink sink = (eventName, payload) -> {
            switch (eventName) {
                case "subagent_started" -> events.add("started:" + ((SubagentTaskService.SubagentTaskStarted) payload).subagentAgentName());
                case "subagent_delta" -> events.add("delta:" + ((SubagentTaskService.SubagentTaskTextDelta) payload).delta());
                case "subagent_tool_call" -> {
                    SubagentTaskService.SubagentTaskToolCall event = (SubagentTaskService.SubagentTaskToolCall) payload;
                    events.add("tool_call:" + event.toolName() + ":" + event.outputPreview());
                }
                case "subagent_done" -> events.add("done:" + ((SubagentTaskService.SubagentTaskCompleted) payload).finalText());
                case "subagent_error" -> events.add("error:" + ((SubagentTaskService.SubagentTaskError) payload).errorText());
                default -> throw new IllegalStateException("Unexpected event: " + eventName);
            }
        };

        ToolExecutionResult result = taskTool.execute(Map.of(
                "agentId", "engineer",
                "task", "write a file",
                "expectedOutput", "child final"
        ), new ToolExecutionContext(workspaceRoot, false, false, 30, parentSessionId, "parent-tool-call", AgentMode.AGENT, "parent-tool-call", sink));

        assertThat(result.isSuccess()).isTrue();

        assertThat(events).containsExactly(
                "started:Engineer",
                "delta:child final",
                "tool_call:write_file:wrote child.txt",
                "done:child final"
        );
    }

    private static ObjectProvider<CodingAgentHarness> childHarnessProvider(CodingAgentHarness harness) {
        return new ObjectProvider<>() {
            @Override
            public CodingAgentHarness getObject() {
                return harness;
            }

            @Override
            public CodingAgentHarness getObject(Object... args) {
                return harness;
            }

            @Override
            public CodingAgentHarness getIfAvailable() {
                return harness;
            }
        };
    }

    private static AgentDefinitionService agentService(AgentDefinition subagent) {
        return new AgentDefinitionService(new ObjectMapper()) {
            @Override
            public List<AgentDefinition> list() {
                return List.of(subagent);
            }

            @Override
            public List<AgentDefinition> listSubagents() {
                return List.of(subagent);
            }

            @Override
            public AgentDefinition getRequired(String id) {
                return subagent;
            }

            @Override
            public AgentDefinition defaultAgent() {
                return subagent;
            }
        };
    }
}
