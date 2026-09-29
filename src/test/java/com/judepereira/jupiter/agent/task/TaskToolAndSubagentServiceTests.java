package com.judepereira.jupiter.agent.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.judepereira.jupiter.agent.catalog.AgentCatalogSnapshot;
import com.judepereira.jupiter.agent.catalog.AgentDefinition;
import com.judepereira.jupiter.agent.catalog.AgentDefinitionService;
import com.judepereira.jupiter.agent.catalog.AgentMode;
import com.judepereira.jupiter.agent.catalog.ExternalAgentCatalogService;
import com.judepereira.jupiter.agent.catalog.ThinkingLevel;
import com.judepereira.jupiter.agent.config.AgentProperties;
import com.judepereira.jupiter.agent.harness.AgentTurnRequest;
import com.judepereira.jupiter.agent.harness.AgentTurnResult;
import com.judepereira.jupiter.agent.harness.CodingAgentHarness;
import com.judepereira.jupiter.agent.harness.SystemPromptComposer;
import com.judepereira.jupiter.agent.harness.ToolCallTrace;
import com.judepereira.jupiter.agent.llm.AgentModelClient;
import com.judepereira.jupiter.agent.llm.AgentModelClientFactory;
import com.judepereira.jupiter.agent.llm.AgentStreamListener;
import com.judepereira.jupiter.agent.llm.dto.Message;
import com.judepereira.jupiter.agent.llm.dto.ModelResponse;
import com.judepereira.jupiter.agent.llm.dto.ModelResponseMetadata;
import com.judepereira.jupiter.agent.llm.dto.ToolParameter;
import com.judepereira.jupiter.agent.tools.ToolExecutionContext;
import com.judepereira.jupiter.agent.tools.ToolExecutionResult;
import com.judepereira.jupiter.agent.tools.ToolProgressSink;
import com.judepereira.jupiter.agent.tools.ToolRegistry;
import com.judepereira.jupiter.agent.tools.impl.TaskTool;
import com.judepereira.jupiter.persistence.AppStateService;
import com.judepereira.jupiter.persistence.Persistence.ChatMessageView;
import com.judepereira.jupiter.persistence.Persistence.SubagentSessionDetailView;
import com.judepereira.jupiter.persistence.TestAppStateSupport;
import com.judepereira.jupiter.testsupport.SkillTestSupport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.ObjectProvider;

public class TaskToolAndSubagentServiceTests {

    private static AgentCatalogSnapshot catalog(AgentDefinition subagent) {
        AgentDefinition caller = new AgentDefinition("parent", "Parent", "", "", AgentMode.AGENT, subagent.modelIds(),
                ThinkingLevel.MEDIUM, "low", true, true, List.of("write_file", "task"));
        return new AgentCatalogSnapshot(List.of(caller, subagent), Map.of(), List.of(), Map.of());
    }

    private static ToolExecutionContext context(Path workspace, long sessionId, String toolCallId,
            AgentCatalogSnapshot snapshot) {
        ToolExecutionContext context = new ToolExecutionContext(workspace, false, false, 30, sessionId, "parent",
                AgentMode.AGENT, toolCallId, Map.of(), Set.of(), ToolProgressSink.noop(), null);
        context.setRuntimeContext(snapshot, snapshot.getRequired("parent"));
        return context;
    }

    private static ToolExecutionContext contextWithSink(Path workspace, long sessionId, String toolCallId,
            AgentCatalogSnapshot snapshot, ToolProgressSink sink) {
        ToolExecutionContext context = new ToolExecutionContext(workspace, false, false, 30, sessionId, "parent",
                AgentMode.AGENT, toolCallId, Map.of(), Set.of(), sink, null);
        context.setRuntimeContext(snapshot, snapshot.getRequired("parent"));
        return context;
    }

    private static ExternalAgentCatalogService externalCatalog(AgentCatalogSnapshot snapshot) {
        ExternalAgentCatalogService service = mock(ExternalAgentCatalogService.class);
        when(service.snapshot(ArgumentMatchers.any(Path.class))).thenReturn(snapshot);
        return service;
    }

