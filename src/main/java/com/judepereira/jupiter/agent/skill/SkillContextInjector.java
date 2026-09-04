package com.judepereira.jupiter.agent.skill;

import com.judepereira.jupiter.agent.llm.dto.Message;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/** Adds ephemeral, explicitly activated skill bodies to a model conversation. */
@Component
public final class SkillContextInjector {
    private final SkillParser parser;

    public SkillContextInjector(SkillParser parser) {
        this.parser = parser;
    }

    public List<Message> injectBeforeNewestUser(List<Message> conversation, SkillInvocationResolver.Resolution resolution) {
        if (resolution == null || (resolution.skills().isEmpty() && resolution.brokenSkills().isEmpty())) return List.copyOf(conversation);
        int index = -1;
        for (int i = conversation.size() - 1; i >= 0; i--) {
            if (conversation.get(i).getRole() == Message.Role.USER) { index = i; break; }
        }
        if (index < 0) return List.copyOf(conversation);
        List<Message> result = new ArrayList<>(conversation);
        List<Message> additions = new ArrayList<>();
        for (SkillDefinition skill : resolution.skills()) {
            try {
                additions.add(new Message(Message.Role.USER, render(skill, load(skill)), null, null));
            } catch (Exception e) {
                additions.add(failure(skill.name()));
            }
        }
        for (String name : resolution.brokenSkills()) additions.add(failure(name));
        result.addAll(index, additions);
        return List.copyOf(result);
    }

    private String load(SkillDefinition skill) throws IOException {
        var file = skill.skillFile();
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("invalid SKILL.md");
        if (!file.toRealPath().equals(file.toAbsolutePath().normalize())) throw new IOException("SKILL.md path changed");
        var current = parser.parse(file, skill.scope()).definition()
                .orElseThrow(() -> new IOException("invalid SKILL.md"));
        if (!sameDefinition(skill, current)) throw new IOException("SKILL.md metadata changed");
        byte[] bytes;
        try (var input = Files.newInputStream(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            bytes = input.readNBytes(SkillParser.MAX_BYTES + 1);
        }
        if (bytes.length > SkillParser.MAX_BYTES) throw new IOException("file exceeds 256 KiB");
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) { throw new IOException("file is not valid UTF-8", e); }
    }

    private static boolean sameDefinition(SkillDefinition expected, SkillDefinition current) {
        return expected.name().equals(current.name())
                && expected.description().equals(current.description())
                && expected.directory().equals(current.directory())
                && expected.skillFile().equals(current.skillFile())
                && expected.scope() == current.scope();
    }

    private static String render(SkillDefinition skill, String body) {
        return "<skill>\n<name>" + escape(skill.name()) + "</name>\n<path>" + escape(skill.skillFile().toAbsolutePath().normalize().toString())
                + "</path>\n" + body + "\n</skill>";
    }

    private static Message failure(String name) {
        return new Message(Message.Role.USER, "The requested skill `" + name + "` could not be loaded: invalid SKILL.md.", null, null);
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }
}
