package com.judepereira.jupiter.agent.skill;

import com.judepereira.jupiter.agent.catalog.ModelDefinition;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/** Renders bounded skill metadata for inclusion in the system prompt. */
@Component
public final class SkillCatalogRenderer {
    private static final int CHARACTERS_PER_TOKEN = 4;
    private static final int SKILL_CONTEXT_PERCENT = 2;
    private static final int LEGACY_FALLBACK_CONTEXT_TOKENS = 32_000;
    private static final String USAGE = "  <usage>\n"
            + "    <bullet>Use a skill when its description clearly matches the task, or when the user explicitly names it with $skill-name.</bullet>\n"
            + "    <bullet>Read the complete SKILL.md file before using a skill.</bullet>\n"
            + "    <bullet>Multiple skills may be activated together.</bullet>\n"
            + "    <bullet>Supporting files are available at paths relative to the skill directory; read them only when needed.</bullet>\n"
            + "  </usage>\n";

    /** Conservative compatibility path for direct tests/services without model wiring. */
    public String render(SkillCatalog catalog) {
        return render(catalog, null);
    }

    public String render(SkillCatalog catalog, ModelDefinition model) {
        if (catalog == null || catalog.skills().isEmpty()) return "";
        int contextTokens = model == null ? LEGACY_FALLBACK_CONTEXT_TOKENS : model.contextTokens();
        if (contextTokens <= 0) throw new IllegalArgumentException("Model context tokens must be positive");
        long budgetValue = (long) contextTokens * CHARACTERS_PER_TOKEN * SKILL_CONTEXT_PERCENT / 100;
        int budget = (int) Math.min(Integer.MAX_VALUE, budgetValue);
        String prefix = "<available_skills>\n";
        String suffix = USAGE + "</available_skills>";
        List<String> entries = catalog.skills().stream().map(SkillCatalogRenderer::renderSkill).toList();
        int completeLength = prefix.length() + suffix.length() + entries.stream().mapToInt(String::length).sum();
        if (completeLength <= budget) {
            return prefix + String.join("", entries) + suffix;
        }
        String reservedNotice = omissionNotice(catalog.skills().size());
        int available = budget - prefix.length() - suffix.length() - reservedNotice.length();
        if (available < 0) throw new IllegalArgumentException("Model context is too small for the skill catalog wrapper");
        List<String> selected = new ArrayList<>();
        int used = 0;
        for (String entry : entries) {
            if (used + entry.length() > available) break;
            selected.add(entry);
            used += entry.length();
        }
        if (selected.size() == entries.size()) {
            // Under-budget catalogs retain the established rendering, without an omission marker.
            return prefix + String.join("", selected) + suffix;
        }
        int omittedCount = entries.size() - selected.size();
        String result = prefix + String.join("", selected) + omissionNotice(omittedCount) + suffix;
        if (result.length() > budget) throw new IllegalStateException("Skill omission notice exceeds model context budget");
        return result;
    }

    private static String renderSkill(SkillDefinition skill) {
        return "  <skill>\n" + "    <name>" + escape(skill.name()) + "</name>\n"
                + "    <description>" + escape(skill.description()) + "</description>\n"
                + "    <path>" + escape(absolutePath(skill.skillFile())) + "</path>\n"
                + "  </skill>\n";
    }

    private static String omissionNotice(int total) {
        return "  <omitted_skills>" + total + " skill entries omitted due to context budget.</omitted_skills>\n";
    }

    private static String absolutePath(Path path) { return path.toAbsolutePath().normalize().toString(); }
    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }
}
