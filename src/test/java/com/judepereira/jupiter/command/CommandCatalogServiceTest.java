package com.judepereira.jupiter.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CommandCatalogServiceTest {
    @TempDir
    Path root;

    @Test
    void discoversProjectAndHomeClaudeAndCodexPromptsWithMetadata() throws Exception {
        Path workspace = root.resolve("workspace");
        Files.createDirectories(workspace.resolve(".claude/commands/nested"));
        Files.createDirectories(workspace.resolve(".codex/prompts"));
        Files.createDirectories(home().resolve(".claude/commands"));
        Files.createDirectories(home().resolve(".codex/prompts"));
        Files.writeString(workspace.resolve(".claude/commands/nested/review.md"), "Review body");
        Files.writeString(workspace.resolve(".codex/prompts/plan.md"),
                "---\nname: Planning\ndescription: Plan it\nprovider: arbitrary\n---\nPlan body");
        Files.writeString(home().resolve(".claude/commands/home.md"), "Home body");
        Files.writeString(home().resolve(".codex/prompts/home.md"), "Home codex body");

        List<CommandCatalogService.CommandDefinition> commands = new CommandCatalogService(
                root.resolve("custom").toString(), home().toString()).list(workspace);

        assertThat(commands).extracting(CommandCatalogService.CommandDefinition::body).contains("Review body",
                "Plan body", "Home body", "Home codex body");
        assertThat(commands).filteredOn(c -> "Plan body".equals(c.body())).singleElement().satisfies(c -> {
            assertThat(c.provider()).isEqualTo("codex");
            assertThat(c.scope()).isEqualTo("project");
            assertThat(c.name()).isEqualTo("Planning");
            assertThat(c.description()).isEqualTo("Plan it");
        });
        assertThat(commands).anySatisfy(
                c -> assertThat(c).extracting(CommandCatalogService.CommandDefinition::scope).isEqualTo("home"));
        assertThat(commands.stream().filter(c -> !c.editable()).map(CommandCatalogService.CommandDefinition::body))
                .contains("Home body", "Home codex body");
    }

    @Test
    void projectOverridesHomeOnlyForSameProviderRelativePath() throws Exception {
        Path workspace = root.resolve("workspace");
        Files.createDirectories(workspace.resolve(".claude/commands"));
        Files.createDirectories(home().resolve(".claude/commands"));
        Files.createDirectories(home().resolve(".codex/prompts"));
        Files.writeString(workspace.resolve(".claude/commands/same.md"), "project");
        Files.writeString(home().resolve(".claude/commands/same.md"), "home");
        Files.writeString(home().resolve(".codex/prompts/same.md"), "codex");

        List<CommandCatalogService.CommandDefinition> external = new CommandCatalogService(
                root.resolve("custom").toString(), home().toString()).list(workspace).stream()
                .filter(c -> !c.editable()).toList();

        assertThat(external).extracting(CommandCatalogService.CommandDefinition::body)
                .containsExactlyInAnyOrder("project", "codex");
        assertThat(external).extracting(CommandCatalogService.CommandDefinition::provider)
                .containsExactlyInAnyOrder("claude", "codex");
    }

    @Test
    void nestedAndCrossProviderCommandsHaveUniqueRouteSafeIds() throws Exception {
        Path workspace = root.resolve("workspace");
        Files.createDirectories(workspace.resolve(".claude/commands/a/b"));
        Files.createDirectories(workspace.resolve(".codex/prompts/a"));
        Files.writeString(workspace.resolve(".claude/commands/a/b/same.md"), "claude");
        Files.writeString(workspace.resolve(".codex/prompts/a/b.md"), "codex");
        List<String> ids = new CommandCatalogService(root.resolve("custom").toString(), home().toString())
                .list(workspace).stream().filter(c -> !c.editable()).map(CommandCatalogService.CommandDefinition::id)
                .toList();
        assertThat(ids).doesNotHaveDuplicates().allMatch(id -> id.matches("[a-zA-Z0-9_-]+"));
    }

    @Test
    void frontmatterOnlyClosesOnStandaloneDelimiter() throws Exception {
        Path workspace = root.resolve("workspace");
        Path commands = workspace.resolve(".claude/commands");
        Files.createDirectories(commands);
        Files.writeString(commands.resolve("delimiter.md"),
                "---\nname: Delimiter\ndescription: ---not-a-delimiter\n---\nBody\n---not-a-delimiter\nTail");

        List<CommandCatalogService.CommandDefinition> external = new CommandCatalogService(
                root.resolve("custom").toString(), home().toString()).list(workspace).stream()
                .filter(c -> !c.editable()).toList();

        assertThat(external).singleElement().satisfies(command -> {
            assertThat(command.name()).isEqualTo("Delimiter");
            assertThat(command.body()).isEqualTo("Body\n---not-a-delimiter\nTail");
        });
    }

    @Test
    void malformedInvalidOversizedAndSymlinkExternalFilesAreSkipped() throws Exception {
        Path workspace = root.resolve("workspace");
        Path commands = workspace.resolve(".claude/commands");
        Files.createDirectories(commands.resolve("dir"));
        Files.writeString(commands.resolve("valid.md"), "valid");
        Files.writeString(commands.resolve("malformed.md"), "---\nname: [\n---\nbad");
        Files.write(commands.resolve("invalid.md"), new byte[]{(byte) 0xc3, (byte) 0x28});
        Files.write(commands.resolve("large.md"), new byte[256 * 1024 + 1]);
        try {
            Files.createSymbolicLink(commands.resolve("link.md"), commands.resolve("valid.md"));
            Files.createSymbolicLink(commands.resolve("linkdir"), commands.resolve("dir"));
        } catch (UnsupportedOperationException | FileSystemException ignored) {
            // Symlink coverage is unavailable on restricted filesystems.
        }
        List<CommandCatalogService.CommandDefinition> external = new CommandCatalogService(
                root.resolve("custom").toString(), home().toString()).list(workspace).stream()
                .filter(c -> !c.editable()).toList();
        assertThat(external).extracting(CommandCatalogService.CommandDefinition::body).containsExactly("valid");
    }

    @Test
    void rediscoveryReflectsExternalAddEditAndDeleteWithoutReload() throws Exception {
        Path workspace = root.resolve("workspace");
        Path commands = workspace.resolve(".claude/commands");
        Files.createDirectories(commands);
        CommandCatalogService service = new CommandCatalogService(root.resolve("custom").toString(), home().toString());
        assertThat(service.list(workspace).stream().filter(c -> !c.editable())).isEmpty();
        Path prompt = commands.resolve("dynamic.md");
        Files.writeString(prompt, "one");
        assertThat(service.list(workspace).stream().map(CommandCatalogService.CommandDefinition::body)).contains("one");
        Files.writeString(prompt, "two");
        assertThat(service.list(workspace).stream().map(CommandCatalogService.CommandDefinition::body)).contains("two");
        Files.delete(prompt);
        assertThat(service.list(workspace).stream().map(CommandCatalogService.CommandDefinition::body))
                .doesNotContain("two");
    }

    @Test
    void externalCommandsCannotBeMutated() throws Exception {
        Path workspace = root.resolve("workspace");
        Files.createDirectories(workspace.resolve(".claude/commands"));
        Path prompt = workspace.resolve(".claude/commands/external.md");
        Files.writeString(prompt, "original");
        CommandCatalogService service = new CommandCatalogService(root.resolve("custom").toString(), home().toString());
        String id = service.list(workspace).stream().filter(c -> !c.editable()).findFirst().orElseThrow().id();
        assertThatThrownBy(() -> service.delete(id)).isInstanceOf(CommandCatalogService.CommandMutationException.class);
        assertThatThrownBy(() -> service.update(id, command(id, "changed")))
                .isInstanceOf(CommandCatalogService.CommandMutationException.class);
        assertThat(Files.readString(prompt)).isEqualTo("original");
    }

    private Path home() {
        return root.resolve("home");
    }

    @Test
    void createWritesFileAndIsImmediatelyAvailable() throws Exception {
        CommandCatalogService service = new CommandCatalogService(root.toString(), home().toString());
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
        CommandCatalogService service = new CommandCatalogService(root.toString(), System.getProperty("user.home"));

        CommandCatalogService.CommandDefinition updated = command("new-id", "new body");
        service.update("renamed", updated);

        assertThat(Files.exists(root.resolve("source-name.md"))).isFalse();
        assertThat(Files.readString(root.resolve("new-id.md"))).contains("id: \"new-id\"", "new body");
        assertThat(service.getRequired("new-id")).isEqualTo(updated);
    }

    @Test
    void deleteRemovesCustomCommand() throws Exception {
        CommandCatalogService service = new CommandCatalogService(root.toString(), System.getProperty("user.home"));
        service.create(command("remove-me", "body"));

        service.delete("remove-me");

        assertThat(Files.exists(root.resolve("remove-me.md"))).isFalse();
        assertThat(service.listCustom()).isEmpty();
    }

    @Test
    void rejectsBundledCollisionAndUnsafeIds() {
        CommandCatalogService service = new CommandCatalogService(root.toString(), System.getProperty("user.home"));

        assertThatThrownBy(() -> service.create(command("status", "body")))
                .isInstanceOf(CommandCatalogService.CommandMutationException.class);
        assertThatThrownBy(() -> service.create(command("../escape", "body")))
                .isInstanceOf(CommandCatalogService.CommandMutationException.class);
    }

    @Test
    void failedReloadRestoresUpdatedFileAndSnapshot() throws Exception {
        CommandCatalogService service = new CommandCatalogService(root.toString(), System.getProperty("user.home"));
        CommandCatalogService.CommandDefinition original = service.create(command("stable", "original"));
        Files.writeString(root.resolve("invalid.md"), "not frontmatter");

        assertThatThrownBy(() -> service.update("stable", command("stable", "changed")))
                .isInstanceOf(IllegalStateException.class);

        assertThat(Files.readString(root.resolve("stable.md"))).contains("original");
        assertThat(service.getRequired("stable")).isEqualTo(original);
    }

    @Test
    void failedDeleteRestoresExactFileAndSnapshot() throws Exception {
        CommandCatalogService service = new CommandCatalogService(root.toString(), System.getProperty("user.home"));
        service.create(command("stable", "original"));
        byte[] original = Files.readAllBytes(root.resolve("stable.md"));
        Files.writeString(root.resolve("invalid.md"), "not frontmatter");

        assertThatThrownBy(() -> service.delete("stable")).isInstanceOf(IllegalStateException.class);

        assertThat(Files.readAllBytes(root.resolve("stable.md"))).isEqualTo(original);
        assertThat(service.getRequired("stable").body()).isEqualTo("original");
    }

    private static CommandCatalogService.CommandDefinition command(String id, String body) {
        return new CommandCatalogService.CommandDefinition(id, "Name", null, CommandCatalogService.CommandKind.SCRIPT,
                body, null, null);
    }

    private static String document(String id, String body) {
        return "---\nid: " + id + "\nname: Name\ntype: script\n---\n\n" + body + "\n";
    }
}
