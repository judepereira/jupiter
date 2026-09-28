package com.judepereira.jupiter.agent.catalog;

import java.nio.file.Path;

public record AgentDiagnostic(Severity severity, Path source, String message) {
    public enum Severity {
        WARNING, ERROR
    }
}
