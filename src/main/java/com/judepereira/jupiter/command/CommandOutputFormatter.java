package com.judepereira.jupiter.command;

import java.util.List;

final class CommandOutputFormatter {

    static final String NO_OUTPUT = "command completed with no output";

    private CommandOutputFormatter() {
    }

    static String formatForAssistantMessage(String output) {
        String summary = summarizeOutput(output);
        if (NO_OUTPUT.equals(summary)) {
            return summary;
        }
        return fence(summary);
    }

    static String summarizeOutput(String output) {
        if (output == null || output.isBlank()) {
            return NO_OUTPUT;
        }
        List<String> lines = output.lines().filter(line -> !line.isBlank()).toList();
        if (lines.isEmpty()) {
            return NO_OUTPUT;
        }
        int from = Math.max(0, lines.size() - 10);
        return String.join("\n", lines.subList(from, lines.size()));
    }

    static String fence(String content) {
        String fence = "`".repeat(Math.max(3, longestBacktickRun(content) + 1));
        return fence + "\n" + content + "\n" + fence;
    }

    private static int longestBacktickRun(String content) {
        int longest = 0;
        int current = 0;
        for (int i = 0; i < content.length(); i++) {
            if (content.charAt(i) == '`') {
                current++;
                longest = Math.max(longest, current);
            } else {
                current = 0;
            }
        }
        return longest;
    }
}
