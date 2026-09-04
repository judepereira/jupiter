package com.judepereira.jupiter.agent.skill;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Pattern;

@Component
public final class SkillParser {
    static final int MAX_BYTES = 256 * 1024;
    private static final Pattern NAME = Pattern.compile("[a-z0-9-]{1,64}");
    private static final YAMLMapper YAML = new YAMLMapper();

    public ParseResult parse(Path skillFile, SkillScope scope) {
        try {
            if (Files.isSymbolicLink(skillFile)) return ParseResult.error(skillFile, "SKILL.md must not be a symbolic link");
            Path lexicalDirectory = skillFile.toAbsolutePath().normalize().getParent();
            if (lexicalDirectory == null || Files.isSymbolicLink(lexicalDirectory)) return ParseResult.error(skillFile, "skill directory must not be a symbolic link");
            if (!Files.isRegularFile(skillFile, LinkOption.NOFOLLOW_LINKS)) return ParseResult.error(skillFile, "SKILL.md is not a regular file");
            Path canonicalFile = skillFile.toRealPath();
            if (!canonicalFile.getParent().equals(lexicalDirectory)) return ParseResult.error(skillFile, "skill directory must not escape skills root");
            if (!canonicalFile.getFileName().toString().equals("SKILL.md")) return ParseResult.error(skillFile, "path must name SKILL.md");
            byte[] bytes;
            try (InputStream input = Files.newInputStream(canonicalFile, LinkOption.NOFOLLOW_LINKS)) {
                bytes = input.readNBytes(MAX_BYTES + 1);
            }
            if (bytes.length > MAX_BYTES) return ParseResult.error(skillFile, "file exceeds 256 KiB");
            String text = decode(bytes);
            String yaml = frontmatter(text);
            JsonNode metadata = YAML.readTree(yaml);
            if (metadata == null || !metadata.isObject()) return ParseResult.error(skillFile, "frontmatter must be a YAML object");
            JsonNode nameNode = metadata.get("name");
            JsonNode descriptionNode = metadata.get("description");
            if (nameNode == null || !nameNode.isTextual() || !NAME.matcher(nameNode.textValue()).matches()) {
                return ParseResult.error(skillFile, "name must match [a-z0-9-]{1,64}");
            }
            Path directory = canonicalFile.getParent();
            if (directory == null || !directory.getFileName().toString().equals(nameNode.textValue())) {
                return ParseResult.error(skillFile, "directory name must equal name");
            }
            if (descriptionNode == null || !descriptionNode.isTextual() || descriptionNode.textValue().isBlank()
                    || descriptionNode.textValue().length() > 1024) {
                return ParseResult.error(skillFile, "description must be nonempty and at most 1024 characters");
            }
            return ParseResult.success(new SkillDefinition(nameNode.textValue(), descriptionNode.textValue(), directory, canonicalFile, scope));
        } catch (CharacterCodingException e) {
            return ParseResult.error(skillFile, "file is not valid UTF-8");
        } catch (JsonProcessingException e) {
            return ParseResult.error(skillFile, "invalid YAML frontmatter: " + e.getOriginalMessage());
        } catch (IOException | RuntimeException e) {
            return ParseResult.error(skillFile, "unable to read skill: " + e.getMessage());
        }
    }

    private static String decode(byte[] bytes) throws CharacterCodingException {
        CharBuffer chars = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes));
        return chars.toString();
    }

    private static String frontmatter(String text) throws IOException {
        if (!(text.startsWith("---\n") || text.startsWith("---\r\n"))) throw new IOException("opening --- is required");
        int start = text.indexOf('\n') + 1;
        int position = start;
        while (position <= text.length()) {
            int end = text.indexOf('\n', position);
            if (end < 0) end = text.length();
            String line = text.substring(position, end);
            if (line.equals("---") || line.equals("---\r")) return text.substring(start, position);
            if (end == text.length()) break;
            position = end + 1;
        }
        throw new IOException("closing --- is required");
    }

    public record ParseResult(Optional<SkillDefinition> definition, Optional<SkillLoadError> error) {
        static ParseResult success(SkillDefinition definition) { return new ParseResult(Optional.of(definition), Optional.empty()); }
        static ParseResult error(Path path, String message) { return new ParseResult(Optional.empty(), Optional.of(new SkillLoadError(path, message))); }
    }
}
