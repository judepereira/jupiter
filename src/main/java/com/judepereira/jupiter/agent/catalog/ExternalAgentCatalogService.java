package com.judepereira.jupiter.agent.catalog;

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.judepereira.jupiter.command.CommandFrontMatterExtractor;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

/** Discovers compatible external agents without retaining workspace state. */
@Service
public class ExternalAgentCatalogService {
    private static final int MAX_BYTES = 1024 * 1024;
    private static final YAMLMapper YAML = new YAMLMapper();
    private static final Set<String> NATIVE_TOOLS = Set.of("list_files", "read_file", "search_code", "write_file",
            "apply_patch", "display_image", "run_command", "task", "mcp:*");
    private static final Map<String, String> CLAUDE_TOOLS = Map.of("Read", "read_file", "Glob", "list_files", "Grep",
            "search_code", "Write", "write_file", "Edit", "apply_patch", "Bash", "run_command", "Task", "task", "Agent",
            "task");

    private final AgentDefinitionService bundled;
    private final ModelCatalogService models;
    private final Path home;

    public ExternalAgentCatalogService(AgentDefinitionService bundled, ModelCatalogService models,
            @Value("${user.home}") String home) {
        this.bundled = bundled;
        this.models = models;
        this.home = Path.of(home).toAbsolutePath().normalize();
    }

