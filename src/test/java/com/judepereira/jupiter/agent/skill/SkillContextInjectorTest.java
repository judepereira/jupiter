package com.judepereira.jupiter.agent.skill;

import com.judepereira.jupiter.agent.llm.dto.Message;
import com.judepereira.jupiter.testsupport.SkillTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SkillContextInjectorTest {
    @TempDir Path temp;

    @Test
    void doesNotReadExternalBodyWhenSkillFileIsReplacedWithSymlink() throws Exception {
        Path workspace = Files.createDirectory(temp.resolve("workspace"));
        Path external = Files.writeString(temp.resolve("external.md"), "EXTERNAL SECRET BODY");
        Path skillFile = writeSkill(workspace, "deploy", "SAFE BODY");
        var skills = SkillTestSupport.components(temp.resolve("home"));
        SkillDefinition discovered = SkillTestSupport.components(temp.resolve("home")).discovery()
                .discover(workspace).skills().getFirst();

        Files.delete(skillFile);
        Files.createSymbolicLink(skillFile, external);

        List<Message> injected = skills.injector().injectBeforeNewestUser(
                List.of(new Message(Message.Role.USER, "$deploy", null, null)),
                skills.resolver().resolveExplicit("$deploy", new SkillCatalog(List.of(discovered), List.of())));

        assertThat(injected).anySatisfy(message -> assertThat(message.getContent())
                .contains("could not be loaded").doesNotContain("EXTERNAL SECRET BODY"));
        assertThat(injected).noneSatisfy(message -> assertThat(message.getContent()).contains("<name>deploy</name>"));
    }

    @Test
    void rejectsRegularFileReplacementWhenMetadataChanges() throws Exception {
        Path workspace = Files.createDirectory(temp.resolve("workspace"));
        Path skillFile = writeSkill(workspace, "deploy", "SAFE BODY");
        var skills = SkillTestSupport.components(temp.resolve("home"));
        SkillDefinition discovered = SkillTestSupport.components(temp.resolve("home")).discovery()
                .discover(workspace).skills().getFirst();

        Files.writeString(skillFile, "---\nname: deploy\ndescription: changed description\n---\nREPLACED SECRET BODY");

        List<Message> injected = skills.injector().injectBeforeNewestUser(
                List.of(new Message(Message.Role.USER, "$deploy", null, null)),
                skills.resolver().resolveExplicit("$deploy", new SkillCatalog(List.of(discovered), List.of())));

        assertThat(injected).anySatisfy(message -> assertThat(message.getContent())
                .contains("could not be loaded: invalid SKILL.md")
                .doesNotContain("REPLACED SECRET BODY"));
        assertThat(injected).noneSatisfy(message -> assertThat(message.getContent()).contains("<name>deploy</name>"));
    }

    @Test
    void doesNotReadExternalBodyWhenContainingDirectoryIsReplacedWithSymlink() throws Exception {
        Path workspace = Files.createDirectory(temp.resolve("workspace"));
        Path external = Files.createDirectory(temp.resolve("external-deploy"));
        Files.writeString(external.resolve("SKILL.md"), "EXTERNAL SECRET BODY");
        Path skillDirectory = workspace.resolve(".agents/skills/deploy");
        writeSkill(workspace, "deploy", "SAFE BODY");
        var skills = SkillTestSupport.components(temp.resolve("home"));
        SkillDefinition discovered = SkillTestSupport.components(temp.resolve("home")).discovery()
                .discover(workspace).skills().getFirst();

        deleteRecursively(skillDirectory);
        Files.createSymbolicLink(skillDirectory, external);

        List<Message> injected = skills.injector().injectBeforeNewestUser(
                List.of(new Message(Message.Role.USER, "$deploy", null, null)),
                skills.resolver().resolveExplicit("$deploy", new SkillCatalog(List.of(discovered), List.of())));

        assertThat(injected).anySatisfy(message -> assertThat(message.getContent())
                .contains("could not be loaded").doesNotContain("EXTERNAL SECRET BODY"));
    }

    private static Path writeSkill(Path workspace, String name, String body) throws Exception {
        Path directory = workspace.resolve(".agents/skills").resolve(name);
        Files.createDirectories(directory);
        return Files.writeString(directory.resolve("SKILL.md"),
                "---\nname: " + name + "\ndescription: deploy skill\n---\n" + body);
    }

    private static void deleteRecursively(Path path) throws Exception {
        Files.delete(path.resolve("SKILL.md"));
        Files.delete(path);
    }
}
