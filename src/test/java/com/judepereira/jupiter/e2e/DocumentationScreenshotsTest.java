package com.judepereira.jupiter.e2e;

import com.judepereira.jupiter.persistence.AppStateRepository;
import com.judepereira.jupiter.persistence.AppStateService;
import com.judepereira.jupiter.persistence.DocumentationScreenshotFixture;
import com.judepereira.jupiter.persistence.DocumentationScreenshotFixture.Fixture;
import com.judepereira.jupiter.persistence.DocumentationScreenshotFixture.FixturePaths;
import com.judepereira.jupiter.persistence.Persistence;
import com.judepereira.jupiter.persistence.TokenUsageService;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.ColorScheme;
import com.microsoft.playwright.options.ReducedMotion;
import com.microsoft.playwright.options.ScreenshotScale;
import com.microsoft.playwright.options.ViewportSize;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

class DocumentationScreenshotsTest extends E2ETestSupport {

    private static final int DESKTOP_WIDTH = 1300;
    private static final int DESKTOP_HEIGHT = 744;
    private static final double DESKTOP_DPR = 3.0;

    private static final int MOBILE_WIDTH = 402;
    private static final int MOBILE_HEIGHT = 844;
    private static final double MOBILE_DPR = 3.0;

    private static final String FIXED_BROWSER_TIME = "2026-09-08T09:45:00+02:00";

    private static final List<String> CATALOG = List.of(
            "interface-desktop.png",
            "interface-mobile.png",
            "projects.png",
            "workspaces.png",
            "sessions-and-chat.png",
            "agents-models-thinking.png",
            "review-and-diffs.png",
            "tool-calls-and-images.png",
            "subagent-session.png",
            "terminal.png",
            "project-settings.png",
            "lifecycle-hooks.png",
            "mcp-servers.png",
            "openai-authentication.png",
            "usage-and-token-tracking.png",
            "slash-commands.png"
    );

    private static final List<Persistence.ProjectTokenUsageHourly> FIXED_USAGE = List.of(
            new Persistence.ProjectTokenUsageHourly(Instant.parse("2026-09-08T04:00:00Z"), "openai/gpt-5.6-terra", 2, 9_800L, 2_400L, 12_200L),
            new Persistence.ProjectTokenUsageHourly(Instant.parse("2026-09-08T05:00:00Z"), "openai/gpt-5.6-terra", 4, 22_300L, 5_200L, 27_500L),
            new Persistence.ProjectTokenUsageHourly(Instant.parse("2026-09-08T05:00:00Z"), "openai/gpt-5.6-luna", 3, 11_100L, 2_700L, 13_800L),
            new Persistence.ProjectTokenUsageHourly(Instant.parse("2026-09-08T06:00:00Z"), "openai/gpt-5.6-sol", 1, 18_700L, 4_900L, 23_600L),
            new Persistence.ProjectTokenUsageHourly(Instant.parse("2026-09-08T07:00:00Z"), "openai/gpt-5.6-terra", 3, 16_900L, 3_800L, 20_700L),
            new Persistence.ProjectTokenUsageHourly(Instant.parse("2026-09-08T07:00:00Z"), "openai/gpt-5.6-luna", 5, 13_200L, 3_100L, 16_300L)
    );

    private static final String MODEL_CATALOG_JSON = """
            {
              "models": {
                "openai/gpt-5.5": {
                  "id": "openai/gpt-5.5",
                  "name": "GPT-5.5",
                  "reasoning": true,
                  "tool_call": true,
                  "limit": {"context": 400000, "output": 128000},
                  "release_date": "2026-05-01"
                },
                "openai/gpt-5.6-luna": {
                  "id": "openai/gpt-5.6-luna",
                  "name": "GPT-5.6 Luna",
                  "reasoning": true,
                  "tool_call": true,
                  "limit": {"context": 400000, "output": 128000},
                  "release_date": "2026-08-01"
                },
                "openai/gpt-5.6-terra": {
                  "id": "openai/gpt-5.6-terra",
                  "name": "GPT-5.6 Terra",
                  "reasoning": true,
                  "tool_call": true,
                  "limit": {"context": 400000, "output": 128000},
                  "release_date": "2026-08-01"
                },
                "openai/gpt-5.6-sol": {
                  "id": "openai/gpt-5.6-sol",
                  "name": "GPT-5.6 Sol",
                  "reasoning": true,
                  "tool_call": true,
                  "limit": {"context": 400000, "output": 128000},
                  "release_date": "2026-08-01"
                }
              }
            }
            """;

