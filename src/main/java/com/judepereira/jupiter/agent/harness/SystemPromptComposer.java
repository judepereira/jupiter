package com.judepereira.jupiter.agent.harness;

import com.judepereira.jupiter.agent.catalog.AgentDefinition;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

@Service
public class SystemPromptComposer {

    private static final String DEFAULT_SYSTEM_PROMPT_RESOURCE = "/system-prompt.md";

    private final String defaultSystemPrompt;

    public SystemPromptComposer() {
        this.defaultSystemPrompt = loadRequiredResource(DEFAULT_SYSTEM_PROMPT_RESOURCE);
    }

    public String composeForAgent(AgentDefinition agent, String workspaceRoot) {
        return compose(requireNonBlank(agent.systemPrompt(), "agent system prompt appendage: " + agent.id()), workspaceRoot);
    }

    public String compose(String appendage, String workspaceRoot) {
        String defaultPrompt = requireNonBlank(defaultSystemPrompt, "default system prompt resource");
        String resolvedWorkspaceRoot = requireNonBlank(workspaceRoot, "workspace root");

        if (appendage == null || appendage.isBlank()) {
            return String.join("\n\n", defaultPrompt, buildEnvAppendage(resolvedWorkspaceRoot));
        }

        return String.join("\n\n", defaultPrompt, appendage, buildEnvAppendage(resolvedWorkspaceRoot));
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
