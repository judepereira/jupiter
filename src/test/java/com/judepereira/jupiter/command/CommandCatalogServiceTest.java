package com.judepereira.jupiter.command;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CommandCatalogServiceTest {
    @TempDir
    Path root;

    @Test
    void createWritesFileAndIsImmediatelyAvailable() throws Exception {
        CommandCatalogService service = new CommandCatalogService(root.toString());
        CommandCatalogService.CommandDefinition definition = command("custom", "echo custom");

        service.create(definition);

        assertThat(Files.readString(root.resolve("custom.md"))).contains("id: \"custom\"", "echo custom");
        assertThat(service.listCustom()).containsExactly(definition);
        assertThat(service.getRequired(" custom ")).isEqualTo(definition);
    }

    @Test
    void updateRenamesDefinitionEvenWhenSourceFilenameDoesNotMatchId() throws Exception {
        Files.createDirectories(root);
        Files.writeString(root.resolve("source-name.md"), document("renamed", "old"));
        CommandCatalogService service = new CommandCatalogService(root.toString());

        CommandCatalogService.CommandDefinition updated = command("new-id", "new body");
        service.update("renamed", updated);

        assertThat(Files.exists(root.resolve("source-name.md"))).isFalse();
        assertThat(Files.readString(root.resolve("new-id.md"))).contains("id: \"new-id\"", "new body");
        assertThat(service.getRequired("new-id")).isEqualTo(updated);
    }

    @Test
    void deleteRemovesCustomCommand() throws Exception {
        CommandCatalogService service = new CommandCatalogService(root.toString());
        service.create(command("remove-me", "body"));

        service.delete("remove-me");

        assertThat(Files.exists(root.resolve("remove-me.md"))).isFalse();
        assertThat(service.listCustom()).isEmpty();
    }

    @Test
    void rejectsBundledCollisionAndUnsafeIds() {
        CommandCatalogService service = new CommandCatalogService(root.toString());

        assertThatThrownBy(() -> service.create(command("status", "body")))
                .isInstanceOf(CommandCatalogService.CommandMutationException.class);
        assertThatThrownBy(() -> service.create(command("../escape", "body")))
                .isInstanceOf(CommandCatalogService.CommandMutationException.class);
    }

    @Test
    void failedReloadRestoresUpdatedFileAndSnapshot() throws Exception {
        CommandCatalogService service = new CommandCatalogService(root.toString());
        CommandCatalogService.CommandDefinition original = service.create(command("stable", "original"));
        Files.writeString(root.resolve("invalid.md"), "not frontmatter");

        assertThatThrownBy(() -> service.update("stable", command("stable", "changed")))
                .isInstanceOf(IllegalStateException.class);

        assertThat(Files.readString(root.resolve("stable.md"))).contains("original");
        assertThat(service.getRequired("stable")).isEqualTo(original);
    }

    @Test
    void failedDeleteRestoresExactFileAndSnapshot() throws Exception {
        CommandCatalogService service = new CommandCatalogService(root.toString());
        service.create(command("stable", "original"));
        byte[] original = Files.readAllBytes(root.resolve("stable.md"));
        Files.writeString(root.resolve("invalid.md"), "not frontmatter");

        assertThatThrownBy(() -> service.delete("stable"))
                .isInstanceOf(IllegalStateException.class);

        assertThat(Files.readAllBytes(root.resolve("stable.md"))).isEqualTo(original);
        assertThat(service.getRequired("stable").body()).isEqualTo("original");
    }

    private static CommandCatalogService.CommandDefinition command(String id, String body) {
        return new CommandCatalogService.CommandDefinition(id, "Name", null,
                CommandCatalogService.CommandKind.SCRIPT, body, null, null);
    }

    private static String document(String id, String body) {
        return "---\nid: " + id + "\nname: Name\ntype: script\n---\n\n" + body + "\n";
    }
}
