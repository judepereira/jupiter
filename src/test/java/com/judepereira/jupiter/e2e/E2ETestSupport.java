package com.judepereira.jupiter.e2e;

import com.judepereira.jupiter.Jupiter;
import com.judepereira.jupiter.testsupport.SQLiteTestSupport;
import com.microsoft.playwright.Page;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import javax.sql.DataSource;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

abstract class E2ETestSupport {

    protected static RunningApp startApp(Path fakeHome, Path dbFile, Class<?>... testConfigClasses) {
        return startApp(fakeHome, dbFile, Map.of(), testConfigClasses);
    }

    protected static RunningApp startApp(Path fakeHome, Path dbFile, int port, Class<?>... testConfigClasses) {
        return startApp(fakeHome, dbFile, Map.of("server.port", Integer.toString(port)), testConfigClasses);
    }

    protected static RunningApp startApp(Path fakeHome, Path dbFile, Map<String, String> additionalProperties, Class<?>... testConfigClasses) {
        String jdbcUrl = "jdbc:sqlite:file:" + dbFile.toAbsolutePath().normalize() + "?journal_mode=WAL&foreign_keys=on";
        Map<String, String> previousProperties = new HashMap<>();
        overrideSystemProperty(previousProperties, "spring.datasource.url", jdbcUrl);
        additionalProperties.forEach((key, value) -> overrideSystemProperty(previousProperties, key, value));
        Class<?>[] sources = Stream.concat(Stream.of(Jupiter.class), Arrays.stream(testConfigClasses)).toArray(Class<?>[]::new);
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("server.port", "0");
        properties.put("spring.datasource.url", jdbcUrl);
        properties.put("spring.datasource.driver-class-name", "org.sqlite.JDBC");
        properties.put("spring.flyway.enabled", "true");
        properties.put("agent.workspace-root", fakeHome.toAbsolutePath().normalize().toString());
        properties.put("openai.api-key", "test");
        properties.putAll(additionalProperties);
        ConfigurableApplicationContext context = new SpringApplicationBuilder(sources)
                .web(WebApplicationType.SERVLET)
                .properties(properties.entrySet().stream().map(entry -> entry.getKey() + "=" + entry.getValue()).toArray(String[]::new))
                .run();

        Integer port = context.getEnvironment().getProperty("local.server.port", Integer.class);
        if (port == null) {
            throw new IllegalStateException("Missing local.server.port");
        }
        SQLiteTestSupport.assertWalAndForeignKeysEnabled(context.getBean(DataSource.class));
        return new RunningApp(context, "http://localhost:" + port, () -> restoreSystemProperties(previousProperties));
    }

    private static void overrideSystemProperty(Map<String, String> previousProperties, String key, String value) {
        previousProperties.put(key, System.getProperty(key));
        System.setProperty(key, value);
    }

    private static void restoreSystemProperties(Map<String, String> previousProperties) {
        previousProperties.forEach((key, value) -> {
            if (value == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, value);
            }
        });
    }

    protected static void captureScreenshot(Page page, Path screenshotsDir, String fileName) {
        page.screenshot(new Page.ScreenshotOptions()
                .setPath(screenshotsDir.resolve(fileName))
                .setFullPage(true));
    }

    protected static void initGitRepoWithInitialCommit(Path repoDir) throws Exception {
        Files.createDirectories(repoDir);
        runGit(repoDir, "git", "init");
        runGit(repoDir, "git", "config", "user.name", "Jupiter Tests");
        runGit(repoDir, "git", "config", "user.email", "tests@example.com");
        Files.writeString(repoDir.resolve("README.md"), "hello\n");
        runGit(repoDir, "git", "add", "README.md");
        runGit(repoDir, "git", "commit", "-m", "init");
    }

    protected static void runGit(Path workingDirectory, String... command) throws Exception {
        Process process = new ProcessBuilder(command)
                .directory(workingDirectory.toFile())
                .redirectErrorStream(true)
                .start();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            String output = new String(process.getInputStream().readAllBytes());
            throw new IllegalStateException("git command failed: " + String.join(" ", command) + "\n" + output);
        }
    }

    protected static void openProjectThroughModal(Page page, String projectName, Path projectDir) {
        openProjectThroughModal(page, projectName, projectDir, () -> {
        });
    }

    protected static void openProject(Page page, String projectName, Path projectDir) {
        page.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON, new Page.GetByRoleOptions().setName("New tab")).click();
        assertThat(page.locator("#project-modal")).isVisible();
        openProjectThroughModal(page, projectName, projectDir);
    }

    protected static void openProjectThroughModal(Page page, String projectName, Path projectDir, Runnable afterDirectorySelected) {
        page.locator(".project-form-field input[name='name']").fill(projectName);
        page.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON, new Page.GetByRoleOptions().setName(projectDir.getFileName().toString())).click();
        page.locator(".project-form-field input[name='name']").fill(projectName);
        assertThat(page.locator("#project-path-input")).hasValue(projectDir.toAbsolutePath().normalize().toString());
        afterDirectorySelected.run();
        page.locator(".project-form-field input[name='name']").fill(projectName);
        page.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Open").setExact(true)).click();
        assertThat(page.locator(".project-tab-group.active .project-tab-label")).hasText(projectName);
    }

    protected static void createImageFile(Path projectDir, String relativePath) throws Exception {
        Path image = projectDir.resolve(relativePath);
        Files.createDirectories(image.getParent());
        Files.write(image, new byte[] {(byte) 0x89, 'P', 'N', 'G'});
    }

    protected record RunningApp(ConfigurableApplicationContext context, String baseUrl, Runnable cleanup) implements AutoCloseable {
        @Override
        public void close() {
            try {
                context.close();
            } finally {
                cleanup.run();
            }
        }

        int port() {
            String value = baseUrl.substring(baseUrl.lastIndexOf(':') + 1);
            return Integer.parseInt(value);
        }
    }
}
