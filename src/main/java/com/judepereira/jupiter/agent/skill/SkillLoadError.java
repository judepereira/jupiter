package com.judepereira.jupiter.agent.skill;

import java.nio.file.Path;

public record SkillLoadError(Path path, String message, String candidateSkillName) {
    public SkillLoadError(Path path, String message) {
        this(path, message, null);
    }
}
