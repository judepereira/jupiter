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
import com.judepereira.jupiter2.persistence.ContextCompactionService;
import com.judepereira.jupiter2.ui.balloon.SystemBalloonService;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public final class TestAppStateSupport {

    private TestAppStateSupport() {
    }

    public static AppStateService appStateService() {
        String dbName = "jupiter_" + UUID.randomUUID().toString().replace("-", "");
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:" + dbName + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
        dataSource.setUsername("sa");
        dataSource.setPassword("");

        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();

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
