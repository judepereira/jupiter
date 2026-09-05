package com.judepereira.jupiter.agent.skill;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Resolves explicit skill references in user text. */
@Component
public final class SkillInvocationResolver {
    private static final Pattern INVOCATION = Pattern.compile("\\$([a-z0-9][a-z0-9-]{0,63})");

    public Resolution resolveExplicit(String userMessage, SkillCatalog catalog) {
        if (userMessage == null || catalog == null) return new Resolution(List.of(), List.of());
        var healthy = catalog.skills().stream().collect(java.util.stream.Collectors.toMap(
                SkillDefinition::name, s -> s, (a, b) -> a));
        var broken = catalog.errors().stream()
                .map(SkillLoadError::candidateSkillName)
                .filter(name -> name != null && name.matches("[a-z0-9][a-z0-9-]{0,63}"))
                .collect(java.util.stream.Collectors.toSet());
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<SkillDefinition> skills = new ArrayList<>();
        List<String> brokenMatches = new ArrayList<>();
        Matcher matcher = INVOCATION.matcher(userMessage);
        while (matcher.find()) {
            String name = matcher.group(1);
            if (!seen.add(name)) continue;
            if (healthy.containsKey(name)) skills.add(healthy.get(name));
            else if (broken.contains(name)) brokenMatches.add(name);
        }
        return new Resolution(List.copyOf(skills), List.copyOf(brokenMatches));
    }

    public record Resolution(List<SkillDefinition> skills, List<String> brokenSkills) {
        public Resolution { skills = List.copyOf(skills); brokenSkills = List.copyOf(brokenSkills); }
    }
}
