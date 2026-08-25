package com.judepereira.jupiter.command;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class CommandOutputFormatterTest {

    @Test
    void summarizes_last_ten_non_blank_lines() {
        String output = "\nline1\nline2\nline3\nline4\nline5\nline6\nline7\nline8\nline9\nline10\nline11\nline12\n";

        assertThat(CommandOutputFormatter.summarizeOutput(output))
                .isEqualTo("line3\nline4\nline5\nline6\nline7\nline8\nline9\nline10\nline11\nline12");
    }

    @Test
    void formats_output_as_fenced_markdown_code_block() {
        String output = "line1\nline2";

        assertThat(CommandOutputFormatter.formatForAssistantMessage(output))
                .isEqualTo("```\nline1\nline2\n```");
    }

    @Test
    void chooses_a_safe_fence_when_output_contains_backticks() {
        String output = "line1\n```\nline2\n`````\nline3";

        String fence = "`".repeat(6);

        assertThat(CommandOutputFormatter.formatForAssistantMessage(output))
                .startsWith(fence + "\n")
                .endsWith("\n" + fence)
                .contains(output);
    }

    @Test
    void preserves_no_output_message() {
        assertThat(CommandOutputFormatter.formatForAssistantMessage("   "))
                .isEqualTo(CommandOutputFormatter.NO_OUTPUT);
    }
}
