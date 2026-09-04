package com.judepereira.jupiter.agent.skill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public final class SkillDiscoveryService {
    private static final Logger log = LoggerFactory.getLogger(SkillDiscoveryService.class);
    private static final String RELATIVE_ROOT = ".agents/skills";
    private final SkillParser parser;
    private final Path userHome;

    public SkillDiscoveryService(SkillParser parser, @Value("${user.home}") String userHome) {
        this.parser = parser;
        this.userHome = Path.of(userHome);
    }

    public SkillCatalog discover(Path workspace) {
        List<SkillLoadError> errors = new ArrayList<>();
        Map<String, SkillDefinition> definitions = new LinkedHashMap<>();
        scan(workspace, SkillScope.REPOSITORY, definitions, errors);
        scan(userHome, SkillScope.USER, definitions, errors);
        List<SkillDefinition> skills = definitions.values().stream().sorted(Comparator.comparing(SkillDefinition::name)).toList();
        errors.forEach(error -> log.warn("Could not load skill {}: {}", error.path(), error.message()));
        return new SkillCatalog(skills, errors);
    }

    private void scan(Path base, SkillScope scope, Map<String, SkillDefinition> definitions, List<SkillLoadError> errors) {
        final Path canonicalBase;
        try {
            canonicalBase = base.toAbsolutePath().normalize().toRealPath();
        } catch (IOException e) {
            if (Files.exists(base, LinkOption.NOFOLLOW_LINKS)) errors.add(new SkillLoadError(base, "unable to access workspace: " + e.getMessage()));
            return;
        }
        Path root = canonicalBase.resolve(RELATIVE_ROOT);
        try {
            Path canonicalRoot = root.toRealPath();
            if (!canonicalRoot.equals(root)) {
                errors.add(new SkillLoadError(root, "skills root must not escape its workspace"));
                return;
            }
        } catch (IOException e) {
            if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) errors.add(new SkillLoadError(root, "unable to access skills root: " + e.getMessage()));
            return;
        }
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return;
        try (var children = Files.list(root)) {
            children.sorted(Comparator.comparing(path -> path.getFileName().toString())).forEach(directory -> {
                String candidate = directory.getFileName().toString();
                if (Files.isSymbolicLink(directory)) {
                    errors.add(new SkillLoadError(directory, "skill directory must not be a symbolic link", candidate));
                    return;
                }
                if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return;
                Path file = directory.resolve("SKILL.md");
                if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return;
                SkillParser.ParseResult result = parser.parse(file, scope);
                result.error().ifPresent(error -> errors.add(error.candidateSkillName() == null
                        ? new SkillLoadError(error.path(), error.message(), candidate) : error));
                result.definition().ifPresent(definition -> {
                    if (scope == SkillScope.REPOSITORY || !definitions.containsKey(definition.name())) definitions.put(definition.name(), definition);
                });
            });
        } catch (IOException e) {
            errors.add(new SkillLoadError(root, "unable to list skills root: " + e.getMessage()));
        }
    }
}
