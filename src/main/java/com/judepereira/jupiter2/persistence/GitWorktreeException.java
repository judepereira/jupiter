package com.judepereira.jupiter2.persistence;

public class GitWorktreeException extends RuntimeException {
    private final String stdout;
    private final String stderr;

    public GitWorktreeException(String message, String stdout, String stderr) {
        super(message);
        this.stdout = stdout;
        this.stderr = stderr;
    }

    public GitWorktreeException(String message, String stdout, String stderr, Throwable cause) {
        super(message, cause);
        this.stdout = stdout;
        this.stderr = stderr;
    }

    public String gitOutput() {
        String combined = String.join("\n", nonBlankLines(stdout), nonBlankLines(stderr));
        return combined.isBlank() ? null : combined;
    }

    public String lastGitOutputLines() {
        String output = gitOutput();
        if (output == null) {
            return null;
        }

        var lines = output.lines().filter(line -> !line.isBlank()).toList();
        int fromIndex = Math.max(0, lines.size() - 10);
        return String.join("\n", lines.subList(fromIndex, lines.size()));
    }

    public String stdout() {
        return stdout;
    }

    public String stderr() {
        return stderr;
    }

    private static String nonBlankLines(String value) {
        return value == null ? "" : value.trim();
    }
}
