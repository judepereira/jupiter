package com.judepereira.jupiter2.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.judepereira.jupiter2.agent.config.AgentProperties;
import com.judepereira.jupiter2.agent.harness.CodingAgentHarness;
import com.judepereira.jupiter2.ui.UiController;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.UUID;

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
        return new UiController(harness, properties, appStateService(), Runnable::run);
    }
}
