package com.judepereira.jupiter.lifecycle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.judepereira.jupiter.agent.catalog.AgentDefinition;
import com.judepereira.jupiter.agent.catalog.AgentDefinitionService;
import com.judepereira.jupiter.agent.catalog.AgentMode;
import com.judepereira.jupiter.agent.catalog.ThinkingLevel;
import com.judepereira.jupiter.agent.harness.AgentTurnRequest;
import com.judepereira.jupiter.agent.harness.AgentTurnResult;
import com.judepereira.jupiter.agent.harness.CodingAgentHarness;
import com.judepereira.jupiter.agent.harness.ToolCallTrace;
import com.judepereira.jupiter.agent.harness.StreamCancelledException;
import com.judepereira.jupiter.agent.llm.AgentStreamListener;
import com.judepereira.jupiter.agent.task.SubagentTaskService;
import com.judepereira.jupiter.persistence.AppStateService;
import com.judepereira.jupiter.persistence.TestAppStateSupport;
import com.judepereira.jupiter.testsupport.ModelCatalogTestSupport;
import com.judepereira.jupiter.ui.UiController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.ui.ConcurrentModel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class LifecycleHookIntegrationTests {

    @Test
    void primaryCompletionDispatchesAfterPersistenceWithPrimarySession(@TempDir Path workspaceRoot) throws Exception {
        LifecycleHookService hooks = hookMock();
        CodingAgentHarness harness = harness((request, listener) -> {
            listener.onComplete(new AgentTurnResult("done", List.of()));
            return new AgentTurnResult("done", List.of());
        });
        UiController controller = TestAppStateSupport.controller(harness, properties(workspaceRoot),
                ModelCatalogTestSupport.modelCatalogService(), hooks);

        String assistantId = sendAndGetAssistantId(controller);
        controller.streamChat(assistantId);
        TestAppStateSupport.awaitAssistantCompletion(controller, assistantId);

        var event = org.mockito.ArgumentCaptor.forClass(LifecycleHookService.LifecycleEvent.class);
        var session = org.mockito.ArgumentCaptor.forClass(Long.class);
        verify(hooks).dispatch(event.capture(), session.capture());
        assertThat(event.getValue()).isEqualTo(LifecycleHookService.LifecycleEvent.ASSISTANT_COMPLETED);
        assertThat(session.getValue()).isPositive();
    }

    @Test
    void primaryFailureDispatchesErroredButCancellationDoesNot(@TempDir Path workspaceRoot) throws Exception {
        LifecycleHookService errorHooks = hookMock();
        CodingAgentHarness failingHarness = harness((request, listener) -> {
            throw new IllegalStateException("provider failed");
        });
        UiController failingController = TestAppStateSupport.controller(failingHarness, properties(workspaceRoot),
                ModelCatalogTestSupport.modelCatalogService(), errorHooks);
        String failingAssistantId = sendAndGetAssistantId(failingController);
        failingController.streamChat(failingAssistantId);
        TestAppStateSupport.awaitAssistantCompletion(failingController, failingAssistantId);
        verify(errorHooks).dispatch(eq(LifecycleHookService.LifecycleEvent.ASSISTANT_ERRORED), anyLong());

        LifecycleHookService cancellationHooks = hookMock();
        CodingAgentHarness cancelledHarness = harness((request, listener) -> {
            throw new StreamCancelledException();
        });
        UiController cancelledController = TestAppStateSupport.controller(cancelledHarness, properties(workspaceRoot),
                ModelCatalogTestSupport.modelCatalogService(), cancellationHooks);
        String cancelledAssistantId = sendAndGetAssistantId(cancelledController);
        cancelledController.streamChat(cancelledAssistantId);
        TestAppStateSupport.awaitAssistantCompletion(cancelledController, cancelledAssistantId);
        verifyNoInteractions(cancellationHooks);
    }

    @Test
    void dispatchFailureCannotChangePrimaryPersistence(@TempDir Path workspaceRoot) {
        LifecycleHookService hooks = mock(LifecycleHookService.class);
        doReturn(CompletableFuture.failedFuture(new IllegalStateException("hook failed")))
                .when(hooks).dispatch(any(), anyLong());
        CodingAgentHarness harness = harness((request, listener) -> {
            listener.onComplete(new AgentTurnResult("done", List.of()));
            return new AgentTurnResult("done", List.of());
        });
        UiController controller = TestAppStateSupport.controller(harness, properties(workspaceRoot),
                ModelCatalogTestSupport.modelCatalogService(), hooks);

        String assistantId = sendAndGetAssistantId(controller);
        controller.streamChat(assistantId);
        assertThat(TestAppStateSupport.awaitAssistantCompletion(controller, assistantId).text()).isEqualTo("done");
    }

    @Test
    void subagentDispatchesAfterChangedFilePersistence(@TempDir Path workspaceRoot) throws Exception {
        AppStateService appStateService = spy(appState(workspaceRoot));
        long parentSessionId = appStateService.loadViewData().activeSession().id();
        LifecycleHookService hooks = hookMock();
        doAnswer(invocation -> CompletableFuture.completedFuture(null)).when(hooks).dispatch(any(), anyLong());

        Path changedFile = workspaceRoot.resolve("changed.txt");
        Files.writeString(changedFile, "changed");
        CodingAgentHarness harness = harness((request, listener) -> {
            ToolCallTrace trace = new ToolCallTrace("tool", "write_file", Map.of("path", "changed.txt"), true, "done", Map.of("path", "changed.txt"));
            listener.onToolCallTrace(trace);
            AgentTurnResult result = new AgentTurnResult("done", List.of(trace));
            listener.onComplete(result);
            return result;
        });
        SubagentTaskService service = new SubagentTaskService(appStateService, definitions(subagent()), provider(harness), hooks);
        service.runTask(request(parentSessionId, workspaceRoot));

        var order = inOrder(appStateService, hooks);
        order.verify(appStateService, times(2)).addChangedFilesToSession(anyLong(), any());
        order.verify(hooks).dispatch(eq(LifecycleHookService.LifecycleEvent.SUBAGENT_COMPLETED), eq(parentSessionId));
    }

    @Test
    void subagentErrorDispatchesAfterChangedFilePersistence(@TempDir Path workspaceRoot) throws Exception {
        AppStateService appStateService = spy(appState(workspaceRoot));
        long parentSessionId = appStateService.loadViewData().activeSession().id();
        LifecycleHookService hooks = hookMock();
        CodingAgentHarness harness = harness((request, listener) -> {
            listener.onToolCallTrace(new ToolCallTrace("tool", "write_file", Map.of("path", "changed.txt"), true,
                    "done", Map.of("path", "changed.txt")));
            throw new IllegalStateException("child failed");
        });
        Files.writeString(workspaceRoot.resolve("changed.txt"), "changed");

        SubagentTaskService service = new SubagentTaskService(appStateService, definitions(subagent()), provider(harness), hooks);
        var result = service.runTask(request(parentSessionId, workspaceRoot));

        assertThat(result.success()).isFalse();
        var order = inOrder(appStateService, hooks);
        order.verify(appStateService, times(2)).addChangedFilesToSession(anyLong(), any());
        order.verify(hooks).dispatch(eq(LifecycleHookService.LifecycleEvent.SUBAGENT_COMPLETED), eq(parentSessionId));
    }

    @Test
    void subagentSuccessAndErrorDispatchOnceToParentSession(@TempDir Path workspaceRoot) {
        AppStateService appStateService = appState(workspaceRoot);
        long parentSessionId = appStateService.loadViewData().activeSession().id();
        AgentDefinition subagent = subagent();
        AgentDefinitionService definitions = definitions(subagent);

        LifecycleHookService successHooks = hookMock();
        CodingAgentHarness successHarness = harness((request, listener) -> {
            AgentTurnResult result = new AgentTurnResult("child done", List.of());
            listener.onComplete(result);
            return result;
        });
        SubagentTaskService successService = new SubagentTaskService(appStateService, definitions,
                provider(successHarness), successHooks);
        var success = successService.runTask(request(parentSessionId, workspaceRoot));
        assertThat(success.success()).isTrue();
        verify(successHooks).dispatch(eq(LifecycleHookService.LifecycleEvent.SUBAGENT_COMPLETED), eq(parentSessionId));
        verify(successHooks, org.mockito.Mockito.never()).dispatch(
                anyOf(LifecycleHookService.LifecycleEvent.ASSISTANT_COMPLETED, LifecycleHookService.LifecycleEvent.ASSISTANT_ERRORED), anyLong());

        LifecycleHookService errorHooks = hookMock();
        CodingAgentHarness errorHarness = harness((request, listener) -> {
            throw new IllegalStateException("child failed");
        });
        SubagentTaskService errorService = new SubagentTaskService(appStateService, definitions,
                provider(errorHarness), errorHooks);
        var error = errorService.runTask(request(parentSessionId, workspaceRoot));
        assertThat(error.success()).isFalse();
        verify(errorHooks).dispatch(eq(LifecycleHookService.LifecycleEvent.SUBAGENT_COMPLETED), eq(parentSessionId));
    }

    @Test
    void cancelledSubagentDoesNotDispatch(@TempDir Path workspaceRoot) {
        AppStateService appStateService = appState(workspaceRoot);
        long parentSessionId = appStateService.loadViewData().activeSession().id();
        LifecycleHookService hooks = hookMock();
        CodingAgentHarness harness = harness((request, listener) -> {
            throw new StreamCancelledException();
        });
        SubagentTaskService service = new SubagentTaskService(appStateService, definitions(subagent()),
                provider(harness), hooks);

        var result = service.runTask(request(parentSessionId, workspaceRoot));

        assertThat(result.success()).isFalse();
        verifyNoInteractions(hooks);
    }

    private static LifecycleHookService hookMock() {
        LifecycleHookService hooks = mock(LifecycleHookService.class);
        doReturn(CompletableFuture.completedFuture(null)).when(hooks).dispatch(any(), anyLong());
        return hooks;
    }

    private static UiController controller(CodingAgentHarness harness, Path workspaceRoot, LifecycleHookService hooks) {
        return TestAppStateSupport.controller(harness, properties(workspaceRoot), ModelCatalogTestSupport.modelCatalogService(), hooks);
    }

    private static String sendAndGetAssistantId(UiController controller) {
        ConcurrentModel model = new ConcurrentModel();
        controller.sendMessage("go", model, null);
        List<?> messages = (List<?>) model.getAttribute("chatMessages");
        return ((com.judepereira.jupiter.ui.ChatPresentationService.ChatMessage) messages.get(messages.size() - 1)).id();
    }

    private static com.judepereira.jupiter.agent.config.AgentProperties properties(Path workspaceRoot) {
        var properties = new com.judepereira.jupiter.agent.config.AgentProperties();
        properties.setWorkspaceRoot(workspaceRoot.toString());
        return properties;
    }

    private static AppStateService appState(Path workspaceRoot) {
        AppStateService service = TestAppStateSupport.appStateService();
        service.addOrReopenProject("Project", workspaceRoot.toString());
        return service;
    }

    private static AgentDefinition subagent() {
        return new AgentDefinition("worker", "Worker", "", "worker prompt", AgentMode.SUBAGENT,
                "openai/gpt-5.5", ThinkingLevel.LOW, null, true, true, List.of());
    }

    private static AgentDefinitionService definitions(AgentDefinition subagent) {
        return new AgentDefinitionService(new ObjectMapper()) {
            @Override
            public AgentDefinition getRequired(String id) {
                return subagent;
            }
        };
    }

    private static SubagentTaskService.SubagentTaskRequest request(long parentSessionId, Path workspaceRoot) {
        return new SubagentTaskService.SubagentTaskRequest(parentSessionId, "parent-tool", workspaceRoot.toString(),
                "worker", "summary", "task", "output", null);
    }

    private static ObjectProvider<CodingAgentHarness> provider(CodingAgentHarness harness) {
        return new ObjectProvider<>() {
            @Override
            public CodingAgentHarness getObject() {
                return harness;
            }

            @Override
            public CodingAgentHarness getObject(Object... args) {
                return harness;
            }
        };
    }

    @FunctionalInterface
    private interface HarnessBehavior {
        AgentTurnResult run(AgentTurnRequest request, AgentStreamListener listener);
    }

    private static CodingAgentHarness harness(HarnessBehavior behavior) {
        return new CodingAgentHarness(null, null, null, null, null, null, null, null, new com.judepereira.jupiter.agent.harness.SystemPromptComposer(com.judepereira.jupiter.testsupport.SkillTestSupport.defaultComponents().renderer()), com.judepereira.jupiter.testsupport.SkillTestSupport.defaultComponents().discovery(), com.judepereira.jupiter.testsupport.SkillTestSupport.defaultComponents().resolver(), com.judepereira.jupiter.testsupport.SkillTestSupport.defaultComponents().injector()) {
            @Override
            public AgentTurnResult runTurnStreaming(AgentTurnRequest request, AgentStreamListener listener) {
                return behavior.run(request, listener);
            }
        };
    }

    private static LifecycleHookService.LifecycleEvent anyOf(LifecycleHookService.LifecycleEvent first,
                                                               LifecycleHookService.LifecycleEvent second) {
        return org.mockito.ArgumentMatchers.argThat(event -> event == first || event == second);
    }
}
