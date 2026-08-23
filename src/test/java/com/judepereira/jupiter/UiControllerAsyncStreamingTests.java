package com.judepereira.jupiter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.judepereira.jupiter.agent.catalog.AgentDefinition;
import com.judepereira.jupiter.agent.catalog.AgentDefinitionService;
import com.judepereira.jupiter.agent.catalog.AgentMode;
import com.judepereira.jupiter.agent.harness.AgentTurnRequest;
import com.judepereira.jupiter.agent.harness.AgentTurnResult;
import com.judepereira.jupiter.agent.harness.CodingAgentHarness;
import com.judepereira.jupiter.agent.harness.StreamCancelledException;
import com.judepereira.jupiter.agent.catalog.ThinkingLevel;
import com.judepereira.jupiter.agent.llm.dto.Message;
import com.judepereira.jupiter.command.CommandStreamService;
import com.judepereira.jupiter.persistence.ContextCompactionService;
import com.judepereira.jupiter.persistence.AppStateService;
import com.judepereira.jupiter.ui.UiController;
import com.judepereira.jupiter.persistence.TestAppStateSupport;
import com.judepereira.jupiter.testsupport.ModelCatalogTestSupport;
import com.judepereira.jupiter.terminal.TerminalManager;
import com.judepereira.jupiter.terminal.TerminalStateService;
import com.judepereira.jupiter.ui.balloon.SystemBalloonService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ui.Model;
import org.springframework.ui.ConcurrentModel;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

public class UiControllerAsyncStreamingTests {

