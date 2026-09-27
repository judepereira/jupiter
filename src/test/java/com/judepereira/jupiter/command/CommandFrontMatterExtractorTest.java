package com.judepereira.jupiter.command;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CommandFrontMatterExtractorTest {
    @Test
    void extractsLfFrontmatterAndNormalizesBlankLineAfterClosingDelimiter() {
        CommandFrontMatterExtractor.Result result = CommandFrontMatterExtractor
                .extract("---\nid: command\n---\n\nbody\n");

        assertThat(result.status()).isEqualTo(CommandFrontMatterExtractor.Status.COMPLETE);
        assertThat(result.yaml()).isEqualTo("id: command");
        assertThat(result.body()).isEqualTo("body\n");
    }

    @Test
    void extractsCrLfFrontmatterWithoutDroppingBodyContent() {
        CommandFrontMatterExtractor.Result result = CommandFrontMatterExtractor
                .extract("---\r\nid: command\r\n---\r\n\r\nbody\r\n");

        assertThat(result.status()).isEqualTo(CommandFrontMatterExtractor.Status.COMPLETE);
        assertThat(result.yaml()).isEqualTo("id: command");
        assertThat(result.body()).isEqualTo("body\r\n");
    }

    @Test
    void distinguishesAbsentUnterminatedAndEmptyFrontmatter() {
        assertThat(CommandFrontMatterExtractor.extract("body").status())
                .isEqualTo(CommandFrontMatterExtractor.Status.ABSENT);
        assertThat(CommandFrontMatterExtractor.extract("---\nname: command\nbody").status())
                .isEqualTo(CommandFrontMatterExtractor.Status.UNTERMINATED);

        CommandFrontMatterExtractor.Result empty = CommandFrontMatterExtractor.extract("---\n---\nbody");
        assertThat(empty.status()).isEqualTo(CommandFrontMatterExtractor.Status.COMPLETE);
        assertThat(empty.yaml()).isEmpty();
        assertThat(empty.body()).isEqualTo("body");
    }

    @Test
    void onlyStandaloneDelimitersCloseFrontmatter() {
        CommandFrontMatterExtractor.Result result = CommandFrontMatterExtractor
                .extract("---\ntext: ---not-a-delimiter\n---not-a-delimiter\n---\nbody");

        assertThat(result.status()).isEqualTo(CommandFrontMatterExtractor.Status.COMPLETE);
        assertThat(result.yaml()).isEqualTo("text: ---not-a-delimiter\n---not-a-delimiter");
        assertThat(result.body()).isEqualTo("body");
    }

    @Test
    void supportsOpeningAndClosingDelimiterAtBoundaries() {
        assertThat(CommandFrontMatterExtractor.extract("---\n---").body()).isEmpty();
        assertThat(CommandFrontMatterExtractor.extract("---\n---\n").body()).isEmpty();
        assertThat(CommandFrontMatterExtractor.extract("---\n---\nbody").body()).isEqualTo("body");
    }
}
