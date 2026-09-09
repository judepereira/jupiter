package com.judepereira.jupiter.command;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CommandCatalogServiceTest {

    @Test
    void loadsUserCommandsFromConfiguredHome(@TempDir Path tempDir) throws Exception {
        Path configuredHome = Files.createDirectories(tempDir.resolve("configured-home"));
        Path commands = Files.createDirectories(configuredHome.resolve(".jupiter/commands"));
        Files.writeString(commands.resolve("configured.md"), """
                ---
                id: configured-command
                name: Configured command
                type: prompt
                ---
                configured body
                """);

        CommandCatalogService catalog = new CommandCatalogService(configuredHome.toString());

        assertThat(catalog.getRequired("configured-command").body()).isEqualTo("configured body");
    }

    @Test
    void absentConfiguredHomeHasOnlyBundledCommands(@TempDir Path tempDir) {
        CommandCatalogService catalog = new CommandCatalogService(tempDir.resolve("does-not-exist").toString());

        assertThat(catalog.list()).isNotEmpty();
        assertThat(catalog.list()).noneMatch(command -> command.id().equals("configured-command"));
    }
}
