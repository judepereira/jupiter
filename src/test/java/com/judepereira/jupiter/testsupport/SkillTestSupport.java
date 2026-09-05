package com.judepereira.jupiter.testsupport;

import com.judepereira.jupiter.agent.skill.SkillCatalog;
import com.judepereira.jupiter.agent.skill.SkillCatalogRenderer;
import com.judepereira.jupiter.agent.skill.SkillContextInjector;
import com.judepereira.jupiter.agent.skill.SkillDefinition;
import com.judepereira.jupiter.agent.skill.SkillDiscoveryService;
import com.judepereira.jupiter.agent.skill.SkillInvocationResolver;
import com.judepereira.jupiter.agent.skill.SkillParser;
import com.judepereira.jupiter.agent.skill.SkillScope;

import java.nio.file.Path;
import java.util.List;

public final class SkillTestSupport {
    private static final Components DEFAULT_COMPONENTS = components(Path.of("target/test-user-home"));

    private SkillTestSupport() {
    }

    public static Components defaultComponents() {
        return DEFAULT_COMPONENTS;
    }

    public static Components components(Path userHome) {
        var parser = new SkillParser();
        return new Components(parser, new SkillCatalogRenderer(),
                new SkillDiscoveryService(parser, userHome.toString()),
                new SkillInvocationResolver(), new SkillContextInjector(parser));
    }

    public static SkillDefinition skill(String name, String description, Path directory, SkillScope scope) {
        return new SkillDefinition(name, description, directory, directory.resolve("SKILL.md"), scope);
    }

    public static SkillCatalog catalog(SkillDefinition... skills) {
        return new SkillCatalog(List.of(skills), List.of());
    }

    public record Components(SkillParser parser, SkillCatalogRenderer renderer,
                             SkillDiscoveryService discovery, SkillInvocationResolver resolver,
                             SkillContextInjector injector) {
    }
}
