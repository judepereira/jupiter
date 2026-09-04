package com.judepereira.jupiter.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.judepereira.jupiter.agent.catalog.ModelCatalogService;
import com.judepereira.jupiter.agent.catalog.ThinkingLevel;
import com.judepereira.jupiter.agent.config.AgentProperties;
import com.judepereira.jupiter.agent.harness.CodingAgentHarness;
import com.judepereira.jupiter.agent.llm.AgentModelClient;
import com.judepereira.jupiter.agent.llm.AgentModelClientFactory;
import com.judepereira.jupiter.agent.llm.AgentModelOptions;
import com.judepereira.jupiter.agent.llm.dto.Message;
import com.judepereira.jupiter.agent.llm.dto.ModelResponse;
import com.judepereira.jupiter.agent.llm.dto.ModelResponseMetadata;
import com.judepereira.jupiter.agent.llm.dto.ToolDefinition;
import com.judepereira.jupiter.agent.mcp.McpProjectMcpServerRuntimeManager;
import com.judepereira.jupiter.command.CommandStreamService;
import com.judepereira.jupiter.git.GitAutoUpdateService;
import com.judepereira.jupiter.lifecycle.LifecycleHookService;
import com.judepereira.jupiter.openai.oauth.OpenAiOAuthService;
import com.judepereira.jupiter.terminal.TerminalHandle;
import com.judepereira.jupiter.terminal.TerminalManager;
import com.judepereira.jupiter.terminal.TerminalStateService;
import com.judepereira.jupiter.testsupport.ModelCatalogTestSupport;
import com.judepereira.jupiter.testsupport.SQLiteTestSupport;
import com.judepereira.jupiter.ui.ActiveStreamRegistryService;
import com.judepereira.jupiter.ui.UiController;
import com.judepereira.jupiter.ui.ChatPresentationService;
import com.judepereira.jupiter.ui.balloon.SystemBalloonService;
import com.judepereira.jupiter.ui.rail.WorkspaceRailRefreshService;
import org.flywaydb.core.Flyway;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.springframework.ui.ConcurrentModel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public final class TestAppStateSupport {

    private TestAppStateSupport() {
    }

    public static AppStateService appStateService() {
        return appStateContext(new ApplicationEventPublisher() {
            @Override
            public void publishEvent(Object event) {
            }
        }).service();
    }

    public static AppStateService appStateService(ApplicationEventPublisher applicationEventPublisher) {
        return appStateContext(applicationEventPublisher).service();
    }

    public static AppStateTestContext appStateContext(ApplicationEventPublisher applicationEventPublisher) {
        return appStateContext(applicationEventPublisher, new ActiveStreamRegistryService());
    }

    private static AppStateTestContext appStateContext(ApplicationEventPublisher applicationEventPublisher, ActiveStreamRegistryService activeStreamRegistryService) {
        Path dbFile;
        try {
            dbFile = Files.createTempDirectory("jupiter-app-state-").resolve("app-state.db");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create SQLite test database", e);
        }

        var dataSource = SQLiteTestSupport.fileBackedDataSource(dbFile);

        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        SQLiteTestSupport.assertWalAndForeignKeysEnabled(dataSource);

        AppStateRepository repository = new AppStateRepository(new NamedParameterJdbcTemplate(dataSource));
        AppStateService service = new AppStateService(repository, new ObjectMapper(), applicationEventPublisher, activeStreamRegistryService);
        return new AppStateTestContext(service, repository, activeStreamRegistryService, dataSource);
    }

    public record AppStateTestContext(AppStateService service, AppStateRepository repository,
                                      ActiveStreamRegistryService activeStreamRegistryService,
                                      javax.sql.DataSource dataSource) {}

    public static UiController controller(CodingAgentHarness harness, AgentProperties properties) {
        return controller(harness, properties, ModelCatalogTestSupport.modelCatalogService(), null);
    }

    public static UiController controller(CodingAgentHarness harness, AgentProperties properties, ModelCatalogService modelCatalogService) {
        return controller(harness, properties, modelCatalogService, null);
    }

    public static UiController controller(CodingAgentHarness harness, AgentProperties properties, ModelCatalogService modelCatalogService,
                                          LifecycleHookService lifecycleHookService) {
        TerminalManager terminalManager = mock(TerminalManager.class);
        AtomicInteger sequence = new AtomicInteger();
        when(terminalManager.createTerminal(anyString(), anyMap())).thenAnswer(invocation -> {
            int n = sequence.incrementAndGet();
            return new TerminalHandle("terminal-" + n, "Terminal " + n);
        });
        when(terminalManager.createTerminal(anyString(), anyString(), anyMap())).thenAnswer(invocation -> {
            int n = sequence.incrementAndGet();
            return new TerminalHandle("terminal-" + n, (String) invocation.getArgument(1));
        });
        ActiveStreamRegistryService activeStreamRegistryService = new ActiveStreamRegistryService();
        AppStateTestContext context = appStateContext(new ApplicationEventPublisher() {
            @Override
            public void publishEvent(Object event) {
            }
        }, activeStreamRegistryService);
        AppStateService appStateService = context.service();
        SpringTemplateEngine templateEngine = new SpringTemplateEngine();
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCacheable(false);
        templateEngine.setTemplateResolver(resolver);
        return new UiController(harness, properties, appStateService, new com.judepereira.jupiter.agent.catalog.AgentDefinitionService(new ObjectMapper()), modelCatalogService, new SystemBalloonService(new ObjectMapper(), () -> new org.springframework.web.servlet.mvc.method.annotation.SseEmitter(0L)), new WorkspaceRailRefreshService(() -> new org.springframework.web.servlet.mvc.method.annotation.SseEmitter(0L), (emitter, eventName, data) -> emitter.send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.event().name(eventName).data(data))), activeStreamRegistryService, terminalManager, new TerminalStateService(), new OpenAiOAuthService(new com.judepereira.jupiter.agent.config.OpenAiOAuthProperties(), new ObjectMapper(), java.net.http.HttpClient.newHttpClient(), context.repository()), contextCompactionService(appStateService), new TokenUsageService(context.repository(), new ObjectMapper()), mock(CommandStreamService.class), mock(McpProjectMcpServerRuntimeManager.class), new com.judepereira.jupiter.ui.ChatPresentationService(), new com.judepereira.jupiter.ui.ChatToolCallHtmlService(templateEngine, new com.judepereira.jupiter.ui.ChatPresentationService(), appStateService), lifecycleHookService, mock(GitAutoUpdateService.class), mock(com.judepereira.jupiter.git.ManualGitPullCoordinator.class), "0.0.1-SNAPSHOT");
    }

    public static ChatPresentationService.ChatMessage awaitAssistantCompletion(UiController controller, String assistantId) {
        return awaitChatMessage(controller, assistantId, message -> !message.pending());
    }

    public static ConcurrentModel awaitChangedFilesAndSelection(UiController controller) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        ConcurrentModel lastSeen = null;
        while (System.nanoTime() < deadline) {
            ConcurrentModel model = new ConcurrentModel();
            controller.index(model);
            lastSeen = model;
            List<?> changedFiles = (List<?>) model.getAttribute("changedFiles");
            if (changedFiles != null && !changedFiles.isEmpty() && model.getAttribute("selectedFile") != null) {
                return model;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for changed files and selection", e);
            }
        }
        throw new IllegalStateException("Timed out waiting for changed files and selection" + (lastSeen == null ? "" : ": " + lastSeen));
    }

    private static ChatPresentationService.ChatMessage awaitChatMessage(UiController controller, String messageId, Predicate<ChatPresentationService.ChatMessage> condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        ChatPresentationService.ChatMessage lastSeen = null;
        while (System.nanoTime() < deadline) {
            ChatPresentationService.ChatMessage message = currentChatMessage(controller, messageId);
            if (message != null) {
                lastSeen = message;
                if (condition.test(message)) {
                    return message;
                }
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for chat message " + messageId, e);
            }
        }
        throw new IllegalStateException("Timed out waiting for chat message " + messageId + (lastSeen == null ? "" : ": " + lastSeen));
    }

    @SuppressWarnings("unchecked")
    private static ChatPresentationService.ChatMessage currentChatMessage(UiController controller, String messageId) {
        ConcurrentModel model = new ConcurrentModel();
        controller.index(model);
        List<ChatPresentationService.ChatMessage> messages = (List<ChatPresentationService.ChatMessage>) model.getAttribute("chatMessages");
        if (messages == null) {
            return null;
        }
        return messages.stream().filter(message -> messageId.equals(message.id())).findFirst().orElse(null);
    }

    public static ContextCompactionService contextCompactionService(AppStateService appStateService) {
        return new ContextCompactionService(appStateService, summaryClientFactory(), null, new com.judepereira.jupiter.agent.harness.SystemPromptComposer(new com.judepereira.jupiter.agent.skill.SkillCatalogRenderer()), new com.judepereira.jupiter.agent.skill.SkillDiscoveryService(new com.judepereira.jupiter.agent.skill.SkillParser(), System.getProperty("user.home")));
    }

    private static AgentModelClientFactory summaryClientFactory() {
        AgentModelClient client = new AgentModelClient() {
            @Override
            public ModelResponse chat(List<Message> conversation, List<ToolDefinition> tools) {
                return chat(conversation, tools, new AgentModelOptions(null, null, ThinkingLevel.LOW, false, null));
            }

            @Override
            public ModelResponse chat(List<Message> conversation, List<ToolDefinition> tools, AgentModelOptions options) {
                return new ModelResponse("compact summary", null, ModelResponseMetadata.empty());
            }

            @Override
            public ModelResponse chatStreaming(List<Message> conversation, List<ToolDefinition> tools, AgentModelOptions options,
                                               java.util.function.Consumer<String> onDelta) {
                onDelta.accept("compact summary");
                return new ModelResponse("compact summary", null, ModelResponseMetadata.empty());
            }
        };

        return new AgentModelClientFactory(null, new AgentProperties()) {
            @Override
            public AgentModelClient getClient() {
                return client;
            }
        };
    }
}
