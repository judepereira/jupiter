package com.judepereira.jupiter.command;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

@Service
public class CommandCatalogService {
    private static final Logger log = LoggerFactory.getLogger(CommandCatalogService.class);
    private static final String RESOURCE_PATTERN = "classpath*:commands/*.md";
    private static final YAMLMapper YAML_MAPPER = new YAMLMapper();
    private static final Pattern SAFE_ID = Pattern.compile("[a-z0-9][a-z0-9_-]*");
    private static final int MAX_EXTERNAL_BYTES = 256 * 1024;

    private final Path userCommandsRoot;
    private final Path userHome;
    private final ThreadLocal<Path> activeWorkspace = new ThreadLocal<>();
    private final AtomicReference<CatalogSnapshot> snapshot;
    private final Object mutationLock = new Object();

    public CommandCatalogService(@Value("${jupiter.commands-root:}") String configuredRoot,
            @Value("${user.home}") String userHome) {
        this.userHome = Path.of(userHome).toAbsolutePath().normalize();
        userCommandsRoot = configuredRoot == null || configuredRoot.isBlank()
                ? this.userHome.resolve(".jupiter").resolve("commands")
                : Path.of(configuredRoot).toAbsolutePath().normalize();
        snapshot = new AtomicReference<>(loadSnapshot());
        if (snapshot.get().commands().isEmpty()) {
            throw new IllegalStateException("Command catalog is empty");
        }
    }

    public List<CommandDefinition> list() {
        return catalogFor(activeWorkspace.get()).commands();
    }

    public List<CommandDefinition> list(Path workspace) {
        return catalogFor(workspace).commands();
    }

    public <T> T withWorkspace(Path workspace, Supplier<T> action) {
        Path previous = activeWorkspace.get();
        activeWorkspace.set(workspace);
        try {
            return action.get();
        } finally {
            if (previous == null)
                activeWorkspace.remove();
            else
                activeWorkspace.set(previous);
        }
    }

    private CatalogSnapshot catalogFor(Path workspace) {
        if (workspace == null)
            return snapshot.get();
        return loadSnapshot(workspace);
    }

    public List<CommandDefinition> listCustom() {
        return snapshot.get().custom().stream().map(CustomEntry::definition).toList();
    }

    public CommandDefinition getRequired(String id) {
        String normalizedId = normalize(id);
        CommandDefinition command = snapshot.get().byId().get(normalizedId);
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

    public CommandDefinition create(CommandDefinition input) {
        synchronized (mutationLock) {
            CommandDefinition definition = normalizeAndValidateInput(input);
            CatalogSnapshot before = snapshot.get();
            if (catalogFor(activeWorkspace.get()).byId().containsKey(definition.id())) {
                throw userError("Command id already exists: " + definition.id());
            }
            Path target = pathForId(definition.id());
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                throw userError("Command file already exists: " + target.getFileName());
            }
            replaceAndReload(target, definition, before);
            return definition;
        }
    }

    public CommandDefinition update(String originalId, CommandDefinition input) {
        synchronized (mutationLock) {
            String sourceId = normalize(originalId);
            CommandDefinition definition = normalizeAndValidateInput(input);
            CatalogSnapshot before = snapshot.get();
            CustomEntry source = before.customById().get(sourceId);
            if (source == null) {
                throw userError("Only custom commands can be updated: " + originalId);
            }
            CommandDefinition collision = before.byId().get(definition.id());
            if (collision != null && !definition.id().equals(sourceId)) {
                throw userError("Command id already exists: " + definition.id());
            }
            Path destination = pathForId(definition.id());
            if (!source.path().equals(destination) && Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                throw userError("Command file already exists: " + destination.getFileName());
            }
            if (source.path().equals(destination)) {
                replaceAndReload(destination, definition, before);
            } else {
                renameAndReload(source.path(), destination, definition, before);
            }
            return definition;
        }
    }

    public void delete(String id) {
        synchronized (mutationLock) {
            String normalizedId = normalize(id);
            CatalogSnapshot before = snapshot.get();
            CustomEntry entry = before.customById().get(normalizedId);
            if (entry == null) {
                throw userError("Only custom commands can be deleted: " + id);
            }
            try {
                byte[] previous = Files.readAllBytes(entry.path());
                Files.delete(entry.path());
                try {
                    publishReloaded();
                } catch (RuntimeException failure) {
                    restore(entry.path(), previous, failure);
                    snapshot.set(before);
                    throw failure;
                }
            } catch (IOException failure) {
                throw new CommandMutationException("Failed to delete command: " + id, failure);
            }
        }
    }