    public AgentCatalogSnapshot snapshot(Path workspace) {
        List<AgentDiagnostic> diagnostics = new ArrayList<>();
        Map<String, Candidate> selected = new LinkedHashMap<>();
        Map<String, AgentCatalogSnapshot.Inheritance> inheritance = new LinkedHashMap<>();
        for (AgentDefinition agent : bundled.list())
            selected.put(agent.id(),
                    new Candidate(agent, new AgentSource(AgentSource.Kind.BUNDLED, AgentSource.Scope.BUNDLED, null)));
        // Lower precedence is loaded first; later vendor/workspace definitions replace
        // it.
        List<Location> locations = new ArrayList<>();
        locations.addAll(locations(home, AgentSource.Scope.HOME));
        if (workspace != null)
            locations.addAll(locations(workspace.toAbsolutePath().normalize(), AgentSource.Scope.WORKSPACE));
        for (Location location : locations) {
            List<CodexRole> codexRoles = location.kind() == AgentSource.Kind.CODEX
                    ? codexRoles(location, diagnostics)
                    : List.of();
            Set<Path> configFiles = codexRoles.stream().map(CodexRole::source).collect(Collectors.toSet());
            for (CodexRole role : codexRoles) {
                register(role.parsed(), new AgentSource(location.kind(), location.scope(), role.source()),
                        location.scope(), selected, inheritance, diagnostics);
            }
            for (Path file : files(location, diagnostics)) {
                if (configFiles.contains(file.toAbsolutePath().normalize()))
                    continue;
                try {
                    register(parse(location, file, read(file)),
                            new AgentSource(location.kind(), location.scope(), file), location.scope(), selected,
                            inheritance, diagnostics);
                } catch (IOException | RuntimeException e) {
                    diagnostics.add(new AgentDiagnostic(AgentDiagnostic.Severity.ERROR, file,
                            e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
                }
            }
        }
        Map<String, AgentSource> sources = selected.entrySet().stream().collect(
                Collectors.toMap(Map.Entry::getKey, e -> e.getValue().source(), (a, b) -> b, LinkedHashMap::new));
        return new AgentCatalogSnapshot(selected.values().stream().map(Candidate::agent).toList(), sources, diagnostics,
                inheritance);
    }

    private List<Location> locations(Path root, AgentSource.Scope scope) {
        return List.of(new Location(root.resolve(".codex/agents"), AgentSource.Kind.CODEX, scope),
                new Location(root.resolve(".claude/agents"), AgentSource.Kind.CLAUDE, scope),
                new Location(root.resolve(".jupiter/agents"), AgentSource.Kind.JUPITER, scope));
    }

    private Set<Path> codexConfigFiles(Location location, List<AgentDiagnostic> diagnostics) {
        if (location.kind() != AgentSource.Kind.CODEX)
            return Set.of();
        Path codex = location.directory().getParent();
        Path config = codex.resolve("config.toml");
        if (Files.notExists(config))
            return Set.of();
        if (!safeCodexPath(codex, config)) {
            diagnostics.add(new AgentDiagnostic(AgentDiagnostic.Severity.ERROR, config,
                    "Codex config must be a safe real file inside .codex"));
            return Set.of();
        }
        return codexRoles(location, diagnostics).stream().map(CodexRole::source).collect(Collectors.toSet());
    }

    private List<CodexRole> codexRoles(Location location, List<AgentDiagnostic> diagnostics) {
        if (location.kind() != AgentSource.Kind.CODEX)
            return List.of();
        Path codex = location.directory().getParent();
        Path config = codex.resolve("config.toml");
        if (Files.notExists(config))
            return List.of();
        List<CodexRole> result = new ArrayList<>();
        if (!safeCodexPath(codex, config)) {
            diagnostics.add(new AgentDiagnostic(AgentDiagnostic.Severity.ERROR, config,
                    "Codex config must be a safe real file inside .codex"));
            return result;
        }
        try {
            TomlParseResult parsed = Toml.parse(read(config));
            if (parsed.hasErrors())
                throw new IllegalArgumentException(parsed.errors().toString());
            TomlTable agents = parsed.getTable("agents");
            if (agents == null)
                return result;
            for (String roleName : agents.keySet()) {
                Path rolePath = config;
                try {
                    Object value = agents.get(roleName);
                    if (!(value instanceof TomlTable role))
                        throw new IllegalArgumentException("Role must be a table");
                    for (String key : role.keySet())
                        if (!key.equals("description") && !key.equals("config_file") && !key.equals("mode"))
                            throw new IllegalArgumentException("Unsupported Codex role control: " + key);
                    String reference = string(role, "config_file", "");
                    if (reference.isBlank())
                        throw new IllegalArgumentException("config_file is required for role " + roleName);
                    rolePath = safeResolve(codex, reference);
                    Parsed roleParsed = parseToml(rolePath, read(rolePath));
                    AgentMode roleMode = role.contains("mode")
                            ? mode(string(role, "mode", null), AgentMode.AGENT)
                            : roleParsed.agent().mode();
                    if (role.contains("mode") && roleParsed.explicitMode() && roleMode != roleParsed.agent().mode())
                        throw new IllegalArgumentException("Conflicting mode between Codex role and config_file");
                    AgentMode effectiveMode = role.contains("mode") ? roleMode : roleParsed.agent().mode();
                    AgentDefinition agent = new AgentDefinition(roleName, roleName,
                            string(role, "description", roleParsed.agent().description()),
                            roleParsed.agent().systemPrompt(), effectiveMode, roleParsed.agent().modelIds(),
                            roleParsed.agent().defaultThinkingLevel(), roleParsed.agent().textVerbosity(),
                            roleParsed.agent().allowWrite(), roleParsed.agent().allowCommand(),
                            roleParsed.agent().allowedTools());
                    result.add(new CodexRole(new Parsed(agent, roleParsed.inheritance(),
                            roleParsed.explicitMode() || role.contains("mode")), rolePath));
                } catch (IOException | IllegalArgumentException e) {
                    diagnostics.add(new AgentDiagnostic(AgentDiagnostic.Severity.ERROR, rolePath, e.getMessage()));
                }
            }
        } catch (IOException | IllegalArgumentException e) {
            diagnostics.add(new AgentDiagnostic(AgentDiagnostic.Severity.ERROR, config, e.getMessage()));
        }
        return result;
    }

    private static Path safeResolve(Path codex, String reference) {
        Path resolved = codex.resolve(reference).normalize();
        if (!safeCodexPath(codex, resolved) || !Files.isRegularFile(resolved))
            throw new IllegalArgumentException("config_file must reference a safe real file inside .codex");
        return resolved;
    }

    private static boolean safeCodexPath(Path codex, Path path) {
        try {
            Path realCodex = codex.toRealPath();
            Path normalized = path.toAbsolutePath().normalize();
            if (!normalized.startsWith(realCodex) || !Files.isRegularFile(normalized))
                return false;
            Path realPath = normalized.toRealPath();
            if (!realPath.startsWith(realCodex))
                return false;
            Path current = realCodex;
            for (Path part : realCodex.relativize(normalized)) {
                current = current.resolve(part);
                if (Files.isSymbolicLink(current))
                    return false;
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static void register(Parsed parsed, AgentSource source, AgentSource.Scope scope,
            Map<String, Candidate> selected, Map<String, AgentCatalogSnapshot.Inheritance> inheritance,
            List<AgentDiagnostic> diagnostics) {
        Candidate prior = selected.get(parsed.agent().id());
        if (prior != null && prior.source().scope() == scope && prior.source().kind() == source.kind()) {
            diagnostics.add(new AgentDiagnostic(AgentDiagnostic.Severity.ERROR, source.path(),
                    "Duplicate agent id from the same source; retained " + prior.source().path()));
            return;
        }
        selected.put(parsed.agent().id(), new Candidate(parsed.agent(), source));
        inheritance.remove(parsed.agent().id());
        if (parsed.inheritance() != AgentCatalogSnapshot.Inheritance.NONE)
            inheritance.put(parsed.agent().id(), parsed.inheritance());
    }

    private List<Path> files(Location location, List<AgentDiagnostic> diagnostics) {
        if (Files.notExists(location.directory()))
            return List.of();
        if (Files.isSymbolicLink(location.directory()) || !Files.isDirectory(location.directory())) {
            diagnostics.add(new AgentDiagnostic(AgentDiagnostic.Severity.ERROR, location.directory(),
                    "Agent directory must be a real directory"));
            return List.of();
        }
        try (var stream = Files.list(location.directory())) {
            List<Path> result = new ArrayList<>();
            stream.sorted().forEach(p -> {
                if (Files.isSymbolicLink(p)) {
                    diagnostics.add(new AgentDiagnostic(AgentDiagnostic.Severity.ERROR, p,
                            "Symbolic-link agent files are not allowed"));
                } else if (Files.isRegularFile(p) && (location.kind() == AgentSource.Kind.CODEX
                        ? p.toString().endsWith(".md") || p.toString().endsWith(".toml")
                        : p.toString().endsWith(".md"))) {
                    result.add(p);
                }
            });
            return result;
        } catch (IOException e) {
            diagnostics.add(new AgentDiagnostic(AgentDiagnostic.Severity.ERROR, location.directory(),
                    "Cannot scan directory: " + e.getMessage()));
            return List.of();
        }
    }

    private Parsed parse(Location location, Path file, String content) throws IOException {
        if (location.kind() == AgentSource.Kind.CODEX && file.toString().endsWith(".toml"))
            return parseToml(file, content);
        if (location.kind() == AgentSource.Kind.CODEX)
            return parseCodexMarkdown(file, content);
        if (location.kind() == AgentSource.Kind.JUPITER) {
            var parsed = AgentDefinitionService.parseContent(content);
            Map<?, ?> data = YAML.readValue(parsed.yaml(), Map.class);
            return jupiter(file, data, parsed.body());
        }
        var extracted = CommandFrontMatterExtractor.extract(content);
        if (extracted.status() != CommandFrontMatterExtractor.Status.COMPLETE)
            throw new IllegalArgumentException("Missing or unterminated YAML frontmatter");
        Map<?, ?> data = YAML.readValue(extracted.yaml(), Map.class);
        return claude(file, data, extracted.body());
    }

    private Parsed jupiter(Path file, Map<?, ?> data, String body) {
        AgentMode mode = mode(data, AgentMode.SUBAGENT);
        if (mode == AgentMode.SUBAGENT && "task".equals(text(data, "tool", "")))
            throw new IllegalArgumentException("task is not allowed for subagent");
        boolean inheritsTools = data.get("tools") == null;
        List<String> tools = nativeTools(data.get("tools"), mode);
        String model = text(data, "model", null);
        if (data.get("tools") instanceof Map<?, ?> map
                && map.values().stream().anyMatch(value -> !(value instanceof Boolean)))
            throw new IllegalArgumentException("Tool map values must be boolean");
        String id = text(data, "id", stem(file));
        if ("plan".equals(id) && mode != AgentMode.AGENT)
            throw new IllegalArgumentException("External plan agent must use mode AGENT");
        return new Parsed(
                definition(id, text(data, "name", display(stem(file))), required(data, "description"), body, mode,
                        primaryModels(model, mode), thinking(text(data, "reasoningEffort", "medium")),
                        text(data, "textVerbosity", null), tools),
                inheritance(model == null || "inherit".equalsIgnoreCase(model), inheritsTools),
                data.containsKey("mode"));
    }

    private Parsed parseCodexMarkdown(Path file, String content) {
        var extracted = CommandFrontMatterExtractor.extract(content);
        if (extracted.status() != CommandFrontMatterExtractor.Status.COMPLETE)
            throw new IllegalArgumentException("Missing or unterminated YAML frontmatter");
        Map<?, ?> data;
        try {
            data = YAML.readValue(extracted.yaml(), Map.class);
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid frontmatter: " + e.getMessage());
        }
        rejectControls(data, Set.of("approval_policy", "network", "sandbox", "mcp_servers", "profiles"));
        String model = text(data, "model", null);
        String effort = text(data, "model_reasoning_effort", "medium");
        String sandbox = text(data, "sandbox_mode", null);
        if ("workspace-write".equals(sandbox))
            throw new IllegalArgumentException("workspace-write sandbox_mode cannot be enforced");
        boolean inheritsTools = data.get("tools") == null;
        AgentMode mode = mode(data, AgentMode.AGENT);
        List<String> tools = data.get("tools") == null
                ? tomlTools(sandbox, mode)
                : nativeTools(data.get("tools"), mode);
        if ("read-only".equals(sandbox))
            tools = tools.stream().filter(t -> Set.of("list_files", "read_file", "search_code").contains(t)).toList();
        return new Parsed(
                definition(stem(file), display(stem(file)), required(data, "description"), extracted.body(), mode,
                        primaryModels(model, mode), thinking(effort), null, tools),
                inheritance(model == null || "inherit".equalsIgnoreCase(model), inheritsTools),
                data.containsKey("mode"));
    }

    private Parsed claude(Path file, Map<?, ?> data, String body) {
        rejectControls(data, Set.of("hooks", "permissionMode", "sandbox", "sandboxMode", "skills", "mcpServers",
                "memory", "background", "isolation", "maxTurns"));
        String id = text(data, "name", stem(file));
        boolean inheritsTools = data.get("tools") == null;
        AgentMode mode = mode(data, AgentMode.AGENT);
        List<String> tools = data.get("tools") == null
                ? defaultClaudeTools(mode)
                : tools(data.get("tools"), mode, false);
        Object denied = data.get("disallowedTools");
        if (denied != null) {
            Set<String> deniedNames = deniedTools(denied);
            tools = tools.stream().filter(t -> !deniedNames.contains(t)).toList();
        }
        String model = text(data, "model", null);
        String effort = text(data, "reasoning_effort", text(data, "reasoningEffort", "medium"));
        return new Parsed(
                definition(id, text(data, "name", display(id)), required(data, "description"), body, mode,
                        primaryModels(model, mode), thinking(effort), text(data, "textVerbosity", null), tools),
                inheritance(model == null || "inherit".equalsIgnoreCase(model), inheritsTools),
                data.containsKey("mode"));
    }

    private static List<String> defaultClaudeTools(AgentMode mode) {
        List<String> tools = new ArrayList<>(List.of("list_files", "read_file", "search_code", "write_file",
                "apply_patch", "display_image", "run_command", "mcp:*"));
        if (mode == AgentMode.AGENT)
            tools.add("task");
        return List.copyOf(tools);
    }

    private List<String> primaryModels(String value, AgentMode mode) {
        List<String> result = models(value);
        if (mode == AgentMode.AGENT && result.isEmpty())
            return bundled.defaultAgent().modelIds();
        return result;
    }

    private Parsed parseToml(Path file, String content) {
        TomlParseResult toml = Toml.parse(content);
        if (toml.hasErrors())
            throw new IllegalArgumentException(toml.errors().toString());
        TomlTable table = toml;
        Set<String> supported = Set.of("name", "description", "developer_instructions", "model",
                "model_reasoning_effort", "sandbox_mode", "tools", "mode");
        for (String key : table.keySet())
            if (!supported.contains(key))
                throw new IllegalArgumentException("Unsupported Codex control: " + key);
        String id = stem(file);
        String name = string(table, "name", display(id));
        boolean inheritsTools = !table.contains("tools");
        String sandbox = string(table, "sandbox_mode", null);
        if ("workspace-write".equals(sandbox))
            throw new IllegalArgumentException("workspace-write sandbox_mode cannot be enforced");
        AgentMode mode = mode(string(table, "mode", null), AgentMode.AGENT);
        List<String> tools = table.contains("tools") ? tools(table.get("tools"), mode, true) : tomlTools(sandbox, mode);
        if ("read-only".equals(sandbox))
            tools = tools.stream().filter(t -> Set.of("list_files", "read_file", "search_code").contains(t)).toList();
        String model = string(table, "model", null);
        return new Parsed(
                definition(id, name, string(table, "description", id), string(table, "developer_instructions", ""),
                        mode, primaryModels(model, mode), thinking(string(table, "model_reasoning_effort", "medium")),
                        null, tools),
                inheritance(model == null || "inherit".equalsIgnoreCase(model), inheritsTools), table.contains("mode"));
    }

    private static AgentCatalogSnapshot.Inheritance inheritance(boolean model, boolean tools) {
        if (model && tools)
            return AgentCatalogSnapshot.Inheritance.MODEL_AND_TOOLS;
        if (model)
            return AgentCatalogSnapshot.Inheritance.MODEL;
        if (tools)
            return AgentCatalogSnapshot.Inheritance.TOOLS;
        return AgentCatalogSnapshot.Inheritance.NONE;
    }

    private List<String> tomlTools(Object sandbox, AgentMode agentMode) {
        String sandboxMode = sandbox == null ? "workspace-write" : sandbox.toString();
        if (sandboxMode.equals("read-only"))
            return List.of("list_files", "read_file", "search_code");
        if (sandboxMode.equals("workspace-write") || sandboxMode.equals("danger-full-access")) {
            List<String> tools = new ArrayList<>(NATIVE_TOOLS);
            if (agentMode == AgentMode.SUBAGENT)
                tools.remove("task");
            return List.copyOf(tools);
        }
        throw new IllegalArgumentException("Unsupported sandbox_mode: " + sandboxMode);
    }

    private AgentDefinition definition(String id, String name, String description, String prompt, AgentMode mode,
            List<String> modelIds, ThinkingLevel thinking, String textVerbosity, List<String> tools) {
        boolean write = tools.contains("write_file") || tools.contains("apply_patch");
        return new AgentDefinition(id, name, description, prompt, mode, modelIds, thinking, textVerbosity, write,
                tools.contains("run_command"), tools);
    }

    private List<String> models(String value) {
        if (value == null || value.isBlank() || value.equalsIgnoreCase("inherit"))
            return List.of();
        List<String> result = new ArrayList<>();
        for (String raw : value.split(",", -1)) {
            String candidate = raw.trim();
            if (candidate.isBlank())
                throw new IllegalArgumentException("Blank model entry");
            String alias = switch (candidate.toLowerCase(Locale.ROOT)) {
                case "opus" -> "claude-opus";
                case "sonnet" -> "claude-sonnet";
                case "haiku" -> "claude-haiku";
                default -> candidate;
            };
            ModelDefinition match = models.list().stream()
                    .filter(m -> m.id().equals(alias) || m.id().endsWith("/" + alias)
                            || m.apiModelId().equalsIgnoreCase(alias) || m.displayName().equalsIgnoreCase(alias)
                            || m.family() != null && m.family().equalsIgnoreCase(alias))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown model: " + candidate));
            result.add(match.id());
        }
        return result.stream().distinct().toList();
    }

    private List<String> nativeTools(Object value, AgentMode mode) {
        if (value instanceof Map<?, ?> map) {
            for (Object flag : map.values())
                if (!(flag instanceof Boolean))
                    throw new IllegalArgumentException("Tool map values must be boolean");
            if (map.keySet().stream().anyMatch(key -> !key.equals("*") && !NATIVE_TOOLS.contains(key.toString())))
                throw new IllegalArgumentException("Unsupported tool in native map");
            if (Boolean.TRUE.equals(map.get("*"))) {
                List<String> expanded = new ArrayList<>(NATIVE_TOOLS);
                map.forEach((key, flag) -> {
                    if (Boolean.FALSE.equals(flag))
                        expanded.remove(key.toString());
                });
                if (mode == AgentMode.SUBAGENT)
                    expanded.remove("task");
                return List.copyOf(expanded);
            }
        }
        return tools(value, mode, true);
    }

    private List<String> tools(Object value, AgentMode mode, boolean jupiter) {
        if (value == null)
            return List.of("list_files", "read_file", "search_code");
        List<?> raw;
        if (value instanceof TomlArray array) {
            raw = array.toList();
        } else if (value instanceof List<?> list) {
            raw = list;
        } else if (value instanceof Map<?, ?> map) {
            for (Object flag : map.values())
                if (!(flag instanceof Boolean))
                    throw new IllegalArgumentException("Tool map values must be boolean");
            raw = map.entrySet().stream().filter(e -> Boolean.TRUE.equals(e.getValue())).map(Map.Entry::getKey)
                    .toList();
        } else {
            raw = List.of(value.toString().split(","));
        }
        List<String> result = new ArrayList<>();
        for (Object item : raw) {
            String name = item.toString().trim();
            if (name.equals("*") || name.equals("mcp:*") || name.equals("task") && mode == AgentMode.SUBAGENT)
                throw new IllegalArgumentException("Unsupported unrestricted or recursive tool: " + name);
            String mapped = jupiter ? name : CLAUDE_TOOLS.getOrDefault(name, name);
            if (!NATIVE_TOOLS.contains(mapped))
                throw new IllegalArgumentException("Unsupported tool: " + name);
            if (!result.contains(mapped))
                result.add(mapped);
        }
        return List.copyOf(result);
    }

    private Set<String> deniedTools(Object denied) {
        String[] values = denied instanceof List<?> list
                ? list.stream().map(Object::toString).toArray(String[]::new)
                : denied.toString().split(",");
        return Arrays.stream(values).map(String::trim).map(name -> {
            String mapped = CLAUDE_TOOLS.get(name);
            if (mapped == null)
                throw new IllegalArgumentException("Unsupported denied tool: " + name);
            return mapped;
        }).collect(Collectors.toSet());
    }

    private static void rejectControls(Map<?, ?> values, Set<String> controls) {
        for (String control : controls)
            if (values.containsKey(control))
                throw new IllegalArgumentException("Unsupported control: " + control);
    }
    private static String read(Path path) throws IOException {
        try (var in = Files.newInputStream(path)) {
            byte[] bytes = in.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES)
                throw new IllegalArgumentException("File exceeds 1 MiB limit");
            try {
                return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            } catch (CharacterCodingException e) {
                throw new IllegalArgumentException("File is not valid UTF-8");
            }
        }
    }
    private static String text(Map<?, ?> map, String key, String fallback) {
        Object v = map.get(key);
        return v == null ? fallback : v.toString();
    }
    private static String required(Map<?, ?> map, String key) {
        String v = text(map, key, "");
        if (v.isBlank())
            throw new IllegalArgumentException(key + " is required");
        return v;
    }
    private static String string(TomlTable t, String key, String fallback) {
        Object v = t.get(key);
        return v == null ? fallback : v.toString();
    }
    private static String stem(Path p) {
        String n = p.getFileName().toString();
        int d = n.lastIndexOf('.');
        return d > 0 ? n.substring(0, d) : n;
    }
    private static String display(String id) {
        return Pattern.compile("[-_]+").splitAsStream(id)
                .map(s -> s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1)).reduce((a, b) -> a + " " + b)
                .orElse(id);
    }
    private static AgentMode mode(Map<?, ?> d, AgentMode fallback) {
        return mode(text(d, "mode", null), fallback);
    }

    private static AgentMode mode(String value, AgentMode fallback) {
        if (value == null || value.isBlank())
            return fallback;
        try {
            return AgentMode.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid mode: " + value);
        }
    }
    private static ThinkingLevel thinking(String v) {
        try {
            return ThinkingLevel.valueOf(v.toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid reasoning effort: " + v);
        }
    }
    private record Location(Path directory, AgentSource.Kind kind, AgentSource.Scope scope) {
    }
    private record Candidate(AgentDefinition agent, AgentSource source) {
    }
    private record Parsed(AgentDefinition agent, AgentCatalogSnapshot.Inheritance inheritance, boolean explicitMode) {
    }
    private record CodexRole(Parsed parsed, Path source) {
    }
}