    @Test
    public void taskToolCreatesHiddenChildSessionPersistsChildTranscriptAndReturnsMetadata(
            @TempDir Path workspaceRoot) {
        AppStateService appStateService = TestAppStateSupport.appStateService();
        appStateService.addOrReopenProject("Alpha", workspaceRoot.toString());
        long parentSessionId = appStateService.loadViewData().activeSession().id();

        AgentDefinition subagent = new AgentDefinition("engineer", "Engineer", "", "Subagent system prompt",
                AgentMode.SUBAGENT, "openai/gpt-5.6-sol", ThinkingLevel.MEDIUM, "low", true, true,
                List.of("write_file"));
        AgentDefinitionService agentDefinitionService = agentService(subagent);

        CodingAgentHarness childHarness = new CodingAgentHarness(null, null, null, null, null, null, null, null, null,
                null, new SystemPromptComposer(SkillTestSupport.defaultComponents().renderer()),
                SkillTestSupport.defaultComponents().discovery(), SkillTestSupport.defaultComponents().resolver(),
                SkillTestSupport.defaultComponents().injector()) {
            @Override
            public AgentTurnResult runTurnStreaming(AgentTurnRequest request, AgentStreamListener listener) {
                assertThat(request.getAgentId()).isEqualTo("engineer");
                ToolCallTrace trace = new ToolCallTrace("child-tool-1", "write_file",
                        Map.of("path", "child.txt", "content", "hello"), true, "wrote child.txt",
                        Map.of("path", "child.txt"));
                listener.onTextDelta("child final");
                listener.onToolCallTrace(trace);
                AgentTurnResult result = new AgentTurnResult("child final", List.of(trace));
                listener.onComplete(result);
                return result;
            }
        };

        SubagentTaskService service = new SubagentTaskService(appStateService, agentDefinitionService,
                externalCatalog(catalog(subagent)), childHarnessProvider(childHarness), null);
        TaskTool taskTool = new TaskTool(agentDefinitionService, service);

        ToolExecutionResult result = taskTool.execute(
                Map.of("agentId", "engineer", "requestSummary", "Create the parser file", "task", "write a file",
                        "expectedOutput", "child final"),
                context(workspaceRoot, parentSessionId, "parent-tool-call", catalog(subagent)));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getText()).isEqualTo("child final");
        assertThat(result.getMachine()).containsEntry("subagentAgentId", "engineer").containsEntry("subagentAgentName",
                "Engineer");
        Long childSessionId = (Long) result.getMachine().get("subagentSessionId");
        assertThat(childSessionId).isNotNull();

        SubagentSessionDetailView child = appStateService.loadSubagentSessionDetail(childSessionId);
        assertThat(child.parentSessionId()).isEqualTo(parentSessionId);
        assertThat(child.parentToolCallId()).isEqualTo("parent-tool-call");
        assertThat(child.subagentAgentId()).isEqualTo("engineer");
        assertThat(child.subagentAgentName()).isEqualTo("Engineer");

        List<ChatMessageView> childMessages = child.sessionDetail().chatMessages();
        assertThat(childMessages).isNotEmpty();
        assertThat(childMessages)
                .anySatisfy(message -> assertThat(message.text()).contains("Primary task:\nwrite a file"));
        assertThat(childMessages).anySatisfy(message -> assertThat(message.text()).isEqualTo("child final"));
        assertThat(childMessages).anySatisfy(
                message -> assertThat(message.toolCalls()).isNotEmpty().extracting(call -> call.outputPreview())
                        .anySatisfy(output -> assertThat(output).contains("wrote child.txt")));
        assertThat(childMessages).allSatisfy(message -> assertThat(message.text()).doesNotContain("<skill>"));