    public void reload() {
        synchronized (mutationLock) {
            publishReloaded();
        }
    }

    private void replaceAndReload(Path target, CommandDefinition definition, CatalogSnapshot before) {
        byte[] previous = null;
        Path temporary = null;
        try {
            Files.createDirectories(userCommandsRoot);
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                previous = Files.readAllBytes(target);
            }
            temporary = Files.createTempFile(userCommandsRoot, ".command-", ".tmp");
            Files.writeString(temporary, serialize(definition), StandardCharsets.UTF_8);
            moveAtomically(temporary, target);
            temporary = null;
            try {
                publishReloaded();
            } catch (RuntimeException failure) {
                restore(target, previous, failure);
                snapshot.set(before);
                throw failure;
            }
        } catch (IOException failure) {
            throw new CommandMutationException("Failed to persist command: " + definition.id(), failure);
        } finally {
            deleteTemporary(temporary);
        }
    }

    private void renameAndReload(Path source, Path destination, CommandDefinition definition, CatalogSnapshot before) {
        Path temporary = null;
        try {
            byte[] previous = Files.readAllBytes(source);
            Files.createDirectories(userCommandsRoot);
            temporary = Files.createTempFile(userCommandsRoot, ".command-", ".tmp");
            Files.writeString(temporary, serialize(definition), StandardCharsets.UTF_8);
            moveAtomically(temporary, destination);
            temporary = null;
            Files.delete(source);
            try {
                publishReloaded();
            } catch (RuntimeException failure) {
                restore(source, previous, failure);
                Files.deleteIfExists(destination);
                snapshot.set(before);
                throw failure;
            }
        } catch (IOException failure) {
            try {
                Files.deleteIfExists(destination);
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw new CommandMutationException("Failed to persist command: " + definition.id(), failure);
        } finally {
            deleteTemporary(temporary);
        }
    }

    private void restore(Path path, byte[] content, RuntimeException failure) {
        try {
            if (content == null) {
                Files.deleteIfExists(path);
            } else {
                Files.createDirectories(userCommandsRoot);
                Path temporary = Files.createTempFile(userCommandsRoot, ".command-rollback-", ".tmp");
                try {
                    Files.write(temporary, content);
                    moveAtomically(temporary, path);
                } finally {
                    deleteTemporary(temporary);
                }
            }
        } catch (IOException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }

    private static void deleteTemporary(Path temporary) {
        if (temporary == null) {
            return;
        }
        try {
            Files.deleteIfExists(temporary);
        } catch (IOException ignored) {
            // The original failure is more useful to callers than cleanup noise.
        }
    }

    private Path pathForId(String id) {
        Path path = userCommandsRoot.resolve(id + ".md").normalize();
        if (!path.getParent().equals(userCommandsRoot) || !path.startsWith(userCommandsRoot)) {
            throw userError("Command path is outside the custom command root");
        }
        return path;
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void publishReloaded() {
        snapshot.set(loadSnapshot());
    }

    private CatalogSnapshot loadSnapshot() {
        return loadSnapshot(null);
    }

    private CatalogSnapshot loadSnapshot(Path workspace) {
        try {
            List<CommandDefinition> bundled = loadClasspathCommands();
            List<CustomEntry> custom = loadUserCommands();
            List<CommandDefinition> all = new ArrayList<>(bundled);
            custom.stream().map(CustomEntry::definition).forEach(all::add);
            loadExternalCommands(workspace, all);
            validateCommands(all);
            Map<String, CommandDefinition> byId = new LinkedHashMap<>();
            all.forEach(command -> byId.put(command.id(), command));
            Map<String, CustomEntry> customById = new LinkedHashMap<>();
            custom.forEach(entry -> customById.put(entry.definition().id(), entry));
            return new CatalogSnapshot(List.copyOf(all), Collections.unmodifiableMap(byId), List.copyOf(custom),
                    Collections.unmodifiableMap(customById));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load command catalog", e);
        }
    }

    private List<CommandDefinition> loadClasspathCommands() throws IOException {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        return Arrays.stream(resolver.getResources(RESOURCE_PATTERN))
                .sorted(Comparator.comparing(Resource::getFilename, Comparator.nullsLast(String::compareTo))
                        .thenComparing(CommandCatalogService::resourceSortKey))
                .map(CommandCatalogService::loadCommand).toList();
    }

    private void loadExternalCommands(Path workspace, List<CommandDefinition> all) {
        Set<String> ids = new HashSet<>();
        all.forEach(command -> ids.add(command.id()));
        for (String provider : List.of("claude", "codex")) {
            Path project = workspace == null
                    ? null
                    : workspace.toAbsolutePath().normalize().resolve("." + provider)
                            .resolve(provider.equals("claude") ? "commands" : "prompts");
            Path home = userHome.resolve("." + provider).resolve(provider.equals("claude") ? "commands" : "prompts");
            scanExternalRoot(project, provider, false, ids, all);
            scanExternalRoot(home, provider, true, ids, all);
        }
    }

    private void scanExternalRoot(Path root, String provider, boolean home, Set<String> ids,
            List<CommandDefinition> all) {
        if (root == null || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS))
            return;
        try {
            Path canonicalRoot = root.toRealPath();
            if (!canonicalRoot.equals(root.toAbsolutePath().normalize()))
                return;
            try (var paths = Files.walk(root)) {
                paths.filter(path -> path.toString().endsWith(".md")).sorted().forEach(path -> {
                    try {
                        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                            return;
                        Path real = path.toRealPath();
                        if (!real.startsWith(canonicalRoot) || real.getParent() == null)
                            return;
                        String relative = canonicalRoot.relativize(real).toString().replace('\\', '/');
                        String relativeKey = relative.substring(0, relative.length() - 3);
                        String id = externalId(provider, relativeKey);
                        if (ids.contains(id))
                            return;
                        CommandDefinition definition = loadExternal(real, id, provider, home, relativeKey);
                        if (definition != null) {
                            all.add(definition);
                            ids.add(id);
                        }
                    } catch (RuntimeException | IOException e) {
                        log.warn("Could not load external command {}: {}", path, e.getMessage());
                    }
                });
            }
        } catch (IOException e) {
            log.warn("Could not scan external command root {}: {}", root, e.getMessage());
        }
    }

    private static String externalId(String provider, String relativeKey) {
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(relativeKey.getBytes(StandardCharsets.UTF_8));
        return "external-" + provider + "-" + encoded;
    }

    private static CommandDefinition loadExternal(Path path, String id, String provider, boolean home,
            String relativeKey) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        if (bytes.length > MAX_EXTERNAL_BYTES)
            throw new IOException("file exceeds 256 KiB");
        String content = decodeUtf8(bytes);
        FrontMatterAndBody parsed = externalFrontMatter(content);
        JsonNode metadata = parsed.yaml().isBlank()
                ? YAML_MAPPER.createObjectNode()
                : YAML_MAPPER.readTree(parsed.yaml());
        String name = metadata != null && metadata.get("name") != null && metadata.get("name").isTextual()
                && !metadata.get("name").asText().isBlank()
                        ? metadata.get("name").asText()
                        : idToDisplayName(relativeKey);
        String description = metadata != null && metadata.get("description") != null
                && metadata.get("description").isTextual() ? metadata.get("description").asText() : null;
        if (parsed.body().isBlank())
            return null;
        return new CommandDefinition(id, name, normalizeOptional(description), CommandKind.PROMPT,
                parsed.body().stripTrailing(), null, null, provider, home ? "home" : "project", path.toString(), false);
    }

    private static String decodeUtf8(byte[] bytes) throws CharacterCodingException {
        CharBuffer chars = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes));
        return chars.toString();
    }

    private static FrontMatterAndBody externalFrontMatter(String content) {
        if (!(content.startsWith("---\n") || content.startsWith("---\r\n")))
            return new FrontMatterAndBody("", content);
        int start = content.indexOf('\n') + 1;
        int end = findClosingDelimiter(content, start);
        if (end < 0)
            return new FrontMatterAndBody("", content);
        int body = end + 4;
        while (body < content.length() && (content.charAt(body) == '\n' || content.charAt(body) == '\r'))
            body++;
        return new FrontMatterAndBody(content.substring(start, end).trim(), content.substring(body));
    }

    private static int findClosingDelimiter(String content, int start) {
        int candidate = content.indexOf("\n---", start);
        while (candidate >= 0) {
            int afterDelimiter = candidate + 4;
            if (afterDelimiter == content.length() || content.charAt(afterDelimiter) == '\n'
                    || content.charAt(afterDelimiter) == '\r')
                return candidate;
            candidate = content.indexOf("\n---", afterDelimiter);
        }
        return -1;
    }

    private List<CustomEntry> loadUserCommands() throws IOException {
        if (!Files.isDirectory(userCommandsRoot, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        Path realRoot = userCommandsRoot.toRealPath();
        try (var stream = Files.list(userCommandsRoot)) {
            return stream.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> path.getFileName().toString().endsWith(".md"))
                    .map(path -> checkedCustomPath(path, realRoot))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .map(path -> new CustomEntry(path, loadCommand(path))).toList();
        }
    }

    private static Path checkedCustomPath(Path path, Path realRoot) {
        try {
            if (!path.toRealPath().startsWith(realRoot)) {
                throw new IllegalStateException("Custom command path is outside the custom command root: " + path);
            }
            return path;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to resolve custom command path: " + path, e);
        }
    }

    static CommandDefinition loadCommand(Resource resource) {
        try (InputStream in = resource.getInputStream()) {
            return loadCommand(resourceSortKey(resource), new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to load command definition from classpath:" + resourceSortKey(resource), e);
        }
    }

    static CommandDefinition loadCommand(Path path) {
        try {
            return loadCommand(path.toString(), Files.readString(path));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load command definition from file:" + path, e);
        }
    }

    private static CommandDefinition loadCommand(String source, String content) {
        FrontMatterAndBody parsed = parseFrontMatter(source, content);
        try {
            FrontMatter frontMatter = YAML_MAPPER.readValue(parsed.yaml(), FrontMatter.class);
            String id = normalize(resolveId(source, frontMatter.id()));
            if (frontMatter.type() == null) {
                throw new IllegalStateException("type is required for command: " + id);
            }
            return new CommandDefinition(id,
                    frontMatter.name() == null || frontMatter.name().isBlank()
                            ? idToDisplayName(id)
                            : frontMatter.name().trim(),
                    normalizeOptional(frontMatter.description()), frontMatter.type(), parsed.body().stripTrailing(),
                    normalizeOptional(frontMatter.workingDir()), frontMatter.timeoutSeconds(), null, null, null, true);
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("Failed to parse command frontmatter in " + source, e);
        }
    }

    private static FrontMatterAndBody parseFrontMatter(String source, String content) {
        if (content == null || !content.startsWith("---")) {
            throw new IllegalStateException("Missing YAML frontmatter in " + source);
        }
        int closing = content.indexOf("\n---", 3);
        int delimiterLength = 4;
        if (closing < 0) {
            closing = content.indexOf("\r\n---", 3);
            delimiterLength = 5;
        }
        if (closing < 0) {
            throw new IllegalStateException("Missing closing YAML frontmatter delimiter in " + source);
        }
        int yamlStart = 3;
        while (yamlStart < content.length() && (content.charAt(yamlStart) == '\r' || content.charAt(yamlStart) == '\n'))
            yamlStart++;
        int bodyStart = closing + delimiterLength;
        while (bodyStart < content.length() && (content.charAt(bodyStart) == '\r' || content.charAt(bodyStart) == '\n'))
            bodyStart++;
        return new FrontMatterAndBody(content.substring(yamlStart, closing).trim(), content.substring(bodyStart));
    }

    private static void validateCommands(List<CommandDefinition> commands) {
        if (commands.isEmpty())
            throw new IllegalStateException("Command catalog is empty");
        Set<String> ids = new HashSet<>();
        for (CommandDefinition command : commands) {
            if (command.id() == null || command.id().isBlank() || !ids.add(command.id()))
                throw new IllegalStateException("Duplicate command id: " + command.id());
            if (command.name() == null || command.name().isBlank())
                throw new IllegalStateException("name is required for command: " + command.id());
            if (command.type() == null)
                throw new IllegalStateException("type is required for command: " + command.id());
            if (command.body() == null || command.body().isBlank())
                throw new IllegalStateException("body is required for command: " + command.id());
            if (command.type() == CommandKind.SCRIPT && command.timeoutSeconds() != null
                    && command.timeoutSeconds() <= 0)
                throw new IllegalStateException(
                        "timeoutSeconds must be greater than zero for command: " + command.id());
        }
    }

    private static String serialize(CommandDefinition command) {
        try {
            String frontMatter = YAML_MAPPER.writeValueAsString(new FrontMatter(command.id(), command.name(),
                    command.description(), command.type(), command.workingDir(), command.timeoutSeconds()));
            if (frontMatter.startsWith("---\n")) {
                frontMatter = frontMatter.substring(4);
            }
            return "---\n" + frontMatter + "---\n\n" + command.body().stripTrailing() + "\n";
        } catch (IOException e) {
            throw new CommandMutationException("Failed to serialize command: " + command.id(), e);
        }
    }

    private static CommandDefinition normalizeAndValidateInput(CommandDefinition input) {
        if (input == null)
            throw userError("Command definition is required");
        String id = normalize(input.id());
        if (!SAFE_ID.matcher(id).matches())
            throw userError("Invalid command id: " + input.id());
        String name = input.name() == null ? null : input.name().trim();
        String body = input.body() == null ? null : input.body().stripTrailing();
        if (name == null || name.isBlank())
            throw userError("Command name is required");
        if (input.type() == null)
            throw userError("Command type is required");
        if (body == null || body.isBlank())
            throw userError("Command body is required");
        if (input.type() == CommandKind.SCRIPT && input.timeoutSeconds() != null && input.timeoutSeconds() <= 0)
            throw userError("timeoutSeconds must be greater than zero");
        return new CommandDefinition(id, name, normalizeOptional(input.description()), input.type(), body,
                normalizeOptional(input.workingDir()), input.timeoutSeconds());
    }

    private static CommandMutationException userError(String message) {
        return new CommandMutationException(message);
    }
    private static String normalize(String id) {
        if (id == null || id.isBlank())
            throw userError("Command id is required");
        return id.trim();
    }
    private static String resolveId(String source, String id) {
        if (id != null && !id.isBlank())
            return id;
        String filename = source.substring(source.lastIndexOf('/') + 1);
        return filename.endsWith(".md")
                ? filename.substring(0, filename.length() - 3).replaceFirst("^\\d+-", "")
                : filename;
    }
    private static String idToDisplayName(String id) {
        return Arrays.stream(id.split("[-_]")).filter(part -> !part.isBlank())
                .map(part -> Character.toUpperCase(part.charAt(0)) + part.substring(1)).reduce((a, b) -> a + " " + b)
                .orElse(id);
    }
    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
    private static String resourceSortKey(Resource resource) {
        return resource.getDescription();
    }

    private record FrontMatterAndBody(String yaml, String body) {
    }
    private record FrontMatter(String id, String name, String description,
            @JsonAlias("kind") @JsonProperty("type") CommandKind type, String workingDir, Integer timeoutSeconds) {
    }
    private record CustomEntry(Path path, CommandDefinition definition) {
    }
    private record CatalogSnapshot(List<CommandDefinition> commands, Map<String, CommandDefinition> byId,
            List<CustomEntry> custom, Map<String, CustomEntry> customById) {
    }

    public static class CommandMutationException extends RuntimeException {
        public CommandMutationException(String message) {
            super(message);
        }
        public CommandMutationException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public record CommandDefinition(String id, String name, String description, @JsonProperty("type") CommandKind type,
            String body, String workingDir, Integer timeoutSeconds, String provider, String scope, String origin,
            boolean editable) {
        public CommandDefinition(String id, String name, String description, CommandKind type, String body,
                String workingDir, Integer timeoutSeconds) {
            this(id, name, description, type, body, workingDir, timeoutSeconds, null, null, null, true);
        }

        @JsonProperty("kind")
        public CommandKind kind() {
            return type;
        }
    }

    public enum CommandKind {
        PROMPT, SCRIPT;

        @JsonCreator
        public static CommandKind fromValue(String value) {
            if (value == null || value.isBlank())
                throw new IllegalArgumentException("type is required");
            return switch (value.trim().toLowerCase()) {
                case "prompt" -> PROMPT;
                case "script" -> SCRIPT;
                default -> throw new IllegalArgumentException("Invalid command kind: " + value);
            };
        }
    }
}
