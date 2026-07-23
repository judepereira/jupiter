package com.judepereira.jupiter2.testsupport;

import org.assertj.core.api.Assertions;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;

public final class SQLiteTestSupport {

    private SQLiteTestSupport() {
    }

    public static SQLiteDataSource fileBackedDataSource(Path dbFile) {
        try {
            Files.createDirectories(dbFile.toAbsolutePath().normalize().getParent());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create SQLite test database directory", e);
        }

        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:file:" + dbFile.toAbsolutePath().normalize() + "?journal_mode=WAL&foreign_keys=on");
        return dataSource;
    }

    public static void assertWalAndForeignKeysEnabled(DataSource dataSource) {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        Assertions.assertThat(jdbcTemplate.queryForObject("PRAGMA journal_mode", String.class)).isEqualTo("wal");
        Assertions.assertThat(jdbcTemplate.queryForObject("PRAGMA foreign_keys", Integer.class)).isEqualTo(1);
    }
}
