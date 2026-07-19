package com.judepereira.jupiter2.e2e;

import com.judepereira.jupiter2.JupiterV2Application;
import com.microsoft.playwright.Page;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.stream.Stream;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

abstract class E2ETestSupport {

    protected static RunningApp startApp(Path fakeHome, Path dbFile, Class<?>... testConfigClasses) {
        String jdbcUrl = "jdbc:h2:file:" + dbFile.toAbsolutePath().normalize() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE";
        Class<?>[] sources = Stream.concat(Stream.of(JupiterV2Application.class), Arrays.stream(testConfigClasses)).toArray(Class<?>[]::new);
        ConfigurableApplicationContext context = new SpringApplicationBuilder(sources)
                .web(WebApplicationType.SERVLET)
                .properties(
                        "server.port=0",
                        "spring.datasource.url=" + jdbcUrl,
                        "spring.datasource.driver-class-name=org.h2.Driver",
                        "spring.datasource.username=sa",
                        "spring.datasource.password=",
                        "spring.flyway.enabled=true",
                        "spring.docker.compose.enabled=false",
                        "agent.workspace-root=" + fakeHome.toAbsolutePath().normalize(),
                        "openai.api-key=test"
                )
                .run();

        Integer port = context.getEnvironment().getProperty("local.server.port", Integer.class);
        if (port == null) {
            throw new IllegalStateException("Missing local.server.port");
        }
        return new RunningApp(context, "http://localhost:" + port);
    }

    protected static void captureScreenshot(Page page, Path screenshotsDir, String fileName) {
        page.screenshot(new Page.ScreenshotOptions()
                .setPath(screenshotsDir.resolve(fileName))
                .setFullPage(true));
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

    protected record RunningApp(ConfigurableApplicationContext context, String baseUrl) implements AutoCloseable {
        @Override
        public void close() {
            context.close();
        }
    }
}
