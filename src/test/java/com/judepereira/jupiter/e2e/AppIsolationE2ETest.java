package com.judepereira.jupiter.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.judepereira.jupiter.agent.skill.SkillDiscoveryService;
import com.judepereira.jupiter.agent.skill.SkillScope;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AppIsolationE2ETest {

    @Test
    void concurrentlyStartedAppsKeepTheirInjectedHomesIsolated(@TempDir Path tempDir) throws Exception {
        Path firstHome = createHomeWithSkill(tempDir.resolve("first-home"), "first");
        Path secondHome = createHomeWithSkill(tempDir.resolve("second-home"), "second");
        Path firstWorkspace = Files.createDirectories(tempDir.resolve("first-workspace"));
        Path secondWorkspace = Files.createDirectories(tempDir.resolve("second-workspace"));
        Path firstDb = tempDir.resolve("first.sqlite");
        Path secondDb = tempDir.resolve("second.sqlite");

        CompletableFuture<E2ETestSupport.RunningApp> first = CompletableFuture
                .supplyAsync(() -> startApp(firstHome, firstWorkspace, firstDb));
        CompletableFuture<E2ETestSupport.RunningApp> second = CompletableFuture
                .supplyAsync(() -> startApp(secondHome, secondWorkspace, secondDb));
        try (E2ETestSupport.RunningApp firstApp = first.get(); E2ETestSupport.RunningApp secondApp = second.get()) {
            assertThat(firstApp.context().getBean(SkillDiscoveryService.class).discover(firstWorkspace).skills())
                    .extracting(skill -> skill.name()).containsExactly("first");
            assertThat(firstApp.context().getBean(SkillDiscoveryService.class).discover(firstWorkspace).skills())
                    .allMatch(skill -> skill.scope() == SkillScope.USER);
            assertThat(secondApp.context().getBean(SkillDiscoveryService.class).discover(secondWorkspace).skills())
                    .extracting(skill -> skill.name()).containsExactly("second");
            assertThat(secondApp.context().getBean(SkillDiscoveryService.class).discover(secondWorkspace).skills())
                    .allMatch(skill -> skill.scope() == SkillScope.USER);
        }
    }

    private static E2ETestSupport.RunningApp startApp(Path home, Path workspace, Path db) {
        return E2ETestSupport.startApp(home, db,
                Map.of("agent.workspace-root", workspace.toAbsolutePath().normalize().toString()));
    }

    private static Path createHomeWithSkill(Path home, String name) {
        try {
            Path skill = Files.createDirectories(home.resolve(".agents/skills").resolve(name));
            Files.writeString(skill.resolve("SKILL.md"),
                    "---\nname: " + name + "\ndescription: test\n---\n\nTest skill.\n");
            return home;
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to create test home", exception);
        }
    }
}
