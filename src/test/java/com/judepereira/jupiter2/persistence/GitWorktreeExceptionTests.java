package com.judepereira.jupiter2.persistence;

import org.junit.jupiter.api.Test;

import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class GitWorktreeExceptionTests {

    @Test
    void lastGitOutputLinesKeepsOnlyTheLastTenNonBlankLines() {
        String output = IntStream.rangeClosed(1, 12)
                .mapToObj(i -> "line-" + i)
                .reduce((a, b) -> a + "\n" + b)
                .orElseThrow();

        GitWorktreeException exception = new GitWorktreeException("git failed", output, null);

        assertThat(exception.lastGitOutputLines().split("\\R"))
                .hasSize(10)
                .contains("line-3", "line-12")
                .doesNotContain("line-1", "line-2");
    }
}
