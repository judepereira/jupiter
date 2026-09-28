package com.judepereira.jupiter.agent.catalog;

import java.nio.file.Path;

public record AgentSource(Kind kind, Scope scope, Path path) {
    public enum Kind {
        BUNDLED, JUPITER, CLAUDE, CODEX
    }
    public enum Scope {
        BUNDLED, HOME, WORKSPACE
    }
}
