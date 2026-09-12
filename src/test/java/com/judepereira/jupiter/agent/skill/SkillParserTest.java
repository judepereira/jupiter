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
    void derivesNameFromDirectoryWhenNameIsAbsent() throws Exception {
        Path directory = Files.createDirectory(temp.resolve("codex-skill"));
        Files.writeString(directory.resolve("SKILL.md"), "---\ndescription: A demo\n---\n");

        var result = new SkillParser().parse(directory.resolve("SKILL.md"), SkillScope.REPOSITORY);

        assertEquals("codex-skill", result.definition().orElseThrow().name());
    }

    @Test
    void acceptsExplicitNameDifferentFromDirectory() throws Exception {
        Path directory = Files.createDirectory(temp.resolve("directory-name"));
        Files.writeString(directory.resolve("SKILL.md"), "---\nname: effective-name\ndescription: A demo\n---\n");

        var result = new SkillParser().parse(directory.resolve("SKILL.md"), SkillScope.REPOSITORY);

        assertEquals("effective-name", result.definition().orElseThrow().name());
    }

    @Test
    void rejectsInvalidDerivedAndExplicitNames() throws Exception {
        Path invalidDirectory = Files.createDirectory(temp.resolve("Invalid_Directory"));
        Files.writeString(invalidDirectory.resolve("SKILL.md"), "---\ndescription: A demo\n---\n");
        Path invalidExplicit = Files.createDirectory(temp.resolve("valid-directory"));
        Files.writeString(invalidExplicit.resolve("SKILL.md"), "---\nname: 42\ndescription: A demo\n---\n");

        var parser = new SkillParser();
        assertTrue(parser.parse(invalidDirectory.resolve("SKILL.md"), SkillScope.REPOSITORY).error().isPresent());
        assertTrue(parser.parse(invalidExplicit.resolve("SKILL.md"), SkillScope.REPOSITORY).error().isPresent());
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
