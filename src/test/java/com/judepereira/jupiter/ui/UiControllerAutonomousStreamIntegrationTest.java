package com.judepereira.jupiter.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.judepereira.jupiter.agent.catalog.AgentDefinition;
import com.judepereira.jupiter.agent.catalog.AgentDefinitionService;
import com.judepereira.jupiter.agent.config.AgentProperties;
import com.judepereira.jupiter.agent.harness.AgentTurnRequest;
import com.judepereira.jupiter.agent.harness.AgentTurnResult;
import com.judepereira.jupiter.agent.harness.CodingAgentHarness;
import com.judepereira.jupiter.agent.harness.SystemPromptComposer;
import com.judepereira.jupiter.agent.llm.AgentStreamListener;
import com.judepereira.jupiter.command.CommandCatalogService.CommandDefinition;
import com.judepereira.jupiter.command.CommandCatalogService.CommandKind;
import com.judepereira.jupiter.persistence.TestAppStateSupport;
import com.judepereira.jupiter.testsupport.SkillTestSupport;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ConcurrentModel;

class UiControllerAutonomousStreamIntegrationTest {
    @Test
    void autonomousTurnUsesRealHistoryAndPreservesSelectedAgentContext() throws Exception {
        var requests = new CopyOnWriteArrayList<AgentTurnRequest>();
        CodingAgentHarness harness = harness(requests);
        AgentProperties properties = new AgentProperties();
        properties.setWorkspaceRoot("/tmp");
        UiController controller = TestAppStateSupport.controller(harness, properties);
        ConcurrentModel model = new ConcurrentModel();

        controller.sendMessage("seed", model, null);
        @SuppressWarnings("unchecked")
        var queued = (List<ChatPresentationService.ChatMessage>) model.getAttribute("newChatMessages");
        assertThat(queued).isNotNull().hasSize(2);
        String seedAssistantId = queued.get(1).id();
        controller.startRegisteredStream(seedAssistantId);
        assertThatThrownByDuplicateStart(controller, seedAssistantId);
        TestAppStateSupport.awaitAssistantCompletion(controller, seedAssistantId);
        controller.index(model);
        UiController.Session session = (UiController.Session) model.getAttribute("activeSession");
        assertThat(session).isNotNull();

        AgentDefinition agent = new AgentDefinitionService(new ObjectMapper()).defaultAgent();
        var command = new CommandDefinition("act", "Act", "", CommandKind.PROMPT, "autonomous prompt", null, null);
        String assistantId = controller.prepareRegisteredStream(session.id(), "/tmp", agent, command);
        controller.startRegisteredStream(assistantId);
        TestAppStateSupport.awaitAssistantCompletion(controller, assistantId);

        AgentTurnRequest autonomous = requests.get(1);
        assertThat(autonomous.getAgentId()).isEqualTo(agent.id());
        assertThat(autonomous.getConversationHistory()).extracting(message -> message.getContent()).contains("seed",
                "autonomous prompt");
        assertThat(controllerMessages(controller)).extracting(ChatPresentationService.ChatMessage::text)
                .contains("seed", "autonomous reply", "autonomous prompt");
    }

    @Test
    void discardBeforeActivationRemovesRegistrationAndDuplicateStartIsRejected() {
        var requests = new CopyOnWriteArrayList<AgentTurnRequest>();
        CodingAgentHarness harness = harness(requests);
        AgentProperties properties = new AgentProperties();
        properties.setWorkspaceRoot("/tmp");
        UiController controller = TestAppStateSupport.controller(harness, properties);
        ConcurrentModel model = new ConcurrentModel();
        controller.sendMessage("seed", model, null);
        @SuppressWarnings("unchecked")
        var queued = (List<ChatPresentationService.ChatMessage>) model.getAttribute("newChatMessages");
        controller.startRegisteredStream(queued.get(1).id());
        TestAppStateSupport.awaitAssistantCompletion(controller, queued.get(1).id());
        controller.index(model);
        UiController.Session session = (UiController.Session) model.getAttribute("activeSession");
        AgentDefinition agent = new AgentDefinitionService(new ObjectMapper()).defaultAgent();
        var command = new CommandDefinition("act", "Act", "", CommandKind.PROMPT, "prompt", null, null);

        String discarded = controller.prepareRegisteredStream(session.id(), "/tmp", agent, command);
        controller.discardRegisteredStream(discarded);
        assertThatThrownByDuplicateStart(controller, discarded);
    }

    private static CodingAgentHarness harness(List<AgentTurnRequest> requests) {
        return new CodingAgentHarness(null, null, null, null, null, null, null, null, null,
                new SystemPromptComposer(SkillTestSupport.defaultComponents().renderer()),
                SkillTestSupport.defaultComponents().discovery(), SkillTestSupport.defaultComponents().resolver(),
                SkillTestSupport.defaultComponents().injector()) {
            @Override
            public AgentTurnResult runTurnStreaming(AgentTurnRequest request, AgentStreamListener listener) {
                requests.add(request);
                listener.onComplete(new AgentTurnResult("autonomous reply", List.of()));
                return new AgentTurnResult("autonomous reply", List.of());
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static List<ChatPresentationService.ChatMessage> controllerMessages(UiController controller) {
        ConcurrentModel model = new ConcurrentModel();
        controller.index(model);
        return (List<ChatPresentationService.ChatMessage>) model.getAttribute("chatMessages");
    }

    private static void assertThatThrownByDuplicateStart(UiController controller, String id) {
        assertThatThrownBy(() -> controller.startRegisteredStream(id)).isInstanceOf(IllegalStateException.class);
    }
}
