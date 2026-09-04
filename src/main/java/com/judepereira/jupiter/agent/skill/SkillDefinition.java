package com.judepereira.jupiter.agent.skill;

import java.nio.file.Path;

public record SkillDefinition(String name, String description, Path directory, Path skillFile, SkillScope scope) {
}
