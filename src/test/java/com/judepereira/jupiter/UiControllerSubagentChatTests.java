package com.judepereira.jupiter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.judepereira.jupiter.agent.catalog.AgentDefinition;
import com.judepereira.jupiter.agent.catalog.AgentDefinitionService;
import com.judepereira.jupiter.agent.catalog.AgentMode;
import com.judepereira.jupiter.agent.catalog.ModelDefinition;
import com.judepereira.jupiter.agent.catalog.ThinkingLevel;
import com.judepereira.jupiter.agent.config.AgentProperties;
import com.judepereira.jupiter.agent.config.OpenAiOAuthProperties;
import com.judepereira.jupiter.agent.harness.CodingAgentHarness;
import com.judepereira.jupiter.agent.harness.SystemPromptComposer;
import com.judepereira.jupiter.anthropic.oauth.AnthropicOAuthService;
import com.judepereira.jupiter.command.CommandCatalogService;
import com.judepereira.jupiter.command.CommandStreamService;
import com.judepereira.jupiter.config.HttpAuthProperties;
import com.judepereira.jupiter.git.GitAutoUpdateService;
import com.judepereira.jupiter.git.ManualGitPullCoordinator;
import com.judepereira.jupiter.openai.oauth.OpenAiOAuthService;
import com.judepereira.jupiter.persistence.AppStateRepository;
import com.judepereira.jupiter.persistence.AppStateService;
import com.judepereira.jupiter.persistence.Persistence.ChatMessageMetadata;
import com.judepereira.jupiter.persistence.Persistence.QueuedChatTurn;
import com.judepereira.jupiter.persistence.TestAppStateSupport;
import com.judepereira.jupiter.terminal.TerminalManager;
import com.judepereira.jupiter.terminal.TerminalStateService;
import com.judepereira.jupiter.testsupport.ModelCatalogTestSupport;
import com.judepereira.jupiter.testsupport.SkillTestSupport;
import com.judepereira.jupiter.ui.ChatPresentationService;
import com.judepereira.jupiter.ui.UiController;
import com.judepereira.jupiter.ui.balloon.SystemBalloonService;
import com.judepereira.jupiter.ui.rail.WorkspaceRailRefreshService;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public class UiControllerSubagentChatTests {

    @Test
    public void loadPrimaryAndSubagentChatPopulateExpectedModelState(@TempDir Path workspaceRoot) {
        AppStateService appStateService = TestAppStateSupport.appStateService();
        appStateService.addOrReopenProject("Alpha", workspaceRoot.toString());
        long parentSessionId = appStateService.loadViewData().activeSession().id();

        AgentDefinition subagent = new AgentDefinition("engineer", "Engineer", "", "Subagent prompt",
                AgentMode.SUBAGENT, "openai/gpt-5.6-sol", ThinkingLevel.MEDIUM, "low", true, true,
                List.of("write_file"));
        long childSessionId = appStateService.createHiddenSubagentSession(parentSessionId, "parent-tool-call",
                subagent);
        QueuedChatTurn turn = appStateService.appendUserMessageAndPendingAssistant(childSessionId,
                "Primary task:\nwrite a file");
        appStateService.completeAssistantMessage(childSessionId, turn.assistantMessage().id(), "child final",
                List.of());

        UiController controller = controller(appStateService, workspaceRoot);

        Model primaryModel = new ConcurrentModel();
        String primaryView = controller.loadPrimaryChat(primaryModel);

        assertThat(primaryView).isEqualTo("fragments/chat :: chat");
        assertThat(primaryModel.getAttribute("subagentView")).isEqualTo(false);
        assertThat(primaryModel.getAttribute("subagentAgentName")).isNull();
        assertThat(primaryModel.getAttribute("subagentAgentId")).isNull();
        assertThat(primaryModel.getAttribute("subagentSessionId")).isNull();
        assertThat((List<AgentDefinition>) primaryModel.getAttribute("agents")).extracting(AgentDefinition::id)
                .containsExactly("plan", "engineer");

        Model subagentModel = new ConcurrentModel();
        String subagentView = controller.loadSubagentChat(childSessionId, subagentModel);

        assertThat(subagentView).isEqualTo("fragments/chat :: chat");
        assertThat(subagentModel.getAttribute("subagentView")).isEqualTo(true);
        assertThat(subagentModel.getAttribute("subagentAgentName")).isEqualTo("Engineer");
        assertThat(subagentModel.getAttribute("subagentAgentId")).isEqualTo("engineer");
        assertThat(subagentModel.getAttribute("subagentSessionId")).isEqualTo(childSessionId);
        assertThat((List<ChatPresentationService.ChatMessage>) subagentModel.getAttribute("chatMessages"))
                .extracting(ChatPresentationService.ChatMessage::text)
                .contains("Primary task:\nwrite a file", "child final");

        Model backModel = new ConcurrentModel();
        String backView = controller.loadPrimaryChat(backModel);

        assertThat(backView).isEqualTo("fragments/chat :: chat");
        assertThat(backModel.getAttribute("selectedAgent")).isEqualTo(backModel.getAttribute("defaultAgent"));
        assertThat(backModel.getAttribute("selectedModel")).isEqualTo(backModel.getAttribute("defaultModel"));
        assertThat(backModel.getAttribute("selectedThinking")).isEqualTo(backModel.getAttribute("defaultThinking"));
        assertThat(backModel.getAttribute("subagentView")).isEqualTo(false);
        assertThat(backModel.getAttribute("subagentAgentName")).isNull();
        assertThat(backModel.getAttribute("subagentAgentId")).isNull();
        assertThat(backModel.getAttribute("subagentSessionId")).isNull();
    }

    @Test
    public void forkedPrimaryChatReturnsShellUpdatesForRefreshTargets(@TempDir Path workspaceRoot) {
        AppStateService appStateService = TestAppStateSupport.appStateService();
        appStateService.addOrReopenProject("Alpha", workspaceRoot.toString());
        long sessionId = appStateService.loadViewData().activeSession().id();
        var turn = appStateService.appendUserMessageAndPendingAssistant(sessionId, "hello");
        appStateService.completeAssistantMessage(sessionId, turn.assistantMessage().id(), "done", List.of());

        UiController controller = controller(appStateService, workspaceRoot);
        Model model = new ConcurrentModel();
        String view = controller.forkPrimaryChat(turn.assistantMessage().id(), model);

        assertThat(view).isEqualTo("fragments/projects :: shellUpdates");
        assertThat(model.getAttribute("shellRefresh")).isEqualTo(true);
        assertThat(model.getAttribute("includeChatContainer")).isEqualTo(true);
        assertThat(model.getAttribute("reviewOob")).isEqualTo(true);
        assertThat(model.getAttribute("terminalOob")).isEqualTo(true);
    }

    @Test
    public void loadPrimaryChatPrefersLatestAssistantMetadataForPrimaryControls(@TempDir Path workspaceRoot) {
        AppStateService appStateService = TestAppStateSupport.appStateService();
        appStateService.addOrReopenProject("Alpha", workspaceRoot.toString());
        long sessionId = appStateService.loadViewData().activeSession().id();

        ChatMessageMetadata metadata = new ChatMessageMetadata("engineer", "Engineer", "openai/gpt-5.6-terra", "HIGH",
                null);
        appStateService.appendVisibleSystemMessage(sessionId, "summary one");
        appStateService.appendVisibleSystemMessage(sessionId, "summary two");
        appStateService.appendUserMessageAndPendingAssistant(sessionId, "user-1", "assistant-1", "task", metadata);
        appStateService.completeAssistantMessage(sessionId, "assistant-1", "done", List.of());

        UiController controller = controller(appStateService, workspaceRoot);
        Model model = new ConcurrentModel();
        controller.loadPrimaryChat(model);

        assertThat(((AgentDefinition) model.getAttribute("selectedAgent")).id()).isEqualTo("engineer");
        assertThat(((ThinkingLevel) model.getAttribute("selectedThinking"))).isEqualTo(ThinkingLevel.HIGH);
    }

    @Test
    public void indexUsesLatestAssistantMetadataForActiveSessionControls(@TempDir Path workspaceRoot) {
        AppStateService appStateService = TestAppStateSupport.appStateService();
        appStateService.addOrReopenProject("Alpha", workspaceRoot.toString());
        long sessionId = appStateService.loadViewData().activeSession().id();

        ChatMessageMetadata metadata = new ChatMessageMetadata("engineer", "Engineer", "openai/gpt-5.6-terra", "HIGH",
                null);
        appStateService.appendUserMessageAndPendingAssistant(sessionId, "user-1", "assistant-1", "task", metadata);
        appStateService.completeAssistantMessage(sessionId, "assistant-1", "done", List.of());

        UiController controller = controller(appStateService, workspaceRoot);
        Model model = new ConcurrentModel();
        controller.index(model);

        assertThat(((AgentDefinition) model.getAttribute("selectedAgent")).id()).isEqualTo("engineer");
        assertThat(((ModelDefinition) model.getAttribute("selectedModel")).id()).isEqualTo("openai/gpt-5.6-terra");
    }

    private static UiController controller(AppStateService appStateService, Path workspaceRoot) {
        AgentProperties props = new AgentProperties();
        props.setWorkspaceRoot(workspaceRoot.toString());
        TerminalManager terminalManager = mock(TerminalManager.class);
        TerminalStateService terminalStateService = new TerminalStateService();
        CodingAgentHarness harness = new CodingAgentHarness(null, null, null, null, null, null, null, null, null,
                new SystemPromptComposer(SkillTestSupport.defaultComponents().renderer()),
                SkillTestSupport.defaultComponents().discovery(), SkillTestSupport.defaultComponents().resolver(),
                SkillTestSupport.defaultComponents().injector());
        AgentDefinitionService agentDefinitionService = new AgentDefinitionService(new ObjectMapper());
        var modelCatalog = ModelCatalogTestSupport.modelCatalogService();
        var balloonService = new SystemBalloonService(new ObjectMapper(), () -> new SseEmitter(0L));
        var contextCompactionService = TestAppStateSupport.contextCompactionService(appStateService);
        var openAiOAuthService = new OpenAiOAuthService(new OpenAiOAuthProperties(), new ObjectMapper(),
                HttpClient.newHttpClient(), mock(AppStateRepository.class), null);
        return new UiController(harness, props, appStateService, agentDefinitionService, modelCatalog,
                ModelCatalogTestSupport.resolutionService(modelCatalog), null, null, null,
                Mockito.mock(AnthropicOAuthService.class), balloonService,
                new WorkspaceRailRefreshService(() -> new SseEmitter(0L),
                        (emitter, eventName, data) -> emitter.send(SseEmitter.event().name(eventName).data(data))),
                appStateService.activeStreamRegistryService(), terminalManager, terminalStateService,
                openAiOAuthService, contextCompactionService, null, mock(CommandStreamService.class),
                new CommandCatalogService(""), null, new ChatPresentationService(), null, null,
                new HttpAuthProperties(), mock(GitAutoUpdateService.class), mock(ManualGitPullCoordinator.class),
                "test");
    }
}
