package com.judepereira.jupiter.security;

import javax.sql.DataSource;
import java.sql.SQLException;

import lombok.RequiredArgsConstructor;
import org.flywaydb.core.Flyway;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class EncryptionMigrationStrategy implements FlywayMigrationStrategy {
    private final DataSource dataSource;
    private final EncryptionMigrationService migration;

    @Override
    public void migrate(Flyway flyway) {
        flyway.migrate();
        try (var connection = dataSource.getConnection()) {
            migration.run(connection);
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to run encryption migration", exception);
        }
    }
}