    private static final String SCREENSHOT_STYLE = """
            *, *::before, *::after {
                animation: none !important;
                transition: none !important;
                scroll-behavior: auto !important;
                caret-color: transparent !important;
            }
            """;

    @Test
    void captureDocumentationScreenshots() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("documentation.screenshots"),
                "Run scripts/screenshots.sh to generate documentation screenshots");

        Path repositoryRoot = Path.of("").toAbsolutePath().normalize();
        Path fixtureRoot = repositoryRoot.resolve("target/documentation-screenshot-fixture");
        Path outputDir = repositoryRoot.resolve(
                System.getProperty("documentation.screenshots.output", ".wiki/images"));

        recreateDirectory(fixtureRoot);
        Files.createDirectories(outputDir);
        for (String fileName : CATALOG) {
            Files.deleteIfExists(outputDir.resolve(fileName));
        }

        Path fakeHome = Files.createDirectories(fixtureRoot.resolve("home"));
        createCustomCommands(fakeHome);
        GitFixture git = createGitFixture(fakeHome);
        Path dbFile = fixtureRoot.resolve("jupiter.sqlite");

        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", fakeHome.toString());

        try (FixtureServer fixtureServer = FixtureServer.start();
             RunningApp app = startApp(fakeHome, dbFile,
                     Map.of(
                             "models.dev.catalog-url", fixtureServer.catalogUrl(),
                             "openai.oauth.issuer", fixtureServer.baseUrl(),
                             "openai.oauth.client-id", "documentation-screenshots"
                     ), ScreenshotUsageConfig.class)) {
            AppStateRepository repository = app.context().getBean(AppStateRepository.class);
            Fixture fixture = DocumentationScreenshotFixture.seed(repository, new FixturePaths(
                    git.jupiterProject(),
                    git.docsWorkspace(),
                    git.mcpWorkspace(),
                    git.blueCaveProject(),
                    git.websiteProject()));

            captureInterfaceDesktop(app, fixture, outputDir);
            captureInterfaceMobile(app, fixture, outputDir);
            captureProjects(app, fixture, git.sampleProject(), outputDir);
            captureWorkspaces(app, fixture, outputDir);
            captureSessionsAndChat(app, fixture, outputDir);
            captureAgentsModelsThinking(app, fixture, outputDir);
            captureReviewAndDiffs(app, fixture, outputDir);
            captureToolCallsAndImages(app, fixture, outputDir);
            captureSubagentSession(app, fixture, outputDir);
            captureProjectSettings(app, fixture, outputDir);
            captureLifecycleHooks(app, fixture, outputDir);
            captureMcpServers(app, fixture, outputDir);
            captureOpenAiAuthentication(app, fixture, outputDir);
            captureUsage(app, fixture, outputDir);
            captureSlashCommands(app, fixture, outputDir);
            captureTerminal(app, fixture, outputDir);

            verifyCatalog(outputDir);
        } finally {
            if (previousHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", previousHome);
            }
        }
    }

    private static void captureInterfaceDesktop(RunningApp app, Fixture fixture, Path outputDir) throws Exception {
        captureDesktop(app, fixture, true, outputDir.resolve("interface-desktop.png"), page -> {
            page.locator("#review-panel").waitFor();
            settleChat(page);
        });
    }

    private static void captureInterfaceMobile(RunningApp app, Fixture fixture, Path outputDir) throws Exception {
        ensureReviewState(app, fixture, false);
        Browser.NewContextOptions options = commonContextOptions(MOBILE_WIDTH, MOBILE_HEIGHT, MOBILE_DPR)
                .setIsMobile(true)
                .setHasTouch(true);
        try (BrowserContext context = newBrowserContext(options)) {
            Page page = context.newPage();
            preparePage(page, app, fixture, MOBILE_DPR);
            page.waitForFunction("() => window.matchMedia('(max-width: 767.98px)').matches");
            settleChat(page);
            captureViewport(page, outputDir.resolve("interface-mobile.png"), MOBILE_WIDTH, MOBILE_HEIGHT, MOBILE_DPR);
        }
    }

    private static void captureProjects(RunningApp app, Fixture fixture, Path sampleProject, Path outputDir) throws Exception {
        captureDesktop(app, fixture, false, outputDir.resolve("projects.png"), page -> {
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("New tab")).click();
            page.locator("#project-modal").waitFor();
            page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName(sampleProject.getFileName().toString()).setExact(true)).click();
            page.locator("#project-name-input").fill("Sample Service");
            page.waitForFunction("expected => document.querySelector('#project-path-input')?.value === expected",
                    sampleProject.toAbsolutePath().normalize().toString());
            settlePage(page);
        });
    }

    private static void captureWorkspaces(RunningApp app, Fixture fixture, Path outputDir) throws Exception {
        captureDesktop(app, fixture, false, outputDir.resolve("workspaces.png"), page -> {
            page.locator(".workspace-create-button").click();
            page.locator("#workspace-modal").waitFor();
            page.locator("[data-workspace-branch-name]").fill("feature/screenshot-catalog");
            settlePage(page);
        });
    }

    private static void captureSessionsAndChat(RunningApp app, Fixture fixture, Path outputDir) throws Exception {
        captureDesktop(app, fixture, false, outputDir.resolve("sessions-and-chat.png"), DocumentationScreenshotsTest::settleChat);
    }

    private static void captureAgentsModelsThinking(RunningApp app, Fixture fixture, Path outputDir) throws Exception {
        captureDesktop(app, fixture, false, outputDir.resolve("agents-models-thinking.png"), page -> {
            page.locator("#chat-agent-select").selectOption("plan");
            page.locator("#chat-model-select").selectOption("openai/gpt-5.6-sol");
            page.locator("#chat-thinking-select").selectOption("HIGH");
            settleChat(page);
        });
    }

    private static void captureReviewAndDiffs(RunningApp app, Fixture fixture, Path outputDir) throws Exception {
        captureDesktop(app, fixture, true, outputDir.resolve("review-and-diffs.png"), page -> {
            page.locator("#review-panel").waitFor();
            page.locator("#review-panel pre").first().waitFor();
            settlePage(page);
        });
    }

    private static void captureToolCallsAndImages(RunningApp app, Fixture fixture, Path outputDir) throws Exception {
        captureDesktop(app, fixture, false, outputDir.resolve("tool-calls-and-images.png"), page -> {
            var imageGroup = page.locator("[data-tool-call-target='group'][data-tool-call-tool-name='display_image']").first();
            imageGroup.waitFor();
            page.waitForFunction("() => document.querySelector('.tool-call-image-preview img')?.naturalWidth > 0");
            imageGroup.evaluate("el => el.scrollIntoView({block: 'center', inline: 'nearest'})");
            settlePage(page);
        });
    }

    private static void captureSubagentSession(RunningApp app, Fixture fixture, Path outputDir) throws Exception {
        captureDesktop(app, fixture, false, outputDir.resolve("subagent-session.png"), page -> {
            var task = page.locator("[data-tool-call-target='group'][data-tool-call-tool-name='task']").first();
            task.waitFor();
            var button = task.locator(".tool-call-subagent-button");
            button.waitFor();
            button.click();
            page.locator(".subagent-bar").waitFor();
            page.locator(".subagent-bar-name").waitFor();
            settleChat(page);
        });
    }

    private static void captureProjectSettings(RunningApp app, Fixture fixture, Path outputDir) throws Exception {
        captureDesktop(app, fixture, false, outputDir.resolve("project-settings.png"), page -> {
            openSettings(page);
            page.locator("#settings-current-project").waitFor();
            settlePage(page);
        });
    }

    private static void captureLifecycleHooks(RunningApp app, Fixture fixture, Path outputDir) throws Exception {
        captureDesktop(app, fixture, false, outputDir.resolve("lifecycle-hooks.png"), page -> {
            openSettings(page);
            page.locator("#settings-hooks-tab").click();
            page.locator("#settings-hooks").waitFor();
            page.locator("textarea[name='assistantCompletedScript']").waitFor();
            settlePage(page);
        });
    }

    private static void captureMcpServers(RunningApp app, Fixture fixture, Path outputDir) throws Exception {
        captureDesktop(app, fixture, false, outputDir.resolve("mcp-servers.png"), page -> {
            openSettings(page);
            page.locator("#settings-mcp-servers-tab").click();
            page.locator("#settings-mcp-servers [data-settings-mcp-server]").first().waitFor();
            settlePage(page);
        });
    }

    private static void captureOpenAiAuthentication(RunningApp app, Fixture fixture, Path outputDir) throws Exception {
        captureDesktop(app, fixture, false, outputDir.resolve("openai-authentication.png"), page -> {
            openSettings(page);
            page.locator("#settings-model-providers-tab").click();
            page.locator("#openai-oauth-section").waitFor();
            page.getByRole(AriaRole.BUTTON,
                    new Page.GetByRoleOptions().setName("Connect ChatGPT/OpenAI subscription")).click();
            page.locator(".settings-openai-user-code").waitFor();
            page.waitForFunction("() => document.querySelector('.settings-openai-user-code')?.textContent.trim() === 'JUPI-TER7'");
            settlePage(page);
        });
    }

    private static void captureUsage(RunningApp app, Fixture fixture, Path outputDir) throws Exception {
        captureDesktop(app, fixture, false, outputDir.resolve("usage-and-token-tracking.png"), page -> {
            openSettings(page);
            page.evaluate("() => { if (window.Chart) { Chart.defaults.animation = false; } }");
            page.locator("#settings-usage-tab").click();
            page.locator("[data-settings-usage-chart]").waitFor();
            page.waitForFunction("() => Number((document.querySelector('[data-usage-requests]')?.textContent || '0').replace(/[^0-9]/g, '')) > 0");
            settlePage(page);
        });
    }

    private static void captureSlashCommands(RunningApp app, Fixture fixture, Path outputDir) throws Exception {
        captureDesktop(app, fixture, false, outputDir.resolve("slash-commands.png"), page -> {
            settleChat(page);
            page.locator("#chat-input").fill("/");
            page.locator("#command-modal").waitFor();
            page.locator(".command-modal-item").first().waitFor();
            settlePage(page);
        });
    }

    private static void captureTerminal(RunningApp app, Fixture fixture, Path outputDir) throws Exception {
        ensureReviewState(app, fixture, false);
        Browser.NewContextOptions options = commonContextOptions(DESKTOP_WIDTH, DESKTOP_HEIGHT, DESKTOP_DPR);
        try (BrowserContext context = newBrowserContext(options)) {
            Page page = context.newPage();
            preparePage(page, app, fixture, DESKTOP_DPR);
            page.locator("#toggle-terminal-rail-btn").click();
            page.locator("#bottom-panel .terminal-mount .xterm").waitFor();
            page.locator(".terminal-mount").click();
            page.keyboard().type("export PS1='jupiter:docs/screenshots$ '; clear; printf 'Documentation screenshot catalog\\n16 captures -> .wiki/images\\n'");
            page.keyboard().press("Enter");
            page.waitForFunction("() => document.querySelector('.terminal-mount .xterm-rows')?.textContent.includes('16 captures -> .wiki/images')");
            settlePage(page);
            captureViewport(page, outputDir.resolve("terminal.png"), DESKTOP_WIDTH, DESKTOP_HEIGHT, DESKTOP_DPR);
            page.locator("#toggle-terminal-rail-btn").click();
        }
    }

    private static void captureDesktop(RunningApp app, Fixture fixture, boolean reviewOpen, Path output,
                                       Consumer<Page> setup) throws Exception {
        ensureReviewState(app, fixture, reviewOpen);
        Browser.NewContextOptions options = commonContextOptions(DESKTOP_WIDTH, DESKTOP_HEIGHT, DESKTOP_DPR);
        try (BrowserContext context = newBrowserContext(options)) {
            Page page = context.newPage();
            preparePage(page, app, fixture, DESKTOP_DPR);
            setup.accept(page);
            captureViewport(page, output, DESKTOP_WIDTH, DESKTOP_HEIGHT, DESKTOP_DPR);
        }
    }

    private static void ensureReviewState(RunningApp app, Fixture fixture, boolean expectedOpen) {
        AppStateService service = app.context().getBean(AppStateService.class);
        var view = service.loadViewData();
        if (view.activeSession() == null || view.activeSession().id() != fixture.activeSessionId()) {
            service.activateSession(fixture.activeSessionId());
            view = service.loadViewData();
        }
        boolean current = view.activeSessionDetail().reviewPanelOpen();
        if (current != expectedOpen) {
            boolean updated = service.toggleReviewPanel(fixture.activeSessionId());
            if (updated != expectedOpen) {
                throw new IllegalStateException("Failed to set deterministic review panel state");
            }
        }
    }

    private static void openSettings(Page page) {
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Settings")).click();
        page.locator("#settings-modal").waitFor();
    }

    private static Browser.NewContextOptions commonContextOptions(int width, int height, double dpr) {
        return new Browser.NewContextOptions()
                .setViewportSize(new ViewportSize(width, height))
                .setScreenSize(width, height)
                .setDeviceScaleFactor(dpr)
                .setLocale("en-GB")
                .setTimezoneId("Europe/Amsterdam")
                .setColorScheme(ColorScheme.LIGHT)
                .setReducedMotion(ReducedMotion.REDUCE);
    }

    private static void preparePage(Page page, RunningApp app, Fixture fixture, double expectedDpr) {
        page.clock().setFixedTime(FIXED_BROWSER_TIME);
        page.navigate(app.baseUrl());
        page.locator("#chat-container[data-session-id='" + fixture.activeSessionId() + "']").waitFor();
        page.locator(".project-tab-group.active .project-tab-label").waitFor();
        page.addStyleTag(new Page.AddStyleTagOptions().setContent(SCREENSHOT_STYLE));
        page.evaluate("async () => { await document.fonts.ready; }");
        page.waitForFunction("() => document.fonts.status === 'loaded'");
        page.waitForFunction("expected => Math.abs(window.devicePixelRatio - expected) < 0.01", expectedDpr);
        page.waitForFunction("() => document.querySelectorAll('#chat-messages-list > li').length >= 10");
        page.waitForFunction("() => Array.from(document.querySelectorAll('#chat-messages-list .chat-message-text'))"
                + ".every(node => node.dataset.markdownRendered === 'true')");
        page.waitForFunction("() => Array.from(document.querySelectorAll('#chat-messages-list .chat-message-subtitle-text'))"
                + ".every(node => node.textContent.trim().length > 0)");
    }

    private static void settleChat(Page page) {
        page.locator("#chat-history").evaluate("el => { el.scrollTop = el.scrollHeight; }");
        settlePage(page);
    }

    private static void settlePage(Page page) {
        page.evaluate("() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))");
    }

    private static void captureViewport(Page page, Path output, int cssWidth, int cssHeight, double dpr) throws IOException {
        page.screenshot(new Page.ScreenshotOptions()
                .setPath(output)
                .setFullPage(false)
                .setScale(ScreenshotScale.DEVICE));

        BufferedImage image = ImageIO.read(output.toFile());
        if (image == null) {
            throw new IllegalStateException("Playwright did not produce a readable PNG: " + output);
        }
        int expectedWidth = (int) Math.round(cssWidth * dpr);
        int expectedHeight = (int) Math.round(cssHeight * dpr);
        if (image.getWidth() != expectedWidth || image.getHeight() != expectedHeight) {
            throw new IllegalStateException("Unexpected screenshot dimensions for " + output + ": got "
                    + image.getWidth() + "x" + image.getHeight() + ", expected "
                    + expectedWidth + "x" + expectedHeight);
        }
    }

    private static void verifyCatalog(Path outputDir) throws IOException {
        for (String fileName : CATALOG) {
            Path image = outputDir.resolve(fileName);
            if (!Files.isRegularFile(image) || Files.size(image) == 0) {
                throw new IllegalStateException("Missing documentation screenshot: " + image);
            }
        }
    }

    private static void createCustomCommands(Path fakeHome) throws IOException {
        Path commands = Files.createDirectories(fakeHome.resolve(".jupiter/commands"));
        Files.writeString(commands.resolve("review-pr.md"), """
                ---
                id: review-pr
                name: Review PR
                description: Review the current changes before opening a pull request
                type: prompt
                ---

                Review the current changes for correctness, tests and anything that should be fixed before opening a pull request.
                """.stripLeading());
        Files.writeString(commands.resolve("smoke-test.md"), """
                ---
                id: smoke-test
                name: Smoke Test
                description: Run the quick local smoke test
                type: script
                timeoutSeconds: 30
                ---

                ./mvnw -q -DskipE2E=true test
                """.stripLeading());
    }

    private static GitFixture createGitFixture(Path fakeHome) throws Exception {
        Path projects = Files.createDirectories(fakeHome.resolve("projects"));
        Path worktrees = Files.createDirectories(fakeHome.resolve("worktrees/jupiter"));

        Path jupiter = projects.resolve("jupiter");
        initRepository(jupiter, "Jupiter");
        createArchitecturePng(jupiter.resolve("docs/architecture.png"));
        runGit(jupiter, "git", "add", "docs/architecture.png");
        Map<String, String> architectureCommitEnvironment = new HashMap<>();
        architectureCommitEnvironment.put("GIT_AUTHOR_DATE", "2026-09-08T07:02:00Z");
        architectureCommitEnvironment.put("GIT_COMMITTER_DATE", "2026-09-08T07:02:00Z");
        runCommand(jupiter, architectureCommitEnvironment, "git", "commit", "--quiet", "-m", "Add architecture fixture");

        runGit(jupiter, "git", "branch", "docs/screenshots");
        runGit(jupiter, "git", "branch", "feature/mcp-auth");

        Path docsWorkspace = worktrees.resolve("docs/screenshots");
        Path mcpWorkspace = worktrees.resolve("feature/mcp-auth");
        Files.createDirectories(docsWorkspace.getParent());
        Files.createDirectories(mcpWorkspace.getParent());
        runGit(jupiter, "git", "worktree", "add", "--quiet", docsWorkspace.toString(), "docs/screenshots");
        runGit(jupiter, "git", "worktree", "add", "--quiet", mcpWorkspace.toString(), "feature/mcp-auth");

        Files.createDirectories(docsWorkspace.resolve("docs"));
        Files.writeString(docsWorkspace.resolve("docs/screenshot-notes.md"),
                "# Screenshot fixture\n\nKeep the database, Git state, browser clock and pixels deterministic.\n",
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

        Path blueCave = projects.resolve("blue-cave");
        initRepository(blueCave, "Blue Cave");
        Path website = projects.resolve("website");
        initRepository(website, "Website");
        Path sampleProject = fakeHome.resolve("sample-app");
        initRepository(sampleProject, "Sample App");

        return new GitFixture(jupiter, docsWorkspace, mcpWorkspace, blueCave, website, sampleProject);
    }

    private static void createArchitecturePng(Path output) throws IOException {
        Files.createDirectories(output.getParent());
        BufferedImage image = new BufferedImage(640, 360, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
            graphics.setColor(new Color(248, 250, 252));
            graphics.fillRect(0, 0, 640, 360);

            graphics.setColor(new Color(37, 99, 235));
            graphics.fillRoundRect(60, 120, 120, 90, 18, 18);
            graphics.setColor(new Color(5, 150, 105));
            graphics.fillRoundRect(260, 70, 120, 90, 18, 18);
            graphics.setColor(new Color(124, 58, 237));
            graphics.fillRoundRect(460, 120, 120, 90, 18, 18);
            graphics.setColor(new Color(217, 119, 6));
            graphics.fillRoundRect(260, 220, 120, 70, 18, 18);

            graphics.setStroke(new BasicStroke(6));
            graphics.setColor(new Color(71, 85, 105));
            graphics.drawLine(180, 165, 260, 115);
            graphics.drawLine(380, 115, 460, 165);
            graphics.drawLine(320, 160, 320, 220);
        } finally {
            graphics.dispose();
        }
        if (!ImageIO.write(image, "png", output.toFile())) {
            throw new IllegalStateException("PNG writer is unavailable");
        }
    }

    private static void initRepository(Path directory, String title) throws Exception {
        Files.createDirectories(directory);
        runGit(directory, "git", "init", "--quiet", "-b", "main");
        runGit(directory, "git", "config", "user.name", "Jupiter Screenshots");
        runGit(directory, "git", "config", "user.email", "screenshots@example.invalid");

        Files.writeString(directory.resolve("README.md"), "# " + title + "\n\nDeterministic documentation fixture.\n");
        Files.createDirectories(directory.resolve("src"));
        Files.writeString(directory.resolve("src/example.txt"), "fixture\n");
        runGit(directory, "git", "add", ".");

        Map<String, String> fixedGitEnvironment = new HashMap<>();
        fixedGitEnvironment.put("GIT_AUTHOR_DATE", "2026-09-08T07:00:00Z");
        fixedGitEnvironment.put("GIT_COMMITTER_DATE", "2026-09-08T07:00:00Z");
        runCommand(directory, fixedGitEnvironment, "git", "commit", "--quiet", "-m", "Initial fixture");
    }

    private static void runCommand(Path workingDirectory, Map<String, String> extraEnvironment,
                                   String... command) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(workingDirectory.toFile())
                .redirectErrorStream(true);
        builder.environment().putAll(extraEnvironment);
        Process process = builder.start();
        byte[] output = process.getInputStream().readAllBytes();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IllegalStateException("Command failed (" + exitCode + "): " + String.join(" ", command)
                    + "\n" + new String(output, StandardCharsets.UTF_8));
        }
    }

    private static void recreateDirectory(Path directory) throws IOException {
        if (Files.exists(directory)) {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
        Files.createDirectories(directory);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ScreenshotUsageConfig {
        @Bean
        @Primary
        TokenUsageService documentationScreenshotTokenUsageService() {
            return new TokenUsageService(null, null) {
                @Override
                public List<Persistence.ProjectTokenUsageHourly> findProjectHourlyUsage(long projectId, Instant fromInclusive,
                                                                                        Instant toExclusive) {
                    return FIXED_USAGE;
                }
            };
        }
    }

    private static final class FixtureServer implements AutoCloseable {
        private final HttpServer server;

        private FixtureServer(HttpServer server) {
            this.server = server;
        }

        static FixtureServer start() throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            FixtureServer fixture = new FixtureServer(server);
            server.createContext("/catalog.json", exchange -> respond(exchange, 200, MODEL_CATALOG_JSON));
            server.createContext("/api/accounts/deviceauth/usercode", exchange -> respond(exchange, 200, """
                    {
                      "device_auth_id": "docs-device-1",
                      "user_code": "JUPI-TER7",
                      "interval": 60,
                      "expires": 600
                    }
                    """));
            server.createContext("/api/accounts/deviceauth/token", exchange -> respond(exchange, 403, ""));
            server.createContext("/codex/device", exchange -> respond(exchange, 200, "ok"));
            server.start();
            return fixture;
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        String catalogUrl() {
            return baseUrl() + "/catalog.json";
        }

        @Override
        public void close() {
            server.stop(0);
        }

        private static void respond(HttpExchange exchange, int status, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(status, bytes.length);
            try (var response = exchange.getResponseBody()) {
                response.write(bytes);
            }
        }
    }

    private record GitFixture(Path jupiterProject, Path docsWorkspace, Path mcpWorkspace,
                              Path blueCaveProject, Path websiteProject, Path sampleProject) {
    }
}
