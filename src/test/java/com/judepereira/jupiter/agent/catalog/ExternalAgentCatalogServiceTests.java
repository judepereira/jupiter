package com.judepereira.jupiter.agent.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExternalAgentCatalogServiceTests {
    @TempDir
    Path home;

    @Test
    void precedenceIsHomeThenWorkspaceAndCodexThenClaudeThenJupiter() throws IOException {
        Path workspace = Files.createDirectory(home.resolve("workspace"));
        write(home.resolve(".codex/agents/same.md"), codex("home-codex", "Home"));
        write(home.resolve(".claude/agents/same.md"), claude("Claude", "Home Claude"));
        write(home.resolve(".jupiter/agents/same.md"), jupiter("same", "Home Jupiter"));
        write(workspace.resolve(".claude/agents/same.md"), claude("Claude", "Workspace Claude"));
        write(workspace.resolve(".jupiter/agents/same.md"), jupiter("same", "Workspace Jupiter"));

        AgentCatalogSnapshot snapshot = service().snapshot(workspace);

        assertThat(snapshot.getRequired("same").description()).isEqualTo("Workspace Jupiter");
        assertThat(snapshot.sources().get("same").scope()).isEqualTo(AgentSource.Scope.WORKSPACE);
        assertThat(snapshot.sources().get("same").kind()).isEqualTo(AgentSource.Kind.JUPITER);
    }

    @Test
    void sameScopeSameVendorDuplicateRetainsSortedFirstAndReportsIt() throws IOException {
        write(home.resolve(".claude/agents/a-first.md"), claude("duplicate", "first"));
        write(home.resolve(".claude/agents/b-second.md"), claude("duplicate", "second"));

        AgentCatalogSnapshot snapshot = service().snapshot(null);

        assertThat(snapshot.getRequired("duplicate").description()).isEqualTo("first");
        assertThat(snapshot.diagnostics()).anyMatch(d -> d.message().contains("Duplicate agent id"));
    }

    @Test
    void nativeToolsRequireBooleanMapAndExpandWildcardForNativeAgents() throws IOException {
        write(home.resolve(".jupiter/agents/primary.md"),
                jupiterWith("id: primary\nname: Primary\ndescription: primary\nmode: agent\nmodel: openai/gpt-4.1\n"
                        + "tools:\n  '*': true\n  task: true\ntextVerbosity: low\n", "primary"));
        write(home.resolve(".jupiter/agents/subagent.md"),
                jupiterWith(
                        "id: subagent\nname: Subagent\ndescription: subagent\nmode: subagent\nmodel: openai/gpt-4.1\n"
                                + "tools:\n  '*': true\n",
                        "subagent"));
        write(home.resolve(".jupiter/agents/bad.md"),
                jupiterWith("id: bad\nname: Bad\ndescription: bad\nmode: subagent\nmodel: openai/gpt-4.1\n"
                        + "tools:\n  list_files: 'yes'\n", "bad"));

        AgentCatalogSnapshot snapshot = service().snapshot(null);

        assertThat(snapshot.getRequired("primary").allowedTools()).contains("task", "write_file", "run_command");
        assertThat(snapshot.getRequired("subagent").allowedTools()).contains("write_file", "run_command")
                .doesNotContain("task");
        assertThat(snapshot.agents()).extracting(AgentDefinition::id).doesNotContain("bad");
        assertThat(snapshot.diagnostics()).anyMatch(d -> d.source().getFileName().toString().equals("bad.md"));
    }

    @Test
    void nativePlanCannotBeReplacedBySubagentAndTextVerbosityIsRetained() throws IOException {
        write(home.resolve(".jupiter/agents/plan.md"), jupiterWith(
                "id: plan\nname: Plan\ndescription: bad\nmode: subagent\nmodel: openai/gpt-4.1\ntextVerbosity: low\n",
                "bad"));

        AgentCatalogSnapshot snapshot = service().snapshot(null);

        assertThat(snapshot.getRequired("plan").description()).isNotEqualTo("bad");
        assertThat(snapshot.diagnostics()).anyMatch(d -> d.source().getFileName().toString().equals("plan.md"));
    }

    @Test
    void explicitModesAreHonoredAcrossMarkdownFormatsAndTaskAliases() throws IOException {
        write(home.resolve(".claude/agents/claude-primary.md"), """
                ---
                name: claude-primary
                description: primary
                mode: agent
                model: gpt-4.1
                tools: Agent,Read
                ---
                primary prompt
                """);
        write(home.resolve(".codex/agents/codex-subagent.toml"), """
                name = "codex-subagent"
                description = "subagent"
                mode = "subagent"
                tools = ["read_file"]
                """);

        AgentCatalogSnapshot snapshot = service().snapshot(null);

        assertThat(snapshot.agents()).extracting(AgentDefinition::id).contains("claude-primary");
        assertThat(snapshot.getRequired("claude-primary").mode()).isEqualTo(AgentMode.AGENT);
        assertThat(snapshot.getRequired("claude-primary").allowedTools()).contains("task");
        assertThat(snapshot.getRequired("codex-subagent").mode()).isEqualTo(AgentMode.SUBAGENT);
        assertThat(snapshot.getRequired("codex-subagent").allowedTools()).doesNotContain("task");
    }

    @Test
    void missingModeDefaultsToPrimaryForClaudeAndCodexButNativeJupiterRemainsSubagent() throws IOException {
        write(home.resolve(".claude/agents/inherited.md"), """
                ---
                name: inherited
                description: inherited
                ---
                prompt
                """);
        write(home.resolve(".codex/agents/inherited.md"), """
                ---
                description: codex inherited
                ---
                prompt
                """);
        write(home.resolve(".codex/agents/direct.toml"), "description = \"direct\"\n");
        write(home.resolve(".codex/config.toml"), """
                [agents.registered]
                description = "registered"
                config_file = "registered.toml"
                """);
        write(home.resolve(".codex/registered.toml"), "description = \"registered file\"\n");
        write(home.resolve(".jupiter/agents/native.md"),
                jupiterWith("id: native\nname: Native\ndescription: native\n", "prompt"));

        AgentCatalogSnapshot snapshot = service().snapshot(null);

        assertThat(snapshot.getRequired("inherited").mode()).isEqualTo(AgentMode.AGENT);
        assertThat(snapshot.getRequired("inherited").allowedTools()).contains("task");
        assertThat(snapshot.getRequired("direct").mode()).isEqualTo(AgentMode.AGENT);
        assertThat(snapshot.getRequired("registered").mode()).isEqualTo(AgentMode.AGENT);
        assertThat(snapshot.getRequired("native").mode()).isEqualTo(AgentMode.SUBAGENT);
    }

    @Test
    void explicitSubagentRemainsSubagentAcrossExternalFormats() throws IOException {
        write(home.resolve(".claude/agents/claude.md"), claudeWithMode("claude", "subagent"));
        write(home.resolve(".codex/agents/codex.md"), codexWithMode("subagent"));
        write(home.resolve(".codex/agents/direct.toml"), "mode = \"subagent\"\ndescription = \"direct\"\n");

        AgentCatalogSnapshot snapshot = service().snapshot(null);

        assertThat(snapshot.getRequired("claude").mode()).isEqualTo(AgentMode.SUBAGENT);
        assertThat(snapshot.getRequired("codex").mode()).isEqualTo(AgentMode.SUBAGENT);
        assertThat(snapshot.getRequired("direct").mode()).isEqualTo(AgentMode.SUBAGENT);
        assertThat(snapshot.getRequired("claude").allowedTools()).doesNotContain("task");
    }

    @Test
    void omittedModelsUseBundledModelForExplicitPrimaryRolesAcrossVendors() throws IOException {
        write(home.resolve(".claude/agents/claude-primary.md"), """
                ---
                name: claude-primary
                description: primary
                mode: agent
                ---
                prompt
                """);
        write(home.resolve(".claude/agents/claude-subagent.md"), """
                ---
                name: claude-subagent
                description: subagent
                mode: subagent
                ---
                prompt
                """);
        write(home.resolve(".codex/agents/codex-primary.md"), """
                ---
                description: primary
                mode: agent
                ---
                prompt
                """);
        write(home.resolve(".codex/agents/codex-subagent.toml"), """
                description = "subagent"
                mode = "subagent"
                """);
        write(home.resolve(".codex/config.toml"), """
                [agents.registered]
                description = "registered"
                mode = "agent"
                config_file = "registered.toml"
                """);
        write(home.resolve(".codex/registered.toml"), """
                description = "registered"
                mode = "agent"
                """);

        AgentCatalogSnapshot snapshot = service().snapshot(null);

        assertThat(snapshot.getRequired("claude-primary").modelIds()).containsExactly("openai/gpt-4.1");
        assertThat(snapshot.getRequired("codex-primary").modelIds()).containsExactly("openai/gpt-4.1");
        assertThat(snapshot.getRequired("registered").modelIds()).containsExactly("openai/gpt-4.1");
        assertThat(snapshot.getRequired("claude-subagent").modelIds()).isEmpty();
        assertThat(snapshot.getRequired("codex-subagent").modelIds()).isEmpty();
    }

    @Test
    void codexRegisteredRoleModeConflictsAndUnknownModesAreDiagnosed() throws IOException {
        write(home.resolve(".codex/config.toml"), """
                [agents.conflict]
                description = "conflict"
                mode = "agent"
                config_file = "conflict.toml"
                [agents.unknown]
                description = "unknown"
                mode = "sidecar"
                config_file = "unknown.toml"
                """);
        write(home.resolve(".codex/conflict.toml"), """
                description = "file"
                mode = "subagent"
                """);
        write(home.resolve(".codex/unknown.toml"), "description = \"file\"\n");

        AgentCatalogSnapshot snapshot = service().snapshot(null);

        assertThat(snapshot.agents()).extracting(AgentDefinition::id).doesNotContain("conflict", "unknown");
        assertThat(snapshot.diagnostics()).extracting(AgentDiagnostic::message)
                .anyMatch(message -> message.contains("Conflicting mode"))
                .anyMatch(message -> message.contains("Invalid mode"));
    }

    @Test
    void claudeMapsCommaAndListToolsAndDisallowedTools() throws IOException {
        write(home.resolve(".claude/agents/tools.md"), """
                ---
                name: vendor-name
                description: Claude tools
                tools: Read,Glob,Grep,Bash
                disallowedTools:
                  - Bash
                model: anthropic/claude-sonnet-5
                ---
                prompt
                """);

        AgentDefinition agent = service().snapshot(null).getRequired("vendor-name");

        assertThat(agent.name()).isEqualTo("vendor-name");
        assertThat(agent.allowedTools()).containsExactly("read_file", "list_files", "search_code");
        assertThat(agent.modelIds()).containsExactly("anthropic/claude-sonnet-5");
    }

    @Test
    void inheritanceUsesCallerModelsAndIntersectsTools() throws IOException {
        write(home.resolve(".claude/agents/inherit.md"), """
                ---
                name: inherit
                description: inherited
                mode: subagent
                model: inherit
                ---
                prompt
                """);
        AgentDefinition caller = agent("caller", List.of("openai/gpt-4.1"), List.of("list_files", "read_file",
                "search_code", "write_file", "apply_patch", "display_image", "run_command", "task"));

        AgentCatalogSnapshot snapshot = service().snapshot(null);
        AgentDefinition resolved = snapshot.resolve("inherit", caller);

        assertThat(snapshot.inheritanceOf("inherit")).isEqualTo(AgentCatalogSnapshot.Inheritance.MODEL_AND_TOOLS);
        assertThat(resolved.modelIds()).containsExactly("openai/gpt-4.1");
        assertThat(resolved.allowedTools()).containsExactlyElementsOf(
                caller.allowedTools().stream().filter(tool -> !tool.equals("task")).toList());
    }

    @Test
    void malformedFilesDoNotHideValidEntriesAndRefreshesFilesystem() throws IOException {
        write(home.resolve(".claude/agents/bad.md"), "not frontmatter");
        write(home.resolve(".claude/agents/good.md"), claude("good", "good"));
        ExternalAgentCatalogService service = service();

        assertThat(service.snapshot(null).getRequired("good").description()).isEqualTo("good");
        Files.delete(home.resolve(".claude/agents/good.md"));
        write(home.resolve(".claude/agents/new.md"), claude("new", "new"));

        AgentCatalogSnapshot refreshed = service.snapshot(null);
        assertThat(refreshed.agents()).extracting(AgentDefinition::id).contains("new").doesNotContain("good");
    }

    @Test
    void codexRegisteredRolesAndModernTomlRolesAreLoaded() throws IOException {
        write(home.resolve(".codex/config.toml"), """
                [agents.registered]
                description = "Registered"
                config_file = "registered.toml"
                [agents.modern]
                description = "Modern"
                config_file = "modern.toml"
                """);
        write(home.resolve(".codex/registered.toml"), """
                name = "registered-id"
                description = "role file"
                developer_instructions = "instructions"
                model = "gpt-4.1"
                model_reasoning_effort = "high"
                sandbox_mode = "read-only"
                """);
        write(home.resolve(".codex/modern.toml"), """
                name = "modern-id"
                description = "modern role"
                developer_instructions = "modern instructions"
                model = "openai/gpt-4.1"
                tools = ["list_files", "read_file"]
                """);

        AgentCatalogSnapshot snapshot = service().snapshot(null);

        assertThat(snapshot.getRequired("registered").description()).isEqualTo("Registered");
        assertThat(snapshot.getRequired("registered").allowedTools()).containsExactly("list_files", "read_file",
                "search_code");
        assertThat(snapshot.getRequired("modern").systemPrompt()).isEqualTo("modern instructions");
    }

    @Test
    void invalidUtf8OversizeSymlinksAndTraversalAreRejected() throws IOException {
        Path agents = Files.createDirectories(home.resolve(".claude/agents"));
        Files.write(agents.resolve("utf8.md"), new byte[]{(byte) 0xc3, (byte) 0x28});
        Files.write(agents.resolve("large.md"), new byte[1024 * 1024 + 1]);
        Path outside = Files.writeString(home.resolve("outside.md"), claude("outside", "outside"));
        Files.createSymbolicLink(agents.resolve("link.md"), outside);
        write(agents.resolve("valid.md"), claude("valid", "valid"));

        AgentCatalogSnapshot snapshot = service().snapshot(null);

        assertThat(snapshot.getRequired("valid")).isNotNull();
        assertThat(snapshot.agents()).extracting(AgentDefinition::id).doesNotContain("outside");
        assertThat(snapshot.diagnostics()).hasSizeGreaterThanOrEqualTo(3);
    }

    @Test
    void symlinkedCodexConfigIsRejectedWhenSymlinksAreSupported() throws IOException {
        Path target = write(home.resolve("codex-config-target.toml"), "[agents.linked]\nconfig_file = \"role.toml\"\n");
        write(home.resolve(".codex/role.toml"), "description = \"role\"\n");
        Path config = home.resolve(".codex/config.toml");
        try {
            Files.createSymbolicLink(config, target);
        } catch (UnsupportedOperationException | FileSystemException e) {
            return;
        }

        AgentCatalogSnapshot snapshot = service().snapshot(null);

        assertThat(snapshot.agents()).extracting(AgentDefinition::id).doesNotContain("linked");
        assertThat(snapshot.diagnostics()).anyMatch(d -> d.source().equals(config));
    }

    @Test
    void unknownAgentIsRejected() {
        assertThatThrownBy(() -> service().snapshot(null).getRequired("unknown"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unknown agent");
    }

    @Test
    void snapshotAndInheritanceAreImmutableAndDefaultPlanIsExplicit() {
        AgentCatalogSnapshot snapshot = service().snapshot(null);

        assertThat(snapshot.defaultAgent().id()).isEqualTo("plan");
        Assertions.assertThatThrownBy(() -> snapshot.agents().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        Assertions.assertThatThrownBy(() -> snapshot.sources().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private ExternalAgentCatalogService service() {
        AgentDefinitionService bundled = mock(AgentDefinitionService.class);
        AgentDefinition plan = agent("plan", List.of("openai/gpt-4.1"), List.of("list_files"));
        when(bundled.list()).thenReturn(List.of(plan));
        when(bundled.defaultAgent()).thenReturn(plan);
        ModelCatalogService models = mock(ModelCatalogService.class);
        when(models.list()).thenReturn(List.of(model("openai/gpt-4.1", "GPT-4.1", "gpt-4.1", "openai"),
                model("anthropic/claude-sonnet-5", "Claude Sonnet 5", "claude-sonnet-5", "anthropic")));
        return new ExternalAgentCatalogService(bundled, models, home.toString());
    }

    private static AgentDefinition agent(String id, List<String> models, List<String> tools) {
        return new AgentDefinition(id, id, id, id, AgentMode.AGENT, models, ThinkingLevel.MEDIUM, null,
                tools.contains("write_file"), tools.contains("run_command"), tools);
    }

    private static ModelDefinition model(String id, String name, String api, String provider) {
        return new ModelDefinition(id, name, provider, api, true, true, 1000, 100, "", "", "", null, "", List.of(),
                List.of());
    }

    private static String claudeWithMode(String id, String mode) {
        return "---\nname: " + id + "\ndescription: test\nmode: " + mode + "\n---\nbody\n";
    }

    private static String codexWithMode(String mode) {
        return "---\ndescription: test\nmode: " + mode + "\n---\nbody\n";
    }

    private static String claude(String id, String description) {
        return "---\nname: " + id + "\ndescription: " + description + "\nmodel: gpt-4.1\n---\nbody\n";
    }

    private static String jupiter(String id, String description) {
        return jupiterWith("id: " + id + "\nname: " + id + "\ndescription: " + description
                + "\nmode: agent\nmodel: openai/gpt-4.1\n", "body");
    }

    private static String jupiterWith(String yaml, String body) {
        return "---\n" + yaml + "---\n" + body + "\n";
    }

    private static String codex(String id, String description) {
        return "---\ndescription: " + description + "\nmodel: gpt-4.1\n---\nbody\n";
    }

    private static Path write(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
        return path;
    }
}
