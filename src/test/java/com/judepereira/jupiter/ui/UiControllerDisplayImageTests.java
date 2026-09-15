package com.judepereira.jupiter.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.judepereira.jupiter.agent.catalog.AgentDefinitionService;
import com.judepereira.jupiter.agent.config.AgentProperties;
import com.judepereira.jupiter.agent.config.OpenAiOAuthProperties;
import com.judepereira.jupiter.agent.harness.CodingAgentHarness;
import com.judepereira.jupiter.agent.harness.SystemPromptComposer;
import com.judepereira.jupiter.agent.llm.AgentModelClientFactory;
import com.judepereira.jupiter.anthropic.oauth.AnthropicOAuthService;
import com.judepereira.jupiter.command.CommandCatalogService;
import com.judepereira.jupiter.command.CommandStreamService;
import com.judepereira.jupiter.config.HttpAuthProperties;
import com.judepereira.jupiter.git.GitAutoUpdateService;
import com.judepereira.jupiter.git.ManualGitPullCoordinator;
import com.judepereira.jupiter.openai.oauth.OpenAiOAuthService;
import com.judepereira.jupiter.persistence.AppStateRepository;
import com.judepereira.jupiter.persistence.AppStateService;
import com.judepereira.jupiter.persistence.ContextCompactionService;
import com.judepereira.jupiter.persistence.Persistence.ToolCallTraceInput;
import com.judepereira.jupiter.persistence.TestAppStateSupport;
import com.judepereira.jupiter.terminal.TerminalManager;
import com.judepereira.jupiter.terminal.TerminalStateService;
import com.judepereira.jupiter.testsupport.ModelCatalogTestSupport;
import com.judepereira.jupiter.testsupport.SkillTestSupport;
import com.judepereira.jupiter.ui.balloon.SystemBalloonService;
import com.judepereira.jupiter.ui.rail.WorkspaceRailRefreshService;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class UiControllerDisplayImageTests {

    @Test
    void streamDisplayImageServesWorkspaceImage(@TempDir Path workspace) throws Exception {
        Path image = workspace.resolve("images/cat.png");
        image.getParent().toFile().mkdirs();
        byte[] bytes = new byte[]{(byte) 0x89, 'P', 'N', 'G'};
        Files.write(image, bytes);

        var appStateContext = TestAppStateSupport.appStateContext(event -> {
        });
        AppStateService appStateService = appStateContext.service();
        appStateService.addOrReopenProject("Alpha", workspace.toString());
        long sessionId = appStateService.loadViewData().activeSession().id();
        var turn = appStateService.appendUserMessageAndPendingAssistant(sessionId, "show image");
        appStateService.appendToolCallTrace(sessionId, turn.assistantMessage().id(), new ToolCallTraceInput("image-1",
                "display_image", Map.of("path", "images/cat.png"), true, "Displayed image: images/cat.png",
                Map.of("displayType", "image", "path", "images/cat.png", "alt", "Cat", "mediaType", "image/png")));

        var skillComponents = SkillTestSupport.defaultComponents();
        var promptComposer = new SystemPromptComposer(skillComponents.renderer());
        UiController controller = new UiController(
                new CodingAgentHarness(null, null, new AgentProperties(), null, null,
                        ModelCatalogTestSupport.resolutionService(ModelCatalogTestSupport.modelCatalogService()), null,
                        null, null, promptComposer, skillComponents.discovery(), skillComponents.resolver(),
                        skillComponents.injector()),
                new AgentProperties(), appStateService, new AgentDefinitionService(new ObjectMapper()),
                ModelCatalogTestSupport.modelCatalogService(),
                ModelCatalogTestSupport.resolutionService(ModelCatalogTestSupport.modelCatalogService()), null, null,
                null, Mockito.mock(AnthropicOAuthService.class),
                new SystemBalloonService(new ObjectMapper(), () -> new SseEmitter(0L)),
                new WorkspaceRailRefreshService(() -> new SseEmitter(0L),
                        (emitter, eventName, data) -> emitter.send(SseEmitter.event().name(eventName).data(data))),
                appStateService.activeStreamRegistryService(), mock(TerminalManager.class), new TerminalStateService(),
                new OpenAiOAuthService(new OpenAiOAuthProperties(), new ObjectMapper(), HttpClient.newHttpClient(),
                        mock(AppStateRepository.class), null),
                new ContextCompactionService(appStateService, mock(AgentModelClientFactory.class), null, promptComposer,
                        skillComponents.discovery()),
                null, mock(CommandStreamService.class), new CommandCatalogService(""), null,
                new ChatPresentationService(), null, null, new HttpAuthProperties(), mock(GitAutoUpdateService.class),
                mock(ManualGitPullCoordinator.class), "0.0.1-SNAPSHOT");

        var response = controller.streamDisplayImage(sessionId, "image-1");

        assertThat(response.getHeaders().getFirst("Content-Type")).isEqualTo("image/png");
        assertThat(response.getHeaders().getFirst("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getBody()).isEqualTo(bytes);
    }
}
