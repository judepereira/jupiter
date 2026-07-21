package com.judepereira.jupiter2.agent.harness;

import com.judepereira.jupiter2.agent.catalog.AgentDefinition;
import com.judepereira.jupiter2.agent.catalog.AgentMode;
import com.judepereira.jupiter2.agent.catalog.ThinkingLevel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class SystemPromptComposerTest {

    @Test
    public void compose_withoutAppendageAddsDefaultPromptAndEnv(@TempDir Path workspaceRoot) {
        String prompt = new SystemPromptComposer().compose(null, workspaceRoot.toString());

        assertThat(prompt).contains(
                "You are Jupiter, a coding agent operating inside a single workspace.",
                "Follow the user's request exactly, use tools when needed, keep changes minimal, and fail loudly when something is wrong.",
                "Working directory: " + workspaceRoot.toAbsolutePath().normalize(),
                "Current date: " + LocalDate.now(),
                "Operating system: Ubuntu Linux",
                "Shell: bash");
    }

    @Test
    public void composeForAgent_addsDefaultPromptAgentAppendageAndEnv(@TempDir Path workspaceRoot) {
        AgentDefinition agent = new AgentDefinition("agent", "Agent", "desc", "You are an appendage.",
                AgentMode.AGENT, "openai/gpt-5.5", ThinkingLevel.MEDIUM, null, true, true, List.of());

        String prompt = new SystemPromptComposer().composeForAgent(agent, workspaceRoot.toString());

        assertThat(prompt).contains(
                "You are Jupiter, a coding agent operating inside a single workspace.",
                "You are an appendage.",
                "Working directory: " + workspaceRoot.toAbsolutePath().normalize(),
                "Current date: " + LocalDate.now(),
                "Operating system: Ubuntu Linux",
                "Shell: bash");
    }
}
