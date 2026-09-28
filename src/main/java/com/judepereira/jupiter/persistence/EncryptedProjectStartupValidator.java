package com.judepereira.jupiter.persistence;

import com.judepereira.jupiter.security.TextEncryptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@DependsOn("flywayInitializer")
@RequiredArgsConstructor
public class EncryptedProjectStartupValidator {
    private static final String KEY_MISMATCH_MESSAGE = "JUPITER_ENCRYPTION_KEY does not match this database";

    private final NamedParameterJdbcTemplate jdbc;
    private final TextEncryptor crypto;

    public void validate() {
        jdbc.query("SELECT name, normalized_path FROM projects LIMIT 1", new MapSqlParameterSource(), (rs, rowNum) -> {
            crypto.decrypt(rs.getString("name"), "projects.name");
            crypto.decrypt(rs.getString("normalized_path"), "projects.normalized_path");
            return null;
        });
    }

    public void validateOrThrowKeyMismatch() {
        try {
            validate();
        } catch (TextEncryptor.EncryptionException exception) {
            if (TextEncryptor.isAuthenticatedDecryptionFailure(exception)) {
                throw new IllegalStateException(KEY_MISMATCH_MESSAGE, exception);
            }
            throw exception;
        }
    }

    @jakarta.annotation.PostConstruct
    void validateAtStartup() {
        validateOrThrowKeyMismatch();
    }
}
