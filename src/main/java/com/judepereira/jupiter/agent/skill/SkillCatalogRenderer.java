package com.judepereira.jupiter.agent.skill;

import java.nio.file.Path;
import org.springframework.stereotype.Component;

/** Renders skill metadata for inclusion in the system prompt. */
@Component
public final class SkillCatalogRenderer {
    public String render(SkillCatalog catalog) {
        if (catalog == null || catalog.skills().isEmpty()) {
            return "";
        }
        StringBuilder output = new StringBuilder("<available_skills>\n");
        for (SkillDefinition skill : catalog.skills()) {
            output.append("  <skill>\n")
                    .append("    <name>").append(escape(skill.name())).append("</name>\n")
                    .append("    <description>").append(escape(skill.description())).append("</description>\n")
                    .append("    <path>").append(escape(absolutePath(skill.skillFile()))).append("</path>\n")
                    .append("  </skill>\n");
        }
        output.append("  <usage>\n")
                .append("    <bullet>Skills are available separately per workspace.</bullet>\n")
                .append("    <bullet>Use a skill when its description clearly matches the task, or when the user explicitly names it with $skill-name.</bullet>\n")
                .append("    <bullet>Read the complete SKILL.md file before using a skill.</bullet>\n")
                .append("    <bullet>Activated skills apply to the current turn only.</bullet>\n")
                .append("    <bullet>Multiple skills may be activated together.</bullet>\n")
                .append("    <bullet>Supporting files are available at paths relative to the skill directory; read them only when needed.</bullet>\n")
                .append("  </usage>\n")
                .append("</available_skills>");
        return output.toString();
    }

    private static String absolutePath(Path path) {
        return path.toAbsolutePath().normalize().toString();
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }
}
