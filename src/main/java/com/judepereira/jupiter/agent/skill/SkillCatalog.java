package com.judepereira.jupiter.agent.skill;

import java.util.List;

public record SkillCatalog(List<SkillDefinition> skills, List<SkillLoadError> errors) {
    public SkillCatalog {
        skills = List.copyOf(skills);
        errors = List.copyOf(errors);
    }
}
