package com.judepereira.jupiter.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.judepereira.jupiter.agent.config.AgentProperties;
import com.judepereira.jupiter.agent.harness.AgentTurnRequest;
import com.judepereira.jupiter.agent.harness.CodingAgentHarness;
import com.judepereira.jupiter.agent.llm.AgentModelClient;
import com.judepereira.jupiter.agent.llm.AgentModelClientFactory;
import com.judepereira.jupiter.agent.llm.dto.Message;
import com.judepereira.jupiter.agent.llm.dto.ModelResponse;
import com.judepereira.jupiter.agent.llm.dto.ModelResponseMetadata;
import com.judepereira.jupiter.agent.llm.dto.ToolCall;
import com.judepereira.jupiter.agent.llm.dto.ToolDefinition;
import com.judepereira.jupiter.agent.tools.ToolRegistry;
import com.judepereira.jupiter.agent.tools.impl.WriteFileTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TokenUsageLifecycleAndHarnessTests {

    @Test
    void usageFactsAndHourlyRowsSurviveSessionDeletion(@TempDir Path projectPath) {
        TestAppStateSupport.AppStateTestContext context = TestAppStateSupport.appStateContext(event -> {});
        AppStateService appStateService = context.service();
        AppStateRepository repository = context.repository();
        TokenUsageService tokenUsageService = new TokenUsageService(repository, new ObjectMapper());

        appStateService.addOrReopenProject("Alpha", projectPath.toString());
        long sessionId = appStateService.loadViewData().activeSession().id();
        String usageKey = repository.findSessionUsageContext(sessionId).orElseThrow().sessionUsageKey();
        tokenUsageService.recordModelResponse(sessionId, "model-history", "harness",
                new ModelResponse("done", null, metadata(12, 8, 20)));

        appStateService.closeSession(sessionId);

        assertThat(tokenUsageService.findHourlyUsage(usageKey, Instant.EPOCH, Instant.now().plus(1, ChronoUnit.HOURS)))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.modelKey()).isEqualTo("model-history");
                    assertThat(row.requestCount()).isEqualTo(1);
                    assertThat(row.totalTokenCount()).isEqualTo(20);
                });
        assertThat(tokenUsageService.findFacts(usageKey))
                .singleElement()
                .satisfies(fact -> {
                    assertThat(fact.sessionIdSnapshot()).isEqualTo(sessionId);
                    assertThat(fact.sessionNameSnapshot()).isEqualTo("Session #1");
                    assertThat(fact.workspacePathSnapshot()).isEqualTo(projectPath.toString());
                    assertThat(fact.totalTokenCount()).isEqualTo(20);
                });
    }

    @Test
    void harnessPersistsUsageForToolAndFinalModelIterations(@TempDir Path workspacePath) {
        TestAppStateSupport.AppStateTestContext context = TestAppStateSupport.appStateContext(event -> {});
        AppStateService appStateService = context.service();
        AppStateRepository repository = context.repository();
        TokenUsageService tokenUsageService = new TokenUsageService(repository, new ObjectMapper());

        appStateService.addOrReopenProject("Alpha", workspacePath.toString());
        long sessionId = appStateService.loadViewData().activeSession().id();
        String usageKey = repository.findSessionUsageContext(sessionId).orElseThrow().sessionUsageKey();

        AgentProperties properties = new AgentProperties();
        properties.setMaxIterations(3);
        properties.setWorkspaceRoot(workspacePath.toString());
        properties.getTooling().setAllowWrite(true);
        ToolRegistry registry = new ToolRegistry();
        registry.register(new WriteFileTool());
        AgentModelClient model = new SequenceModel(List.of(
                new ModelResponse(null, new ToolCall(null, "write_file", Map.of("path", "iteration.txt", "content", "written")), metadata(10, 4, 14)),
                new ModelResponse("final response", null, metadata(15, 6, 21))));
        CodingAgentHarness harness = new CodingAgentHarness(
                fakeFactory(model), registry, properties, null, null, appStateService, tokenUsageService, null,
                new com.judepereira.jupiter.agent.harness.SystemPromptComposer(new com.judepereira.jupiter.agent.skill.SkillCatalogRenderer()), new com.judepereira.jupiter.agent.skill.SkillDiscoveryService(new com.judepereira.jupiter.agent.skill.SkillParser(), System.getProperty("user.home")), new com.judepereira.jupiter.agent.skill.SkillInvocationResolver(), new com.judepereira.jupiter.agent.skill.SkillContextInjector(new com.judepereira.jupiter.agent.skill.SkillParser()));

        AgentTurnRequest request = new AgentTurnRequest("system", List.of(new Message(Message.Role.USER, "user", null, null)), workspacePath.toString(),
                null, "model-iterations", null, sessionId, null);
        assertThat(harness.runTurn(request).getFinalText()).isEqualTo("final response");

        assertThat(tokenUsageService.findFacts(usageKey))
                .extracting(Persistence.TokenUsageFact::modelKey, Persistence.TokenUsageFact::totalTokenCount)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("model-iterations", 14),
                        org.assertj.core.groups.Tuple.tuple("model-iterations", 21));
        assertThat(tokenUsageService.findHourlyUsage(usageKey, Instant.EPOCH, Instant.now().plus(1, ChronoUnit.HOURS)))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.modelKey()).isEqualTo("model-iterations");
                    assertThat(row.requestCount()).isEqualTo(2);
                    assertThat(row.totalTokenCount()).isEqualTo(35);
                });
    }

    private static AgentModelClientFactory fakeFactory(AgentModelClient model) {
        return new AgentModelClientFactory(null, new AgentProperties()) {
            @Override
            public AgentModelClient getClient() {
                return model;
            }
        };
    }

    private static ModelResponseMetadata metadata(int input, int output, int total) {
        return new ModelResponseMetadata(input, output, total, null, null, null,
                null, "provider-model", "stop", Map.of());
    }

    private static final class SequenceModel implements AgentModelClient {
        private final List<ModelResponse> responses;
        private int nextIndex;

        private SequenceModel(List<ModelResponse> responses) {
            this.responses = responses;
        }

        @Override
        public ModelResponse chat(List<Message> conversation, List<ToolDefinition> tools) {
            return responses.get(nextIndex++);
        }

        @Override
        public ModelResponse chatStreaming(List<Message> conversation, List<ToolDefinition> tools,
                                           java.util.function.Consumer<String> onDelta) {
            return chat(conversation, tools);
        }
    }
}
