package com.judepereira.jupiter.agent.harness;

import com.judepereira.jupiter.agent.catalog.AgentDefinition;
import com.judepereira.jupiter.agent.skill.SkillCatalog;
import com.judepereira.jupiter.agent.skill.SkillCatalogRenderer;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

@Service
public class SystemPromptComposer {

    private static final String DEFAULT_SYSTEM_PROMPT_RESOURCE = "/system-prompt.md";

    private final String defaultSystemPrompt;
    private final SkillCatalogRenderer skillCatalogRenderer;

    public SystemPromptComposer(SkillCatalogRenderer skillCatalogRenderer) {
        this.defaultSystemPrompt = loadRequiredResource(DEFAULT_SYSTEM_PROMPT_RESOURCE);
        this.skillCatalogRenderer = skillCatalogRenderer;
    }

    public String composeForAgent(AgentDefinition agent, String workspaceRoot, SkillCatalog catalog) {
        return compose(requireNonBlank(agent.systemPrompt(), "agent system prompt appendage: " + agent.id()), workspaceRoot, catalog);
    }

    public String compose(String appendage, String workspaceRoot, SkillCatalog catalog) {
        String defaultPrompt = requireNonBlank(defaultSystemPrompt, "default system prompt resource");
        String resolvedWorkspaceRoot = requireNonBlank(workspaceRoot, "workspace root");
        String renderedCatalog = skillCatalogRenderer.render(catalog);
        java.util.List<String> sections = new java.util.ArrayList<>(java.util.List.of(defaultPrompt));
        if (appendage != null && !appendage.isBlank()) sections.add(appendage);
        if (!renderedCatalog.isBlank()) sections.add(renderedCatalog);
        sections.add(buildEnvAppendage(resolvedWorkspaceRoot));
        return String.join("\n\n", sections);
    }

    private static String buildEnvAppendage(String workspaceRoot) {
        return "<env>\n" +
                "Working directory: " + Path.of(workspaceRoot).toAbsolutePath().normalize() + "\n" +
                "Current date: " + java.time.LocalDate.now() + "\n" +
                "Operating system: Ubuntu Linux\n" +
                "Shell: bash\n" +
                "</env>";
    }

    private static String requireNonBlank(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required " + label);
        }
        return value;
    }

    private static String loadRequiredResource(String path) {
        try (InputStream inputStream = SystemPromptComposer.class.getResourceAsStream(path)) {
            if (inputStream == null) {
                throw new IllegalStateException("Missing required resource: " + path);
            }
            String content = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8).strip();
            if (content.isBlank()) {
                throw new IllegalStateException("Blank required resource: " + path);
            }
            return content;
        } catch (Exception e) {
            if (e instanceof IllegalStateException ise) {
                throw ise;
            }
            throw new IllegalStateException("Failed to load required resource: " + path, e);
        }
    }
}
