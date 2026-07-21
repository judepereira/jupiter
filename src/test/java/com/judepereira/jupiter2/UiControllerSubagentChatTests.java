package com.judepereira.jupiter2;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.judepereira.jupiter2.agent.catalog.AgentDefinition;
import com.judepereira.jupiter2.agent.catalog.AgentDefinitionService;
import com.judepereira.jupiter2.agent.catalog.AgentMode;
import com.judepereira.jupiter2.agent.catalog.ThinkingLevel;
import com.judepereira.jupiter2.agent.config.AgentProperties;
import com.judepereira.jupiter2.agent.harness.CodingAgentHarness;
import com.judepereira.jupiter2.persistence.AppStateService;
import com.judepereira.jupiter2.persistence.Persistence.ChatMessageView;
import com.judepereira.jupiter2.persistence.Persistence.QueuedChatTurn;
import com.judepereira.jupiter2.persistence.TestAppStateSupport;
import com.judepereira.jupiter2.testsupport.ModelCatalogTestSupport;
import com.judepereira.jupiter2.terminal.TerminalManager;
import com.judepereira.jupiter2.terminal.TerminalStateService;
import com.judepereira.jupiter2.ui.UiController;
import com.judepereira.jupiter2.ui.balloon.SystemBalloonService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

public class UiControllerSubagentChatTests {

    @Test
    public void loadPrimaryAndSubagentChatPopulateExpectedModelState(@TempDir Path workspaceRoot) {
        AppStateService appStateService = TestAppStateSupport.appStateService();
        appStateService.addOrReopenProject("Alpha", workspaceRoot.toString());
        long parentSessionId = appStateService.loadViewData().activeSession().id();

        AgentDefinition subagent = new AgentDefinition("engineer", "Engineer", "", "Subagent prompt", AgentMode.SUBAGENT,
                "openai/gpt-5.5", ThinkingLevel.MEDIUM, "low", true, true, List.of("write_file"));
        long childSessionId = appStateService.createHiddenSubagentSession(parentSessionId, "parent-tool-call", subagent);
        QueuedChatTurn turn = appStateService.appendUserMessageAndPendingAssistant(childSessionId, "Primary task:\nwrite a file");
        appStateService.completeAssistantMessage(childSessionId, turn.assistantMessage().id(), "child final", List.of());

        UiController controller = controller(appStateService, workspaceRoot);

        Model primaryModel = new ConcurrentModel();
        String primaryView = controller.loadPrimaryChat(primaryModel);

        assertThat(primaryView).isEqualTo("fragments/chat :: chat");
        assertThat(primaryModel.getAttribute("subagentView")).isEqualTo(false);
        assertThat((List<AgentDefinition>) primaryModel.getAttribute("agents")).extracting(AgentDefinition::id)
                .containsExactly("plan");

        Model subagentModel = new ConcurrentModel();
        String subagentView = controller.loadSubagentChat(childSessionId, subagentModel);

        assertThat(subagentView).isEqualTo("fragments/chat :: chat");
        assertThat(subagentModel.getAttribute("subagentView")).isEqualTo(true);
        assertThat(subagentModel.getAttribute("subagentAgentName")).isEqualTo("Engineer");
        assertThat(subagentModel.getAttribute("subagentSessionId")).isEqualTo(childSessionId);
        assertThat((List<UiController.ChatMessage>) subagentModel.getAttribute("chatMessages")).extracting(UiController.ChatMessage::text)
                .contains("Primary task:\nwrite a file", "child final");

        Model backModel = new ConcurrentModel();
        String backView = controller.loadPrimaryChat(backModel);

        assertThat(backView).isEqualTo("fragments/chat :: chat");
        assertThat(backModel.getAttribute("subagentView")).isEqualTo(false);
        assertThat(backModel.getAttribute("subagentAgentName")).isNull();
        assertThat(backModel.getAttribute("subagentSessionId")).isNull();
    }

    private static UiController controller(AppStateService appStateService, Path workspaceRoot) {
        AgentProperties props = new AgentProperties();
        props.setWorkspaceRoot(workspaceRoot.toString());
        TerminalManager terminalManager = mock(TerminalManager.class);
        TerminalStateService terminalStateService = new TerminalStateService();
        CodingAgentHarness harness = new CodingAgentHarness(null, null, null);
        AgentDefinitionService agentDefinitionService = new AgentDefinitionService(new ObjectMapper());
        var modelCatalog = ModelCatalogTestSupport.modelCatalogService();
        var balloonService = new SystemBalloonService(new ObjectMapper());
        var contextCompactionService = TestAppStateSupport.contextCompactionService(appStateService);
        var openAiOAuthService = new com.judepereira.jupiter2.openai.oauth.OpenAiOAuthService(new com.judepereira.jupiter2.agent.config.OpenAiOAuthProperties(), new ObjectMapper(), HttpClient.newHttpClient());
        return new UiController(harness, props, appStateService, agentDefinitionService, modelCatalog, balloonService, terminalManager,
                terminalStateService, openAiOAuthService, contextCompactionService, Runnable::run, "test");
    }
}
