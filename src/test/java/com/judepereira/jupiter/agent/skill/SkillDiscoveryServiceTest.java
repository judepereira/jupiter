package com.judepereira.jupiter.agent.skill;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillDiscoveryServiceTest {
    @TempDir Path temp;

    @Test
    void repositoryOverridesUserAndDiscoveryIsSorted() throws Exception {
        Path workspace = Files.createDirectory(temp.resolve("workspace"));
        Path home = Files.createDirectory(temp.resolve("home"));
        write(home.resolve(".agents/skills/user-only"), "user-only", "user");
        write(home.resolve(".agents/skills/shared"), "shared", "user");
        write(workspace.resolve(".agents/skills/shared"), "shared", "repo");
        write(workspace.resolve(".agents/skills/repo-only"), "repo-only", "repo");

        var catalog = new SkillDiscoveryService(new SkillParser(), home.toString()).discover(workspace);

        assertEquals(java.util.List.of("repo-only", "shared", "user-only"), catalog.skills().stream().map(SkillDefinition::name).toList());
        assertEquals("repo", catalog.skills().stream().filter(s -> s.name().equals("shared")).findFirst().orElseThrow().description());
    }

    @Test
    void ignoresNestedDirectoriesAndCollectsBrokenSkills() throws Exception {
        Path workspace = Files.createDirectory(temp.resolve("workspace"));
        write(workspace.resolve(".agents/skills/good"), "good", "ok");
        write(workspace.resolve(".agents/skills/bad"), "bad", "---\nname: BAD\ndescription: bad\n---\n");
        write(workspace.resolve(".agents/skills/nested/child"), "child", "nested");

        var catalog = new SkillDiscoveryService(new SkillParser(), temp.resolve("home").toString()).discover(workspace);

        assertEquals(java.util.List.of("good"), catalog.skills().stream().map(SkillDefinition::name).toList());
        assertTrue(catalog.errors().stream().anyMatch(e -> e.path().toString().contains("bad")));
    }

    @Test
    void ignoresDirectoriesWithoutSkillFile() throws Exception {
        Path workspace = Files.createDirectory(temp.resolve("workspace"));
        Files.createDirectories(workspace.resolve(".agents/skills/not-a-skill"));

        var catalog = new SkillDiscoveryService(new SkillParser(), temp.resolve("home").toString()).discover(workspace);

        assertTrue(catalog.skills().isEmpty());
        assertTrue(catalog.errors().isEmpty());
    }

    private static void write(Path directory, String name, String description) throws Exception {
        Files.createDirectories(directory);
        String body = description.startsWith("---") ? description : "---\nname: " + name + "\ndescription: " + description + "\n---\nbody\n";
        Files.writeString(directory.resolve("SKILL.md"), body);
    }
}
