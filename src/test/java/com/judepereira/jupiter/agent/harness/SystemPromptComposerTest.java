package com.judepereira.jupiter.agent.harness;

import com.judepereira.jupiter.agent.catalog.AgentDefinition;
import com.judepereira.jupiter.agent.catalog.AgentMode;
import com.judepereira.jupiter.agent.catalog.ThinkingLevel;
import com.judepereira.jupiter.agent.skill.SkillCatalog;
import com.judepereira.jupiter.agent.skill.SkillDefinition;
import com.judepereira.jupiter.agent.skill.SkillScope;
import com.judepereira.jupiter.testsupport.SystemPromptTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class SystemPromptComposerTest {

    @Test
    public void compose_withoutAppendageAddsDefaultPromptAndEnv(@TempDir Path workspaceRoot) {
        String prompt = new SystemPromptComposer(new com.judepereira.jupiter.agent.skill.SkillCatalogRenderer()).compose(null, workspaceRoot.toString(), new SkillCatalog(List.of(), List.of()));

        assertThat(prompt)
                .isEqualTo(SystemPromptTestSupport.composeExpected(null, workspaceRoot))
                .contains("## Skills\n"
                        + "Skills are reusable task instructions stored in SKILL.md files.\n"
                        + "Available skills are provided separately for each workspace.\n"
                        + "Use a skill when:\n"
                        + "- Its name is explicitly mentioned.\n"
                        + "- Its description clearly matches the task.\n"
                        + "Before following a skill, read the complete SKILL.md file.\n"
                        + "Activated skills apply to the current turn only unless referenced again.\n"
                        + "Use multiple skills when they are all necessary.\n"
                        + "Supporting files are relative to the directory containing SKILL.md.")
                .contains("## Subagent Delegation")
                .contains("A subagent started with the `task` tool has zero history of the parent agent's conversation.")
                .contains("Make every delegated task self-contained.");
    }

    @Test
    public void compose_insertsCatalogBeforeEnvironment(@TempDir Path workspaceRoot) {
        var skill = new SkillDefinition("demo", "demo description", workspaceRoot, workspaceRoot.resolve("SKILL.md"), SkillScope.REPOSITORY);
        String prompt = new SystemPromptComposer(new com.judepereira.jupiter.agent.skill.SkillCatalogRenderer()).compose("appendage", workspaceRoot.toString(), new SkillCatalog(List.of(skill), List.of()));
        assertThat(prompt).containsSubsequence("appendage", "<available_skills>", "demo description", "<env>");
    }

    @Test
    public void composeForAgent_addsDefaultPromptAgentAppendageAndEnv(@TempDir Path workspaceRoot) {
        AgentDefinition agent = new AgentDefinition("agent", "Agent", "desc", "You are an appendage.",
                AgentMode.AGENT, "openai/gpt-5.5", ThinkingLevel.MEDIUM, null, true, true, List.of());

        String prompt = new SystemPromptComposer(new com.judepereira.jupiter.agent.skill.SkillCatalogRenderer()).composeForAgent(agent, workspaceRoot.toString(), new SkillCatalog(List.of(), List.of()));

        assertThat(prompt).isEqualTo(SystemPromptTestSupport.composeExpected("You are an appendage.", workspaceRoot));
    }
}
