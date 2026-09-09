package com.judepereira.jupiter.command;

import com.judepereira.jupiter.e2e.E2ETestSupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

class CommandCatalogIsolationTest {

    @Test
    void concurrentAppsUseDistinctConfiguredHomesWithoutChangingJvmProperties(@TempDir Path tempDir) throws Exception {
        Path homeA = createHomeWithCommand(tempDir.resolve("home-a"), "from-home-a");
        Path homeB = createHomeWithCommand(tempDir.resolve("home-b"), "from-home-b");
        Path dbA = tempDir.resolve("db-a/jupiter.db");
        Path dbB = tempDir.resolve("db-b/jupiter.db");
        Files.createDirectories(dbA.getParent());
        Files.createDirectories(dbB.getParent());
        String originalHome = System.getProperty("jupiter.user-home");

        E2ETestSupport.RunningApp appA = null;
        E2ETestSupport.RunningApp appB = null;
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            CompletableFuture<E2ETestSupport.RunningApp> first = CompletableFuture.supplyAsync(
                    () -> E2ETestSupport.startApp(homeA, dbA), executor);
            CompletableFuture<E2ETestSupport.RunningApp> second = CompletableFuture.supplyAsync(
                    () -> E2ETestSupport.startApp(homeB, dbB), executor);
            appA = first.join();
            appB = second.join();

            assertThat(appA.context().getBean(CommandCatalogService.class).getRequired("from-home-a")).isNotNull();
            assertThat(appA.context().getBean(CommandCatalogService.class).list())
                    .noneMatch(command -> command.id().equals("from-home-b"));
            assertThat(appB.context().getBean(CommandCatalogService.class).getRequired("from-home-b")).isNotNull();
            assertThat(appB.context().getBean(CommandCatalogService.class).list())
                    .noneMatch(command -> command.id().equals("from-home-a"));
        } finally {
            if (appA != null) {
                appA.close();
            }
            if (appB != null) {
                appB.close();
            }
            assertThat(System.getProperty("jupiter.user-home")).isEqualTo(originalHome);
        }
    }

    private static Path createHomeWithCommand(Path home, String id) throws Exception {
        Path commands = Files.createDirectories(home.resolve(".jupiter/commands"));
        Files.writeString(commands.resolve(id + ".md"), """
                ---
                id: %s
                name: %s
                type: prompt
                ---
                body
                """.formatted(id, id));
        return home;
    }
}
