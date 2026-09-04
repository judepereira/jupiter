package com.judepereira.jupiter.agent.skill;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SkillCatalogRendererTest {
    @Test
    void rendersMetadataOnlyWithSafeXmlAndNormalizedAbsolutePath() {
        SkillDefinition skill = new SkillDefinition("demo", "Use <carefully> & safely", Path.of("skills/demo"),
                Path.of("/tmp/work/../work/skills/demo/SKILL.md"), SkillScope.REPOSITORY);

        String rendered = new SkillCatalogRenderer().render(new SkillCatalog(List.of(skill), List.of()));

        assertThat(rendered).isEqualTo("<available_skills>\n" +
                "  <skill>\n" +
                "    <name>demo</name>\n" +
                "    <description>Use &lt;carefully&gt; &amp; safely</description>\n" +
                "    <path>/tmp/work/skills/demo/SKILL.md</path>\n" +
                "  </skill>\n" +
                "  <usage>\n" +
                "    <bullet>Skills are available separately per workspace.</bullet>\n" +
                "    <bullet>Use a skill when its description clearly matches the task, or when the user explicitly names it with $skill-name.</bullet>\n" +
                "    <bullet>Read the complete SKILL.md file before using a skill.</bullet>\n" +
                "    <bullet>Activated skills apply to the current turn only.</bullet>\n" +
                "    <bullet>Multiple skills may be activated together.</bullet>\n" +
                "    <bullet>Supporting files are available at paths relative to the skill directory; read them only when needed.</bullet>\n" +
                "  </usage>\n" +
                "</available_skills>");
    }

    @Test
    void rendersEmptyCatalogAsBlank() {
        assertThat(new SkillCatalogRenderer().render(new SkillCatalog(List.of(), List.of()))).isEmpty();
    }
}
