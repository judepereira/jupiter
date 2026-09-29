package com.judepereira.jupiter.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.judepereira.jupiter.security.EncryptionKey;
import com.judepereira.jupiter.security.TextEncryptor;
import com.judepereira.jupiter.testsupport.SQLiteTestSupport;
import com.judepereira.jupiter.testsupport.TestEncryptionSupport;
import java.nio.file.Path;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class EncryptedProjectStartupValidatorTests {
    private static final String WRONG_KEY = "//////////////////////////////////////////8=";

    @TempDir
    Path tempDir;
    private DataSource dataSource;

    @BeforeEach
    void setUp() {
        try {
            Path database = tempDir.resolve("validator.sqlite");
            dataSource = SQLiteTestSupport.fileBackedDataSource(database);
            Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to create validator test database", exception);
        }
    }

    @Test
    void emptyProjectsAreValid() {
        EncryptedProjectStartupValidator validator = validator(TestEncryptionSupport.encryptor());

        validator.validateOrThrowKeyMismatch();
    }

    @Test
    void correctKeyDecryptsOneProject() {
        TextEncryptor crypto = TestEncryptionSupport.encryptor();
        JdbcTemplate jdbc = jdbc();
        jdbc.update(
                "INSERT INTO projects (name, normalized_path, normalized_path_blind_index, display_order) "
                        + "VALUES (?, ?, ?, 1)",
                crypto.encrypt("project", "projects.name"), crypto.encrypt("/project", "projects.normalized_path"),
                crypto.blindIndex("/project", "projects.normalized_path"));

        validator(crypto).validateOrThrowKeyMismatch();
    }

    @Test
    void wrongKeyHasClearMismatchError() {
        TextEncryptor crypto = TestEncryptionSupport.encryptor();
        JdbcTemplate jdbc = jdbc();
        jdbc.update(
                "INSERT INTO projects (name, normalized_path, normalized_path_blind_index, display_order) "
                        + "VALUES (?, ?, ?, 1)",
                crypto.encrypt("project", "projects.name"), crypto.encrypt("/project", "projects.normalized_path"),
                crypto.blindIndex("/project", "projects.normalized_path"));

        assertThatThrownBy(
                () -> validator(new TextEncryptor(EncryptionKey.fromBase64(WRONG_KEY))).validateOrThrowKeyMismatch())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("JUPITER_ENCRYPTION_KEY does not match this database")
                .hasCauseInstanceOf(TextEncryptor.EncryptionException.class);
    }

    @Test
    void queryIsBoundedToOneRow() {
        TextEncryptor crypto = TestEncryptionSupport.encryptor();
        JdbcTemplate jdbc = jdbc();
        for (int i = 1; i <= 3; i++) {
            String path = "/project-" + i;
            jdbc.update(
                    "INSERT INTO projects (name, normalized_path, normalized_path_blind_index, display_order) "
                            + "VALUES (?, ?, ?, ?)",
                    crypto.encrypt("project-" + i, "projects.name"), crypto.encrypt(path, "projects.normalized_path"),
                    crypto.blindIndex(path, "projects.normalized_path"), i);
        }

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM projects LIMIT 1", Integer.class)).isEqualTo(3);
        validator(crypto).validateOrThrowKeyMismatch();
    }

    private EncryptedProjectStartupValidator validator(TextEncryptor crypto) {
        return new EncryptedProjectStartupValidator(new NamedParameterJdbcTemplate(dataSource), crypto);
    }

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }
}