        assertThat(appStateService.loadViewData().sessions()).hasSize(1);
    }

    @Test
    public void childHarnessUsesWorkspaceSkillMetadataAndEphemeralExplicitBody(@TempDir Path workspaceRoot)
            throws Exception {
        Path skill = workspaceRoot.resolve(".agents/skills/release/SKILL.md");
        Files.createDirectories(skill.getParent());
        Files.writeString(skill, "---\nname: release\ndescription: release workflow\n---\nFULL RELEASE BODY");
        AppStateService appStateService = TestAppStateSupport.appStateService();
        appStateService.addOrReopenProject("Alpha", workspaceRoot.toString());
        long parentSessionId = appStateService.loadViewData().activeSession().id();
        AgentDefinition subagent = new AgentDefinition("engineer", "Engineer", "", "Subagent system prompt",
                AgentMode.SUBAGENT, "openai/gpt-5.6-sol", ThinkingLevel.MEDIUM, "low", true, true, List.of());
        AgentDefinitionService definitions = agentService(subagent);
        List<List<Message>> captured = new ArrayList<>();
        AgentModelClient model = (conversation, tools) -> {
            captured.add(List.copyOf(conversation));
            return new ModelResponse("done", null, ModelResponseMetadata.empty(), null);
        };
        CodingAgentHarness childHarness = realHarness(model, workspaceRoot);

        SubagentTaskService service = new SubagentTaskService(appStateService, definitions,
                externalCatalog(catalog(subagent)), childHarnessProvider(childHarness), null);
        TaskTool taskTool = new TaskTool(definitions, service);

        var firstResult = taskTool
                .execute(
                        Map.of("agentId", "engineer", "requestSummary", "release", "task", "use $release",
                                "expectedOutput", "done"),
                        context(workspaceRoot, parentSessionId, "tool-explicit", catalog(subagent)));
        assertThat(captured).hasSize(1);
        assertThat(captured.getFirst().getFirst().getContent()).contains("<available_skills>")
                .contains("release workflow");
        assertThat(captured.getFirst())
                .anySatisfy(message -> assertThat(message.getContent()).contains("FULL RELEASE BODY"));
        assertThat(captured.getFirst())
                .anySatisfy(message -> assertThat(message.getContent()).contains("Primary task:\nuse $release"));

        taskTool.execute(Map.of("agentId", "engineer", "requestSummary", "metadata", "task", "inspect skills",
                "expectedOutput", "done"), context(workspaceRoot, parentSessionId, "tool-metadata", catalog(subagent)));
        assertThat(captured).hasSize(2);
        assertThat(captured.get(1).getFirst().getContent()).contains("<available_skills>");
        assertThat(captured.get(1))
                .noneSatisfy(message -> assertThat(message.getContent()).contains("FULL RELEASE BODY"));
        assertThat(appStateService.loadSessionDetail(parentSessionId).chatMessages()).allSatisfy(
                message -> assertThat(message.text()).doesNotContain("<skill>").doesNotContain("FULL RELEASE BODY"));
    }

    private static CodingAgentHarness realHarness(AgentModelClient model, Path workspace) {
        AgentProperties props = new AgentProperties();
        props.setMaxIterations(1);
        props.setWorkspaceRoot(workspace.toString());
        AgentModelClientFactory factory = new AgentModelClientFactory(null) {
            @Override
            public AgentModelClient getClient(String provider) {
                return model;
            }
        };
        props.setModel("openai/gpt-5.6-sol");
        return new CodingAgentHarness(factory, new ToolRegistry(), props, null, null, null, null, null, null, null,
                new SystemPromptComposer(SkillTestSupport.defaultComponents().renderer()),
                SkillTestSupport.components(workspace).discovery(), SkillTestSupport.defaultComponents().resolver(),
                SkillTestSupport.defaultComponents().injector());
    }

    @Test
    public void taskToolRequiresRequestSummaryForNewCalls(@TempDir Path workspaceRoot) {
        AppStateService appStateService = TestAppStateSupport.appStateService();
        appStateService.addOrReopenProject("Alpha", workspaceRoot.toString());
        long parentSessionId = appStateService.loadViewData().activeSession().id();

        AgentDefinition subagent = new AgentDefinition("engineer", "Engineer", "", "Subagent system prompt",
                AgentMode.SUBAGENT, "openai/gpt-5.6-sol", ThinkingLevel.MEDIUM, "low", true, true,
                List.of("write_file"));
        AgentDefinitionService agentDefinitionService = agentService(subagent);
        SubagentTaskService service = new SubagentTaskService(appStateService, agentDefinitionService,
                externalCatalog(catalog(subagent)), childHarnessProvider(null), null);
        TaskTool taskTool = new TaskTool(agentDefinitionService, service);

        ToolExecutionResult result = taskTool
                .execute(Map.of("agentId", "engineer", "task", "write a file", "expectedOutput", "child final"),
                        new ToolExecutionContext(workspaceRoot, false, false, 30, parentSessionId, "parent-tool-call",
                                AgentMode.AGENT, "parent-tool-call", Map.of(), Set.of(), ToolProgressSink.noop(),
                                null));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getText()).contains("missing required task tool arguments");
    }

    @Test
    public void taskToolDescriptionIncludesOnlySubagentsFromExternalSnapshot() {
        AgentDefinition subagent = new AgentDefinition("engineer", "Engineer", "subagent description", "",
                AgentMode.SUBAGENT, "openai/gpt-5.5", ThinkingLevel.MEDIUM, "low", true, true, List.of());
        AgentDefinition primary = new AgentDefinition("primary", "Primary", "primary description", "", AgentMode.AGENT,
                "openai/gpt-5.5", ThinkingLevel.MEDIUM, "low", true, true, List.of("task"));
        AgentDefinition parent = new AgentDefinition("parent", "Parent", "", "", AgentMode.AGENT, "openai/gpt-5.5",
                ThinkingLevel.MEDIUM, "low", true, true, List.of("task"));
        AgentCatalogSnapshot snapshot = new AgentCatalogSnapshot(List.of(parent, primary, subagent), Map.of(),
                List.of(), Map.of());
        AgentDefinitionService agentDefinitionService = agentService(subagent);
        TaskTool taskTool = new TaskTool(agentDefinitionService, new SubagentTaskService(null, agentDefinitionService,
                externalCatalog(snapshot), childHarnessProvider(null), null));

        assertThat(taskTool.definition(snapshot).getDescription()).contains("Engineer (engineer)")
                .doesNotContain("Primary (primary)");
    }

    @Test
    public void taskToolRejectsExternalPrimaryTarget() {
        AgentDefinition subagent = new AgentDefinition("engineer", "Engineer", "", "", AgentMode.SUBAGENT,
                "openai/gpt-5.5", ThinkingLevel.MEDIUM, "low", true, true, List.of());
        AgentDefinition primary = new AgentDefinition("primary", "Primary", "", "", AgentMode.AGENT, "openai/gpt-5.5",
                ThinkingLevel.MEDIUM, "low", true, true, List.of("task"));
        AgentDefinition parent = new AgentDefinition("parent", "Parent", "", "", AgentMode.AGENT, "openai/gpt-5.5",
                ThinkingLevel.MEDIUM, "low", true, true, List.of("task"));
        AgentCatalogSnapshot snapshot = new AgentCatalogSnapshot(List.of(parent, primary, subagent), Map.of(),
                List.of(), Map.of());
        AgentDefinitionService agentDefinitionService = agentService(subagent);
        TaskTool taskTool = new TaskTool(agentDefinitionService, new SubagentTaskService(null, agentDefinitionService,
                externalCatalog(snapshot), childHarnessProvider(null), null));

        ToolExecutionResult result = taskTool.execute(Map.of("agentId", "primary", "requestSummary", "run primary",
                "task", "do work", "expectedOutput", "done"), context(Path.of("."), 1L, "tool-call", snapshot));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getText()).contains("Target agent is not a subagent: primary");
    }

    @Test
    public void taskToolSchemaRequiresRequestSummary() {
        AgentDefinition subagent = new AgentDefinition("engineer", "Engineer", "", "Subagent system prompt",
                AgentMode.SUBAGENT, "openai/gpt-5.5", ThinkingLevel.MEDIUM, "low", true, true, List.of("write_file"));
        AgentDefinitionService agentDefinitionService = agentService(subagent);
        TaskTool taskTool = new TaskTool(agentDefinitionService, new SubagentTaskService(null, agentDefinitionService,
                externalCatalog(catalog(subagent)), childHarnessProvider(null), null));

        var schema = taskTool.definition().getSchema();
        assertThat(schema.properties()).extracting(ToolParameter::name).contains("agentId", "requestSummary", "task",
                "expectedOutput");
        assertThat(schema.required()).containsExactly("agentId", "requestSummary", "task", "expectedOutput");
    }

    @Test
    public void taskToolStreamsRequestSummaryThroughTheToolProgressSink(@TempDir Path workspaceRoot) {
        AppStateService appStateService = TestAppStateSupport.appStateService();
        appStateService.addOrReopenProject("Alpha", workspaceRoot.toString());
        long parentSessionId = appStateService.loadViewData().activeSession().id();

        AgentDefinition subagent = new AgentDefinition("engineer", "Engineer", "", "Subagent system prompt",
                AgentMode.SUBAGENT, "openai/gpt-5.5", ThinkingLevel.MEDIUM, "low", true, true, List.of("write_file"));
        AgentDefinitionService agentDefinitionService = agentService(subagent);

        CodingAgentHarness childHarness = new CodingAgentHarness(null, null, null, null, null, null, null, null, null,
                null, new SystemPromptComposer(SkillTestSupport.defaultComponents().renderer()),
                SkillTestSupport.defaultComponents().discovery(), SkillTestSupport.defaultComponents().resolver(),
                SkillTestSupport.defaultComponents().injector()) {
            @Override
            public AgentTurnResult runTurnStreaming(AgentTurnRequest request, AgentStreamListener listener) {
                ToolCallTrace trace = new ToolCallTrace("child-tool-1", "write_file",
                        Map.of("path", "child.txt", "content", "hello"), true, "wrote child.txt",
                        Map.of("path", "child.txt"));
                listener.onTextDelta("child final");
                listener.onToolCallTrace(trace);
                AgentTurnResult result = new AgentTurnResult("child final", List.of(trace));
                listener.onComplete(result);
                return result;
            }
        };

        SubagentTaskService service = new SubagentTaskService(appStateService, agentDefinitionService,
                externalCatalog(catalog(subagent)), childHarnessProvider(childHarness), null);
        TaskTool taskTool = new TaskTool(agentDefinitionService, service);

        List<String> events = new ArrayList<>();
        ToolProgressSink sink = (eventName, payload) -> {
            switch (eventName) {
                case "subagent_started" -> {
                    SubagentTaskService.SubagentTaskStarted event = (SubagentTaskService.SubagentTaskStarted) payload;
                    events.add("started:" + event.subagentAgentName() + ':' + event.requestSummary());
                }
                case "subagent_delta" ->
                    events.add("delta:" + ((SubagentTaskService.SubagentTaskTextDelta) payload).delta());
                case "subagent_tool_call" -> {
                    SubagentTaskService.SubagentTaskToolCall event = (SubagentTaskService.SubagentTaskToolCall) payload;
                    events.add("tool_call:" + event.toolName() + ":" + event.outputPreview());
                }
                case "subagent_done" ->
                    events.add("done:" + ((SubagentTaskService.SubagentTaskCompleted) payload).finalText());
                case "subagent_error" ->
                    events.add("error:" + ((SubagentTaskService.SubagentTaskError) payload).errorText());
                default -> throw new IllegalStateException("Unexpected event: " + eventName);
            }
        };

        ToolExecutionResult result = taskTool.execute(
                Map.of("agentId", "engineer", "requestSummary", "Create the parser file", "task", "write a file",
                        "expectedOutput", "child final"),
                contextWithSink(workspaceRoot, parentSessionId, "parent-tool-call", catalog(subagent), sink));

        assertThat(result.isSuccess()).isTrue();
        assertThat(events).containsExactly("started:Engineer:Create the parser file", "delta:child final",
                "tool_call:write_file:wrote child.txt", "done:child final");
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