    private static List<String> render(List<Message> messages) {
        return messages.stream().map(m -> m.getRole() + ":" + m.getContent()).toList();
    }

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
            public AgentTurnResult runTurnStreaming(AgentTurnRequest request, com.judepereira.jupiter.agent.llm.AgentStreamListener listener) {
                runCalled.set(true);
                return new AgentTurnResult("final reply", List.of());
            }
        };

        var props = new com.judepereira.jupiter.agent.config.AgentProperties();
        props.setWorkspaceRoot(".");
        UiController ctrl = TestAppStateSupport.controller(fake, props);

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
    public void multiTurnRequestHistory_excludesSystemAndPendingPlaceholder_butKeepsPriorTurns(@TempDir java.nio.file.Path tmp) throws Exception {
        class RecordingHarness extends CodingAgentHarness {
            final List<AgentTurnRequest> requests = new ArrayList<>();

            RecordingHarness() {
                super(null, null, null);
            }

            @Override
            public AgentTurnResult runTurnStreaming(AgentTurnRequest request, com.judepereira.jupiter.agent.llm.AgentStreamListener listener) {
                requests.add(request);
                AgentTurnResult result = new AgentTurnResult("reply-" + requests.size(), List.of());
                listener.onComplete(result);
                return result;
            }
        }

        RecordingHarness fake = new RecordingHarness();

        var props = new com.judepereira.jupiter.agent.config.AgentProperties();
        props.setWorkspaceRoot(tmp.toString());
        UiController ctrl = TestAppStateSupport.controller(fake, props);

        {
            Model m1 = new ConcurrentModel();
            ctrl.sendMessage("first", m1, null);
            String assistantId = assistantId((ConcurrentModel) m1);
            ctrl.streamChat(assistantId);
            TestAppStateSupport.awaitAssistantCompletion(ctrl, assistantId);
        }

        {
            Model m2 = new ConcurrentModel();
            ctrl.sendMessage("second", m2, null);
            String assistantId = assistantId((ConcurrentModel) m2);
            ctrl.streamChat(assistantId);
            TestAppStateSupport.awaitAssistantCompletion(ctrl, assistantId);
        }

        assertThat(fake.requests).hasSize(2);
        assertThat(fake.requests.get(0).getSystemPrompt())
                .isNull();
        assertThat(render(fake.requests.get(0).getConversationHistory())).containsExactly("USER:first");
        assertThat(fake.requests.get(1).getSystemPrompt())
                .isNull();
        assertThat(render(fake.requests.get(1).getConversationHistory())).containsExactly("USER:first", "ASSISTANT:reply-1", "USER:second");
    }

    @Test
    public void sendMessageForwardsSelectedAgentModelAndThinkingLevel(@TempDir java.nio.file.Path tmp) throws Exception {
        class RecordingHarness extends CodingAgentHarness {
            final List<AgentTurnRequest> requests = new ArrayList<>();

            RecordingHarness() {
                super(null, null, null);
            }

            @Override
            public AgentTurnResult runTurnStreaming(AgentTurnRequest request, com.judepereira.jupiter.agent.llm.AgentStreamListener listener) {
                requests.add(request);
                AgentTurnResult result = new AgentTurnResult("reply", List.of());
                listener.onComplete(result);
                return result;
            }
        }

        RecordingHarness fake = new RecordingHarness();

        var props = new com.judepereira.jupiter.agent.config.AgentProperties();
        props.setWorkspaceRoot(tmp.toString());
        UiController ctrl = TestAppStateSupport.controller(fake, props);

        Model model = new ConcurrentModel();
        ctrl.sendMessage("go", "engineer", "openai/gpt-5.5-pro", "LOW", model, null);
        String assistantId = assistantId((ConcurrentModel) model);
        ctrl.streamChat(assistantId);
        TestAppStateSupport.awaitAssistantCompletion(ctrl, assistantId);

        assertThat(fake.requests).hasSize(1);
        AgentTurnRequest request = fake.requests.get(0);
        assertThat(request.getAgentId()).isEqualTo("engineer");
        assertThat(request.getModelId()).isEqualTo("openai/gpt-5.5-pro");
        assertThat(request.getThinkingLevel()).isEqualTo(ThinkingLevel.LOW);
        assertThat(request.getSystemPrompt()).isNull();
        assertThat(request.getConversationHistory()).hasSize(1);
        assertThat(request.getConversationHistory().get(0).getRole()).isEqualTo(Message.Role.USER);
        assertThat(request.getConversationHistory().get(0).getContent()).isEqualTo("go");
    }

    @Test
    public void sendMessageCompactsOldHistoryAndRendersSummaryInResponse(@TempDir java.nio.file.Path tmp) throws Exception {
        class RecordingHarness extends CodingAgentHarness {
            final List<AgentTurnRequest> requests = new ArrayList<>();

            RecordingHarness() {
                super(null, null, null);
            }

            @Override
            public AgentTurnResult runTurnStreaming(AgentTurnRequest request, com.judepereira.jupiter.agent.llm.AgentStreamListener listener) {
                if (request.getSystemPrompt() != null && request.getSystemPrompt().toLowerCase().contains("compact")) {
                    return new AgentTurnResult("compact summary", List.of());
                }
                requests.add(request);
                AgentTurnResult result = new AgentTurnResult("reply-" + requests.size(), List.of());
                listener.onComplete(result);
                return result;
            }
        }

        RecordingHarness fake = new RecordingHarness();

        var props = new com.judepereira.jupiter.agent.config.AgentProperties();
        props.setWorkspaceRoot(tmp.toString());
        AppStateService appStateService = TestAppStateSupport.appStateService();
        AgentDefinition agent = new AgentDefinition("plan", "Plan", "", "Summarize", AgentMode.AGENT, "openai/gpt-5.5", ThinkingLevel.LOW, null, true, true,
                List.of("list_files", "read_file", "search_code", "write_file", "apply_patch", "run_command"));
        AgentDefinitionService agentDefinitionService = new AgentDefinitionService(new ObjectMapper()) {
            @Override
            public List<AgentDefinition> list() {
                return List.of(agent);
            }

            @Override
            public AgentDefinition defaultAgent() {
                return agent;
            }

            @Override
            public AgentDefinition getRequired(String id) {
                return agent;
            }
        };
        var modelCatalog = ModelCatalogTestSupport.modelCatalogService("https://models.dev/catalog.json", """
                {
                  "models": {
                    "openai/gpt-5.5": {
                      "id": "openai/gpt-5.5",
                      "name": "GPT-5.5",
                      "reasoning": true,
                      "tool_call": true,
                      "release_date": "2026-04-23",
                      "limit": {
                      "context": 5000,
                        "output": 500
                      }
                    }
                  }
                }
                """);
        java.util.concurrent.atomic.AtomicInteger compactionCalls = new java.util.concurrent.atomic.AtomicInteger();
        ContextCompactionService contextCompactionService = new ContextCompactionService(appStateService,
                new com.judepereira.jupiter.agent.llm.AgentModelClientFactory(null, new com.judepereira.jupiter.agent.config.AgentProperties()) {
                    @Override
                    public com.judepereira.jupiter.agent.llm.AgentModelClient getClient() {
                        return null;
                    }
                }) {
            @Override
            public java.util.Optional<com.judepereira.jupiter.persistence.Persistence.ChatMessageView> compactIfNeeded(long sessionId, AgentDefinition agent,
                                                                                                                       com.judepereira.jupiter.agent.catalog.ModelDefinition model,
                                                                                                                       ThinkingLevel thinkingLevel, String workspaceRoot, String upcomingUserText) {
                if (compactionCalls.incrementAndGet() < 8) {
                    return java.util.Optional.empty();
                }
                appStateService.markTurnsIncludeInModelFalse(sessionId, 7);
                return java.util.Optional.of(appStateService.appendVisibleSystemMessage(sessionId, "compact summary", 7L));
            }
        };
        UiController ctrl = new UiController(fake, props, appStateService, agentDefinitionService, modelCatalog,
                new SystemBalloonService(new ObjectMapper()), new com.judepereira.jupiter.ui.rail.WorkspaceRailRefreshService(),
                mock(TerminalManager.class), new TerminalStateService(),
                new com.judepereira.jupiter.openai.oauth.OpenAiOAuthService(new com.judepereira.jupiter.agent.config.OpenAiOAuthProperties(), new ObjectMapper(), java.net.http.HttpClient.newHttpClient()),
                contextCompactionService, mock(CommandStreamService.class), "0.0.1-SNAPSHOT");

        for (int i = 1; i <= 7; i++) {
            Model model = new ConcurrentModel();
            ctrl.sendMessage("turn-" + i + " " + "u".repeat(800), model, null);
            String assistantId = assistantId((ConcurrentModel) model);
            ctrl.streamChat(assistantId);
            TestAppStateSupport.awaitAssistantCompletion(ctrl, assistantId);
        }

        Model compacted = new ConcurrentModel();
        ctrl.sendMessage("turn-8 " + "u".repeat(800), compacted, null);
        String assistantId = assistantId((ConcurrentModel) compacted);
        ctrl.streamChat(assistantId);
        TestAppStateSupport.awaitAssistantCompletion(ctrl, assistantId);

        assertThat(fake.requests).isNotEmpty();
        AgentTurnRequest lastRequest = fake.requests.getLast();
        assertThat(render(lastRequest.getConversationHistory())).anyMatch(row -> row.contains("SYSTEM:compact summary"));
        assertThat(render(lastRequest.getConversationHistory())).anyMatch(row -> row.contains("USER:turn-8"));
    }

    @Test
    public void midTurnCompactionRebuildsConversationBeforeSecondModelRequest(@TempDir java.nio.file.Path tmp) throws Exception {
        AppStateService appStateService = TestAppStateSupport.appStateService();
        appStateService.addOrReopenProject("Alpha", tmp.toString());
        long sessionId = appStateService.loadViewData().activeSession().id();

        for (int i = 1; i <= 3; i++) {
            String userText = "prior-" + i + " " + "u".repeat(120);
            var turn = appStateService.appendUserMessageAndPendingAssistant(sessionId, userText);
            appStateService.completeAssistantMessage(sessionId, turn.assistantMessage().id(), "reply-" + i + " " + "a".repeat(80), List.of());
        }

        AgentDefinition agent = new AgentDefinition("engineer", "Engineer", "", "Implement", AgentMode.SUBAGENT, "openai/gpt-5.5", ThinkingLevel.MEDIUM, "low", true, true,
                List.of("big_tool"));

        AgentDefinitionService agentDefinitionService = new AgentDefinitionService(new ObjectMapper()) {
            @Override
            public List<AgentDefinition> list() {
                return List.of(agent);
            }

            @Override
            public AgentDefinition defaultAgent() {
                return agent;
            }

            @Override
            public AgentDefinition getRequired(String id) {
                return agent;
            }
        };

        var modelCatalog = ModelCatalogTestSupport.modelCatalogService("https://models.dev/catalog.json", """
                {
                  "models": {
                    "openai/gpt-5.5": {
                      "id": "openai/gpt-5.5",
                      "name": "GPT-5.5",
                      "reasoning": true,
                      "tool_call": true,
                      "release_date": "2026-04-23",
                      "limit": {
                        "context": 5000,
                        "output": 128
                      }
                    }
                  }
                }
                """);

        class RecordingModel implements com.judepereira.jupiter.agent.llm.AgentModelClient {
            final List<List<Message>> conversations = new ArrayList<>();
            private int index;

            @Override
            public com.judepereira.jupiter.agent.llm.dto.ModelResponse chat(List<Message> conversation, List<com.judepereira.jupiter.agent.llm.dto.ToolDefinition> tools) {
                return chatStreaming(conversation, tools, null, delta -> {});
            }

            @Override
            public com.judepereira.jupiter.agent.llm.dto.ModelResponse chatStreaming(List<Message> conversation, List<com.judepereira.jupiter.agent.llm.dto.ToolDefinition> tools,
                                                                                      com.judepereira.jupiter.agent.llm.AgentModelOptions options,
                                                                                      java.util.function.Consumer<String> onDelta) {
                conversations.add(List.copyOf(conversation));
                return switch (index++) {
                    case 0 -> new com.judepereira.jupiter.agent.llm.dto.ModelResponse(null,
                            new com.judepereira.jupiter.agent.llm.dto.ToolCall("big_tool", java.util.Map.of()));
                    default -> new com.judepereira.jupiter.agent.llm.dto.ModelResponse("all done", null);
                };
            }
        }

        RecordingModel model = new RecordingModel();
        var registry = new com.judepereira.jupiter.agent.tools.ToolRegistry();
        registry.register(new com.judepereira.jupiter.agent.tools.AgentTool() {
            @Override
            public String name() {
                return "big_tool";
            }

            @Override
            public com.judepereira.jupiter.agent.llm.dto.ToolDefinition definition() {
                return new com.judepereira.jupiter.agent.llm.dto.ToolDefinition("big_tool", "Big tool",
                        com.judepereira.jupiter.agent.llm.dto.ToolSchema.object());
            }

            @Override
            public com.judepereira.jupiter.agent.tools.ToolExecutionResult execute(java.util.Map<String, Object> args,
                                                                                   com.judepereira.jupiter.agent.tools.ToolExecutionContext context) {
                String text = "tool-result-" + "x".repeat(5000);
                return new com.judepereira.jupiter.agent.tools.ToolExecutionResult(true, text, java.util.Map.of("path", "generated.txt"));
            }
        });

        var props = new com.judepereira.jupiter.agent.config.AgentProperties();
        props.setWorkspaceRoot(tmp.toString());
        props.setMaxIterations(5);
        props.getTooling().setAllowWrite(true);

        CodingAgentHarness harness = new CodingAgentHarness(new com.judepereira.jupiter.agent.llm.AgentModelClientFactory(null, new com.judepereira.jupiter.agent.config.AgentProperties()) {
            @Override
            public com.judepereira.jupiter.agent.llm.AgentModelClient getClient() {
                return model;
            }
        }, registry, props, agentDefinitionService, modelCatalog);

        java.util.concurrent.atomic.AtomicInteger compactionCalls = new java.util.concurrent.atomic.AtomicInteger();
        ContextCompactionService contextCompactionService = new ContextCompactionService(appStateService,
                new com.judepereira.jupiter.agent.llm.AgentModelClientFactory(null, new com.judepereira.jupiter.agent.config.AgentProperties()) {
                    @Override
                    public com.judepereira.jupiter.agent.llm.AgentModelClient getClient() {
                        return null;
                    }
                }) {
            @Override
            public java.util.Optional<com.judepereira.jupiter.persistence.Persistence.ChatMessageView> compactIfNeeded(long sessionId, AgentDefinition agent,
                                                                                                                       com.judepereira.jupiter.agent.catalog.ModelDefinition model,
                                                                                                                       ThinkingLevel thinkingLevel, String workspaceRoot, String upcomingUserText) {
                if (compactionCalls.incrementAndGet() < 2) {
                    return java.util.Optional.empty();
                }
                appStateService.markTurnsIncludeInModelFalse(sessionId, 3);
                return java.util.Optional.of(appStateService.appendVisibleSystemMessage(sessionId, "compact summary", 3L));
            }
        };

        UiController ctrl = new UiController(harness, props, appStateService, agentDefinitionService, modelCatalog,
                new SystemBalloonService(new ObjectMapper()), new com.judepereira.jupiter.ui.rail.WorkspaceRailRefreshService(),
                mock(TerminalManager.class), new TerminalStateService(),
                new com.judepereira.jupiter.openai.oauth.OpenAiOAuthService(new com.judepereira.jupiter.agent.config.OpenAiOAuthProperties(), new ObjectMapper(), java.net.http.HttpClient.newHttpClient()),
                contextCompactionService, mock(CommandStreamService.class), "0.0.1-SNAPSHOT");

        Model sendModel = new ConcurrentModel();
        ctrl.sendMessage("current turn", "engineer", null, null, sendModel, null);
        String assistantId = assistantId((ConcurrentModel) sendModel);
        ctrl.streamChat(assistantId);
        TestAppStateSupport.awaitAssistantCompletion(ctrl, assistantId);

        assertThat(model.conversations).hasSize(2);
        List<String> second = render(model.conversations.get(1));
        assertThat(second).anyMatch(row -> row.contains("SYSTEM:compact summary"));
        assertThat(second).anyMatch(row -> row.contains("USER:current turn"));
        assertThat(second).anyMatch(row -> row.contains("TOOL:tool-result-"));
        assertThat(second).anyMatch(row -> row.contains("ASSISTANT:"));
        assertThat(appStateService.loadViewData().activeSessionDetail().chatMessages().stream()
                .map(com.judepereira.jupiter.persistence.Persistence.ChatMessageView::text)
                .toList()).contains("compact summary");
    }

    @Test
    public void streaming_preserves_spaces_and_newlines_in_final_text() throws Exception {
        CodingAgentHarness fake = new CodingAgentHarness(null, null, null) {
            @Override
            public AgentTurnResult runTurn(AgentTurnRequest request) {
                return new AgentTurnResult("", List.of());
            }

            @Override
            public AgentTurnResult runTurnStreaming(AgentTurnRequest request, com.judepereira.jupiter.agent.llm.AgentStreamListener listener) {
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

        var props = new com.judepereira.jupiter.agent.config.AgentProperties();
        props.setWorkspaceRoot(".");
        UiController ctrl = TestAppStateSupport.controller(fake, props);

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
        TestAppStateSupport.awaitAssistantCompletion(ctrl, assistantId);

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
            public AgentTurnResult runTurnStreaming(AgentTurnRequest request, com.judepereira.jupiter.agent.llm.AgentStreamListener listener) {
                throw new RuntimeException("provider error: " + quotaJson);
            }
        };

        var props = new com.judepereira.jupiter.agent.config.AgentProperties();
        props.setWorkspaceRoot(".");
        UiController ctrl = TestAppStateSupport.controller(fake, props);

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
        TestAppStateSupport.awaitAssistantCompletion(ctrl, assistantId);

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

    @Test
    public void stopChatCancelsInFlightStreamAndPersistsStoppedAssistantMessage(@TempDir java.nio.file.Path tmp) throws Exception {
        CodingAgentHarness fake = new CodingAgentHarness(null, null, null) {
            @Override
            public AgentTurnResult runTurn(AgentTurnRequest request) {
                return new AgentTurnResult("", List.of());
            }

            @Override
            public AgentTurnResult runTurnStreaming(AgentTurnRequest request, com.judepereira.jupiter.agent.llm.AgentStreamListener listener) {
                listener.onTextDelta("partial ");
                while (request.getCancellationToken() != null && !request.getCancellationToken().isCancelled()) {
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                throw new StreamCancelledException();
            }
        };

        var props = new com.judepereira.jupiter.agent.config.AgentProperties();
        props.setWorkspaceRoot(tmp.toString());
        UiController ctrl = TestAppStateSupport.controller(fake, props);

        Model sendModel = new ConcurrentModel();
        ctrl.sendMessage("go", sendModel, null);
        String assistantId = assistantId((ConcurrentModel) sendModel);

        var emitter = ctrl.streamChat(assistantId);
        assertThat(emitter).isNotNull();

        Model stopModel = new ConcurrentModel();
        String view = ctrl.stopChat(assistantId, stopModel);
        assertThat(view).isEqualTo("fragments/chat :: chat");

        TestAppStateSupport.awaitAssistantCompletion(ctrl, assistantId);

        Model after = new ConcurrentModel();
        ctrl.index(after);
        List<?> chatMessages = (List<?>) after.getAttribute("chatMessages");
        Object assistant = chatMessages.stream()
                .filter(message -> assistantId.equals(((UiController.ChatMessage) message).id()))
                .findFirst()
                .orElseThrow();

        assertThat(((UiController.ChatMessage) assistant).pending()).isFalse();
        assertThat(((UiController.ChatMessage) assistant).text()).isEqualTo("partial\n\nAction Interrupted");
    }
    private static String assistantId(ConcurrentModel model) throws Exception {
        List<?> msgs = (List<?>) model.getAttribute("chatMessages");
        Object last = msgs.get(msgs.size() - 1);
        try {
            var cls = last.getClass();
            var f = cls.getDeclaredField("id");
            f.setAccessible(true);
            return (String) f.get(last);
        } catch (Exception e) {
            String s = last.toString();
            int i = s.indexOf("id=");
            if (i >= 0) return s.substring(i + 3).replaceAll("[^a-zA-Z0-9-]", "");
            throw e;
        }
    }
}
