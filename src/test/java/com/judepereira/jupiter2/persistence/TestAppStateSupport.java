package com.judepereira.jupiter2.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.judepereira.jupiter2.agent.catalog.ThinkingLevel;
import com.judepereira.jupiter2.agent.catalog.ModelCatalogService;
import com.judepereira.jupiter2.agent.config.AgentProperties;
import com.judepereira.jupiter2.agent.harness.CodingAgentHarness;
import com.judepereira.jupiter2.agent.llm.AgentModelClient;
import com.judepereira.jupiter2.agent.llm.AgentModelClientFactory;
import com.judepereira.jupiter2.agent.llm.AgentModelOptions;
import com.judepereira.jupiter2.agent.llm.dto.Message;
import com.judepereira.jupiter2.agent.llm.dto.ModelResponse;
import com.judepereira.jupiter2.agent.llm.dto.ToolDefinition;
import com.judepereira.jupiter2.terminal.TerminalHandle;
import com.judepereira.jupiter2.terminal.TerminalManager;
import com.judepereira.jupiter2.terminal.TerminalStateService;
import com.judepereira.jupiter2.ui.UiController;
import com.judepereira.jupiter2.testsupport.ModelCatalogTestSupport;
import com.judepereira.jupiter2.testsupport.SQLiteTestSupport;
import com.judepereira.jupiter2.persistence.ContextCompactionService;
import com.judepereira.jupiter2.ui.balloon.SystemBalloonService;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public final class TestAppStateSupport {

    private TestAppStateSupport() {
    }

    public static AppStateService appStateService() {
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
        return new AppStateService(repository, new ObjectMapper());
    }

    public static UiController controller(CodingAgentHarness harness, AgentProperties properties) {
        return controller(harness, properties, ModelCatalogTestSupport.modelCatalogService());
    }

    public static UiController controller(CodingAgentHarness harness, AgentProperties properties, ModelCatalogService modelCatalogService) {
        TerminalManager terminalManager = mock(TerminalManager.class);
        AtomicInteger sequence = new AtomicInteger();
        when(terminalManager.createTerminal(anyString())).thenAnswer(invocation -> {
            int n = sequence.incrementAndGet();
            return new TerminalHandle("terminal-" + n, "Terminal " + n);
        });
        when(terminalManager.createTerminal(anyString(), anyString())).thenAnswer(invocation -> {
            int n = sequence.incrementAndGet();
            return new TerminalHandle("terminal-" + n, (String) invocation.getArgument(1));
        });
        AppStateService appStateService = appStateService();
        return new UiController(harness, properties, appStateService, terminalManager, new TerminalStateService(),
                modelCatalogService, new SystemBalloonService(new ObjectMapper()), contextCompactionService(appStateService), Runnable::run);
    }

    public static ContextCompactionService contextCompactionService(AppStateService appStateService) {
        return new ContextCompactionService(appStateService, summaryClientFactory());
    }

    private static AgentModelClientFactory summaryClientFactory() {
        AgentModelClient client = new AgentModelClient() {
            @Override
            public ModelResponse chat(List<Message> conversation, List<ToolDefinition> tools) {
                return chat(conversation, tools, new AgentModelOptions(null, null, ThinkingLevel.LOW, false, null));
            }

            @Override
            public ModelResponse chat(List<Message> conversation, List<ToolDefinition> tools, AgentModelOptions options) {
                return new ModelResponse("compact summary", null);
            }

            @Override
            public ModelResponse chatStreaming(List<Message> conversation, List<ToolDefinition> tools, AgentModelOptions options,
                                               java.util.function.Consumer<String> onDelta) {
                throw new AssertionError("context compaction should not stream");
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
