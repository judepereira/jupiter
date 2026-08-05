package com.judepereira.jupiter.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
class SqliteStorageConfig {

    @Bean
    static BeanFactoryPostProcessor sqliteDataDirectoryInitializer(Environment environment) {
        return beanFactory -> {
            var url = environment.getProperty("spring.datasource.url");
            if (url == null || !url.startsWith("jdbc:sqlite:")) {
                return;
            }

            url = environment.resolvePlaceholders(url);

            var databasePath = url.substring("jdbc:sqlite:".length());
            if (databasePath.startsWith("file:")) {
                databasePath = databasePath.substring("file:".length());
            }

            var queryIndex = databasePath.indexOf('?');
            if (queryIndex >= 0) {
                databasePath = databasePath.substring(0, queryIndex);
            }

            var parentDirectory = Path.of(databasePath).getParent();
            if (parentDirectory == null) {
                return;
            }

            try {
                Files.createDirectories(parentDirectory);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to create SQLite data directory: " + parentDirectory, e);
            }
        };
    }
}
