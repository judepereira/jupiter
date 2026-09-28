package com.judepereira.jupiter.command;

/**
 * Extracts line-oriented YAML frontmatter without applying command-specific
 * policy.
 */
public final class CommandFrontMatterExtractor {
    private CommandFrontMatterExtractor() {
    }

    public static Result extract(String content) {
        if (content == null || !isDelimiterLine(content, 0)) {
            return new Result(Status.ABSENT, "", content == null ? "" : content);
        }

        int firstLineEnd = lineEnd(content, 0);
        int yamlStart = firstLineEnd < content.length() ? nextLineStart(content, firstLineEnd) : content.length();
        int closingStart = findClosingDelimiter(content, yamlStart);
        if (closingStart < 0) {
            return new Result(Status.UNTERMINATED, "", content);
        }

        int closingLineEnd = lineEnd(content, closingStart);
        int bodyStart = closingLineEnd < content.length() ? nextLineStart(content, closingLineEnd) : content.length();
        while (bodyStart < content.length()
                && (content.charAt(bodyStart) == '\n' || content.charAt(bodyStart) == '\r')) {
            bodyStart++;
        }
        return new Result(Status.COMPLETE, content.substring(yamlStart, closingStart).trim(),
                content.substring(bodyStart));
    }

    private static int findClosingDelimiter(String content, int start) {
        int lineStart = start;
        while (lineStart < content.length()) {
            if (isDelimiterLine(content, lineStart)) {
                return lineStart;
            }
            int end = lineEnd(content, lineStart);
            if (end == content.length()) {
                break;
            }
            lineStart = nextLineStart(content, end);
        }
        return -1;
    }

    private static boolean isDelimiterLine(String content, int start) {
        if (!content.startsWith("---", start)) {
            return false;
        }
        int end = start + 3;
        return end == content.length() || content.charAt(end) == '\n' || content.charAt(end) == '\r';
    }

    private static int lineEnd(String content, int start) {
        int lf = content.indexOf('\n', start);
        return lf < 0 ? content.length() : lf;
    }

    private static int nextLineStart(String content, int lineEnd) {
        return lineEnd + (lineEnd < content.length() && content.charAt(lineEnd) == '\n' ? 1 : 2);
    }

    public enum Status {
        ABSENT, COMPLETE, UNTERMINATED
    }

    public record Result(Status status, String yaml, String body) {
    }
}
