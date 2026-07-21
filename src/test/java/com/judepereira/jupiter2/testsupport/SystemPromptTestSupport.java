package com.judepereira.jupiter2.testsupport;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;

public final class SystemPromptTestSupport {

    private static final String DEFAULT_SYSTEM_PROMPT_RESOURCE = "/system-prompt.md";

    private SystemPromptTestSupport() {
    }

    public static String defaultSystemPrompt() {
        try (InputStream inputStream = SystemPromptTestSupport.class.getResourceAsStream(DEFAULT_SYSTEM_PROMPT_RESOURCE)) {
            if (inputStream == null) {
                throw new IllegalStateException("Missing test resource: " + DEFAULT_SYSTEM_PROMPT_RESOURCE);
            }
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load test resource: " + DEFAULT_SYSTEM_PROMPT_RESOURCE, e);
        }
    }

    public static String envAppendage(Path workspaceRoot) {
        return "<env>\n" +
                "Working directory: " + workspaceRoot.toAbsolutePath().normalize() + "\n" +
                "Current date: " + LocalDate.now() + "\n" +
                "Operating system: Ubuntu Linux\n" +
                "Shell: bash\n" +
                "</env>";
    }

    public static String composeExpected(String appendage, Path workspaceRoot) {
        String defaultPrompt = defaultSystemPrompt();
        String env = envAppendage(workspaceRoot);
        if (appendage == null || appendage.isBlank()) {
            return String.join("\n\n", defaultPrompt, env);
        }
        return String.join("\n\n", defaultPrompt, appendage, env);
    }
}
