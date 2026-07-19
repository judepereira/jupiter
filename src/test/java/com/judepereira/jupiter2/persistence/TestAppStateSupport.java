package com.judepereira.jupiter2.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.judepereira.jupiter2.agent.config.AgentProperties;
import com.judepereira.jupiter2.agent.harness.CodingAgentHarness;
import com.judepereira.jupiter2.terminal.TerminalHandle;
import com.judepereira.jupiter2.terminal.TerminalManager;
import com.judepereira.jupiter2.terminal.TerminalStateService;
import com.judepereira.jupiter2.ui.UiController;
import com.judepereira.jupiter2.ui.balloon.SystemBalloonService;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

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
        TerminalManager terminalManager = mock(TerminalManager.class);
        AtomicInteger sequence = new AtomicInteger();
        when(terminalManager.createTerminal(anyString())).thenAnswer(invocation -> {
            int n = sequence.incrementAndGet();
            return new TerminalHandle("terminal-" + n, "Terminal " + n);
        });
        return new UiController(harness, properties, appStateService(), terminalManager, new TerminalStateService(), new SystemBalloonService(new ObjectMapper()), Runnable::run);
    }
}
