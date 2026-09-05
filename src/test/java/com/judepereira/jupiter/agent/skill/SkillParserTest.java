package com.judepereira.jupiter.agent.skill;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillParserTest {
    @TempDir Path temp;

    @Test
    void parsesOnlyFrontmatterAndIgnoresUnknownMetadata() throws Exception {
        Path directory = Files.createDirectory(temp.resolve("demo-skill"));
        Files.writeString(directory.resolve("SKILL.md"), "---\nname: demo-skill\ndescription: A demo\nunknown: value\n---\n# Body\n");

        var result = new SkillParser().parse(directory.resolve("SKILL.md"), SkillScope.REPOSITORY);

        assertTrue(result.definition().isPresent());
        assertEquals("A demo", result.definition().orElseThrow().description());
    }

    @Test
    void rejectsMalformedMetadataAndUnsafeFile() throws Exception {
        Path directory = Files.createDirectory(temp.resolve("bad"));
        Files.writeString(directory.resolve("SKILL.md"), "name: bad\ndescription: no delimiters\n");

        assertTrue(new SkillParser().parse(directory, SkillScope.REPOSITORY).error().isPresent());
    }

    @Test
    void rejectsBlankDescription() throws Exception {
        Path directory = Files.createDirectory(temp.resolve("blank-description"));
        Files.writeString(directory.resolve("SKILL.md"), "---\nname: blank-description\ndescription: \"   \\t\"\n---\n");

        var result = new SkillParser().parse(directory.resolve("SKILL.md"), SkillScope.REPOSITORY);

        assertTrue(result.definition().isEmpty());
        assertTrue(result.error().isPresent());
    }

    @Test
    void rejectsInvalidUtf8AndOversizedFiles() throws Exception {
        Path directory = Files.createDirectory(temp.resolve("bad"));
        Path file = directory.resolve("SKILL.md");
        Files.write(file, new byte[]{(byte) 0xc3, 0x28});
        assertTrue(new SkillParser().parse(directory, SkillScope.REPOSITORY).error().isPresent());
        Files.write(file, new byte[256 * 1024 + 1]);
        assertTrue(new SkillParser().parse(directory, SkillScope.REPOSITORY).error().isPresent());
    }
}
