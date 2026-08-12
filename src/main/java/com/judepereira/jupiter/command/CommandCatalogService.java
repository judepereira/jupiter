package com.judepereira.jupiter.command;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class CommandCatalogService {

    private static final String RESOURCE_PATTERN = "classpath*:commands/*.md";
    private static final YAMLMapper YAML_MAPPER = new YAMLMapper();

    private final List<CommandDefinition> commands;
    private final Map<String, CommandDefinition> commandsById;

    public CommandCatalogService() {
        this.commands = loadCommands();
        this.commandsById = indexCommands(commands);
        if (commandsById.isEmpty()) {
            throw new IllegalStateException("Command catalog is empty");
        }
    }

    public List<CommandDefinition> list() {
        return commands;
    }

    public CommandDefinition getRequired(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Command id is required");
        }
        CommandDefinition command = commandsById.get(normalizeId(id));
        if (command == null) {
            throw new IllegalArgumentException("Unknown command id: " + id);
        }
        return command;
    }

    public CommandDefinition getRequiredScript(String id) {
        CommandDefinition command = getRequired(id);
        if (command.type() != CommandKind.SCRIPT) {
            throw new IllegalStateException("Command is not executable: " + command.id());
        }
        return command;
    }

    private static List<CommandDefinition> loadCommands() {
        try {
            List<CommandDefinition> bundled = loadClasspathCommands();
            List<CommandDefinition> user = loadUserCommands();
            List<CommandDefinition> all = new ArrayList<>(bundled.size() + user.size());
            all.addAll(bundled);
            all.addAll(user);
            validateCommands(all);
            return List.copyOf(all);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load command catalog", e);
        }
    }

    private static List<CommandDefinition> loadClasspathCommands() throws IOException {
        var resolver = new PathMatchingResourcePatternResolver();
        return Arrays.stream(resolver.getResources(RESOURCE_PATTERN))
                .sorted(Comparator.comparing(Resource::getFilename, Comparator.nullsLast(String::compareTo))
                        .thenComparing(CommandCatalogService::resourceSortKey))
                .map(CommandCatalogService::loadCommand)
                .toList();
    }

    private static List<CommandDefinition> loadUserCommands() throws IOException {
        Path root = userCommandsRoot();
        if (!Files.isDirectory(root)) {
            return List.of();
        }

        try (var stream = Files.list(root)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".md"))
                    .sorted(Comparator.comparing((Path path) -> path.getFileName().toString(), Comparator.nullsLast(String::compareTo))
                            .thenComparing(Path::toString))
                    .map(CommandCatalogService::loadCommand)
                    .toList();
        }
    }

    static CommandDefinition loadCommand(Resource resource) {
        try (InputStream inputStream = resource.getInputStream()) {
            String content = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            return loadCommand(resourceSortKey(resource), content);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load command definition from classpath:" + resourceSortKey(resource), e);
        } catch (RuntimeException e) {
            throw new IllegalStateException("Failed to load command definition from classpath:" + resourceSortKey(resource), e);
        }
    }

    static CommandDefinition loadCommand(Path path) {
        try (InputStream inputStream = Files.newInputStream(path)) {
            String content = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            return loadCommand(path.toString(), content);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load command definition from file:" + path, e);
        } catch (RuntimeException e) {
            throw new IllegalStateException("Failed to load command definition from file:" + path, e);
        }
    }

    private static CommandDefinition loadCommand(String sourceKey, String content) {
        var frontMatterAndBody = parseFrontMatter(sourceKey, content);
        try {
            var metadata = YAML_MAPPER.readValue(frontMatterAndBody.yaml(), FrontMatter.class);
            String id = normalizeId(resolveId(sourceKey, metadata.id()));
            String name = resolveName(id, metadata.name());
            validateRequiredFields(metadata, id);
            return new CommandDefinition(
                    id,
                    name,
                    normalizeOptional(metadata.description()),
                    metadata.type(),
                    frontMatterAndBody.body().stripTrailing(),
                    normalizeOptional(metadata.workingDir()),
                    metadata.timeoutSeconds()
            );
        } catch (IOException e) {
            throw new IllegalStateException("Failed to parse command frontmatter in " + sourceKey, e);
        } catch (RuntimeException e) {
            throw new IllegalStateException("Failed to parse command frontmatter in " + sourceKey, e);
        }
    }

    private static FrontMatterAndBody parseFrontMatter(String sourceKey, String content) {
        if (content == null || !content.startsWith("---")) {
            throw new IllegalStateException("Missing YAML frontmatter in " + sourceKey);
        }

        int closing = content.indexOf("\n---", 3);
        int delimiterLength = 4;
        if (closing < 0) {
            closing = content.indexOf("\r\n---", 3);
            delimiterLength = 5;
        }
        if (closing < 0) {
            throw new IllegalStateException("Missing closing YAML frontmatter delimiter in " + sourceKey);
        }

        int yamlStart = 3;
        while (yamlStart < content.length() && (content.charAt(yamlStart) == '\r' || content.charAt(yamlStart) == '\n')) {
            yamlStart++;
        }
        String yaml = content.substring(yamlStart, closing).trim();
        int bodyStart = closing + delimiterLength;
        while (bodyStart < content.length() && (content.charAt(bodyStart) == '\r' || content.charAt(bodyStart) == '\n')) {
            bodyStart++;
        }
        String body = content.substring(bodyStart);
        return new FrontMatterAndBody(yaml, body);
    }

    private static void validateCommands(List<CommandDefinition> commands) {
        if (commands == null || commands.isEmpty()) {
            throw new IllegalStateException("Command catalog is empty");
        }

        Map<String, Boolean> seen = new LinkedHashMap<>();
        for (CommandDefinition command : commands) {
            if (command.id() == null || command.id().isBlank()) {
                throw new IllegalStateException("Command id is required");
            }
            if (seen.put(command.id(), Boolean.TRUE) != null) {
                throw new IllegalStateException("Duplicate command id: " + command.id());
            }
            if (command.name() == null || command.name().isBlank()) {
                throw new IllegalStateException("name is required for command: " + command.id());
            }
            if (command.type() == null) {
                throw new IllegalStateException("type is required for command: " + command.id());
            }
            if (command.body() == null || command.body().isBlank()) {
                throw new IllegalStateException("body is required for command: " + command.id());
            }
            if (command.type() == CommandKind.SCRIPT && command.timeoutSeconds() != null && command.timeoutSeconds() <= 0) {
                throw new IllegalStateException("timeoutSeconds must be greater than zero for command: " + command.id());
            }
        }
    }

    private static Map<String, CommandDefinition> indexCommands(List<CommandDefinition> commands) {
        Map<String, CommandDefinition> indexed = new LinkedHashMap<>();
        for (CommandDefinition command : commands) {
            indexed.put(command.id(), command);
        }
        return Collections.unmodifiableMap(indexed);
    }

    private static String resolveId(String sourceKey, String id) {
        if (id != null && !id.isBlank()) {
            return id;
        }
        int slash = sourceKey.lastIndexOf('/');
        String filename = slash >= 0 ? sourceKey.substring(slash + 1) : sourceKey;
        if (filename.endsWith(".md")) {
            filename = filename.substring(0, filename.length() - 3);
        }
        return filename.replaceFirst("^\\d+-", "");
    }

    private static String normalizeId(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalStateException("Command id is required");
        }
        return id.trim();
    }

    private static String resolveName(String id, String name) {
        if (name != null && !name.isBlank()) {
            return name;
        }
        return idToDisplayName(id);
    }

    private static String idToDisplayName(String id) {
        return Arrays.stream(id.split("[-_]") )
                .filter(part -> !part.isBlank())
                .map(part -> part.substring(0, 1).toUpperCase() + part.substring(1))
                .reduce((left, right) -> left + " " + right)
                .orElse(id);
    }

    private static void validateRequiredFields(FrontMatter metadata, String id) {
        if (metadata.type() == null) {
            throw new IllegalStateException("type is required for command: " + id);
        }
    }

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String resourceSortKey(Resource resource) {
        return resource.getDescription();
    }

    private static Path userCommandsRoot() {
        return Path.of(System.getProperty("user.home")).toAbsolutePath().normalize().resolve(".jupiter").resolve("commands");
    }

    private record FrontMatterAndBody(String yaml, String body) {
    }

    private record FrontMatter(String id, String name, String description, @JsonAlias("kind") @JsonProperty("type") CommandKind type, String workingDir, Integer timeoutSeconds) {
    }

    public record CommandDefinition(String id, String name, String description, @JsonProperty("type") CommandKind type, String body, String workingDir, Integer timeoutSeconds) {
        @JsonProperty("kind")
        public CommandKind kind() {
            return type;
        }
    }

    public enum CommandKind {
        PROMPT,
        SCRIPT;

        @JsonCreator
        public static CommandKind fromValue(String value) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("type is required");
            }
            return switch (value.trim().toLowerCase()) {
                case "prompt" -> PROMPT;
                case "script" -> SCRIPT;
                default -> throw new IllegalArgumentException("Invalid command kind: " + value);
            };
        }
    }
}
