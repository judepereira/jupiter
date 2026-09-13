package com.judepereira.jupiter.agent.skill;

import com.judepereira.jupiter.agent.catalog.ModelDefinition;
import com.judepereira.jupiter.testsupport.SkillTestSupport;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SkillCatalogRendererTest {
    @Test
    void rendersMetadataOnlyWithSafeXmlAndNormalizedAbsolutePath() {
        SkillDefinition skill = new SkillDefinition("demo", "Use <carefully> & safely", Path.of("skills/demo"),
                Path.of("/tmp/work/../work/skills/demo/SKILL.md"), SkillScope.REPOSITORY);

        String rendered = SkillTestSupport.defaultComponents().renderer().render(new SkillCatalog(List.of(skill), List.of()));

        assertThat(rendered)
                .doesNotContain("Skills are available separately per workspace.", "Activated skills apply to the current turn only.")
                .isEqualTo("<available_skills>\n" +
                "  <skill>\n" +
                "    <name>demo</name>\n" +
                "    <description>Use &lt;carefully&gt; &amp; safely</description>\n" +
                "    <path>/tmp/work/skills/demo/SKILL.md</path>\n" +
                "  </skill>\n" +
                "  <usage>\n" +
                "    <bullet>Use a skill when its description clearly matches the task, or when the user explicitly names it with $skill-name.</bullet>\n" +
                "    <bullet>Read the complete SKILL.md file before using a skill.</bullet>\n" +
                "    <bullet>Multiple skills may be activated together.</bullet>\n" +
                "    <bullet>Supporting files are available at paths relative to the skill directory; read them only when needed.</bullet>\n" +
                "  </usage>\n" +
                "</available_skills>");
    }

    @Test
    void rendersEmptyCatalogAsBlank() {
        assertThat(SkillTestSupport.defaultComponents().renderer().render(new SkillCatalog(List.of(), List.of())))
                .isEmpty();
    }

    @Test
    void boundsOverBudgetCatalogToCompleteEntriesAndDeterministicallyReportsOmissions() {
        var skills = IntStream.range(0, 30)
                .mapToObj(i -> SkillTestSupport.skill("skill-" + i, "description-" + i,
                        Path.of("/tmp/skill-" + i), SkillScope.REPOSITORY))
                .toList();
        var model = model("small", 10000);
        String rendered = SkillTestSupport.defaultComponents().renderer().render(new SkillCatalog(skills, List.of()), model);

        assertThat(rendered.length()).isLessThanOrEqualTo(model.contextTokens() * 4 * 2 / 100);
        assertThat(rendered).contains("<omitted_skills>28 skill entries omitted");
        assertThat(rendered).contains("<name>skill-0</name>").contains("<name>skill-1</name>")
                .doesNotContain("<name>skill-2</name>");
        assertThat(rendered).contains("</skill>");
    }

    @Test
    void usesModelSpecificBudgetsAndPreservesCompleteRenderingAtBoundary() {
        var skill = SkillTestSupport.skill("boundary", "description", Path.of("/tmp/boundary"), SkillScope.REPOSITORY);
        var catalog = SkillTestSupport.catalog(skill);
        var renderer = SkillTestSupport.defaultComponents().renderer();
        String full = renderer.render(catalog);
        int contextTokens = (full.length() * 100 + 7) / 8;

        assertThat(renderer.render(catalog, model("boundary", contextTokens))).isEqualTo(full);
        assertThat(renderer.render(catalog, model("larger", contextTokens + 100))).isEqualTo(full);
    }

    @Test
    void rejectsNonPositiveModelContext() {
        assertThatThrownBy(() -> SkillTestSupport.defaultComponents().renderer().render(
                SkillTestSupport.catalog(SkillTestSupport.skill("x", "y", Path.of("/tmp/x"), SkillScope.REPOSITORY)),
                model("invalid", 0))).isInstanceOf(IllegalArgumentException.class);
    }

    private static ModelDefinition model(String id, int contextTokens) {
        return new ModelDefinition(id, id, "test", id, false, true, contextTokens, 32, null, null, null);
    }
}
