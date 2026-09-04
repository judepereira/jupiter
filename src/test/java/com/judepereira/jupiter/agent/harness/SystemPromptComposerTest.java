package com.judepereira.jupiter.agent.harness;

import com.judepereira.jupiter.agent.catalog.AgentDefinition;
import com.judepereira.jupiter.agent.catalog.AgentMode;
import com.judepereira.jupiter.agent.catalog.ThinkingLevel;
import com.judepereira.jupiter.testsupport.SystemPromptTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class SystemPromptComposerTest {

    @Test
    public void compose_withoutAppendageAddsDefaultPromptAndEnv(@TempDir Path workspaceRoot) {
        String prompt = new SystemPromptComposer().compose(null, workspaceRoot.toString());

        assertThat(prompt)
                .isEqualTo(SystemPromptTestSupport.composeExpected(null, workspaceRoot))
                .contains("## Subagent Delegation")
                .contains("A subagent started with the `task` tool has zero history of the parent agent's conversation.")
                .contains("Make every delegated task self-contained.");
    }

    @Test
    public void composeForAgent_addsDefaultPromptAgentAppendageAndEnv(@TempDir Path workspaceRoot) {
        AgentDefinition agent = new AgentDefinition("agent", "Agent", "desc", "You are an appendage.",
                AgentMode.AGENT, "openai/gpt-5.5", ThinkingLevel.MEDIUM, null, true, true, List.of());

        String prompt = new SystemPromptComposer().composeForAgent(agent, workspaceRoot.toString());

        assertThat(prompt).isEqualTo(SystemPromptTestSupport.composeExpected("You are an appendage.", workspaceRoot));
    }
}
