package com.judepereira.jupiter.e2e;

import com.judepereira.jupiter.persistence.AppStateRepository;
import com.judepereira.jupiter.persistence.AppStateService;
import com.judepereira.jupiter.persistence.DocumentationScreenshotFixture;
import com.judepereira.jupiter.persistence.DocumentationScreenshotFixture.Fixture;
import com.judepereira.jupiter.persistence.DocumentationScreenshotFixture.FixturePaths;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.ColorScheme;
import com.microsoft.playwright.options.ReducedMotion;
import com.microsoft.playwright.options.ScreenshotScale;
import com.microsoft.playwright.options.ViewportSize;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

class DocumentationScreenshotsTest extends E2ETestSupport {

    private static final int DESKTOP_WIDTH = 1300;
    private static final int DESKTOP_HEIGHT = 744;
    private static final double DESKTOP_DPR = 1.0;

    private static final int MOBILE_WIDTH = 402;
    private static final int MOBILE_HEIGHT = 844;
    private static final double MOBILE_DPR = 1.0;

    private static final String FIXED_BROWSER_TIME = "2026-09-08T09:45:00+02:00";


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

        Path fakeHome = Files.createDirectories(fixtureRoot.resolve("home"));
        GitFixture git = createGitFixture(fakeHome);
        Path dbFile = fixtureRoot.resolve("jupiter.sqlite");

        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", fakeHome.toString());

        try (CatalogServer catalogServer = CatalogServer.start();
             RunningApp app = startApp(fakeHome, dbFile,
                     Map.of("models.dev.catalog-url", catalogServer.url()))) {
            AppStateRepository repository = app.context().getBean(AppStateRepository.class);
            Fixture fixture = DocumentationScreenshotFixture.seed(repository, new FixturePaths(
                    git.jupiterProject(),
                    git.docsWorkspace(),
                    git.mcpWorkspace(),
                    git.blueCaveProject(),
                    git.websiteProject()));

            captureDesktop(app, fixture, outputDir.resolve("interface-desktop.png"));

            // The desktop shot intentionally demonstrates Review. Keep mobile focused on chat.
            boolean reviewOpen = app.context().getBean(AppStateService.class)
                    .toggleReviewPanel(fixture.activeSessionId());
            if (reviewOpen) {
                throw new IllegalStateException("Expected the fixture review panel to close before mobile capture");
            }
            captureMobile(app, fixture, outputDir.resolve("interface-mobile.png"));
        } finally {
            if (previousHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", previousHome);
            }
        }
    }

    private static void captureDesktop(RunningApp app, Fixture fixture, Path output) throws Exception {
        Browser.NewContextOptions options = commonContextOptions(DESKTOP_WIDTH, DESKTOP_HEIGHT, DESKTOP_DPR);
        try (BrowserContext context = newBrowserContext(options)) {
            Page page = context.newPage();
            preparePage(page, app, fixture, DESKTOP_DPR);
            page.locator("#review-panel").waitFor();
            settleChat(page);
            captureViewport(page, output, DESKTOP_WIDTH, DESKTOP_HEIGHT, DESKTOP_DPR);
        }
    }

    private static void captureMobile(RunningApp app, Fixture fixture, Path output) throws Exception {
        Browser.NewContextOptions options = commonContextOptions(MOBILE_WIDTH, MOBILE_HEIGHT, MOBILE_DPR)
                .setIsMobile(true)
                .setHasTouch(true);
        try (BrowserContext context = newBrowserContext(options)) {
            Page page = context.newPage();
            preparePage(page, app, fixture, MOBILE_DPR);
            page.waitForFunction("() => window.matchMedia('(max-width: 767.98px)').matches");
            settleChat(page);
            captureViewport(page, output, MOBILE_WIDTH, MOBILE_HEIGHT, MOBILE_DPR);
        }
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
        page.waitForFunction("() => document.querySelectorAll('#chat-messages-list > li').length >= 8");
        page.waitForFunction("() => Array.from(document.querySelectorAll('#chat-messages-list .chat-message-text'))"
                + ".every(node => node.dataset.markdownRendered === 'true')");
        page.waitForFunction("() => Array.from(document.querySelectorAll('#chat-messages-list .chat-message-subtitle-text'))"
                + ".every(node => node.textContent.trim().length > 0)");
    }

    private static void settleChat(Page page) {
        page.locator("#chat-history").evaluate("el => { el.scrollTop = el.scrollHeight; }");
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
            throw new IllegalStateException("Unexpected screenshot size for " + output + ": got "
                    + image.getWidth() + "x" + image.getHeight() + ", expected "
                    + expectedWidth + "x" + expectedHeight);
        }
    }

    private static GitFixture createGitFixture(Path fakeHome) throws Exception {
        Path projects = Files.createDirectories(fakeHome.resolve("projects"));
        Path worktrees = Files.createDirectories(fakeHome.resolve(".trees/jupiter"));

        Path jupiter = projects.resolve("jupiter");
        initRepository(jupiter, "Jupiter");
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

        return new GitFixture(jupiter, docsWorkspace, mcpWorkspace, blueCave, website);
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
                    + "\n" + new String(output));
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

    private static final class CatalogServer implements AutoCloseable {
        private final HttpServer server;

        private CatalogServer(HttpServer server) {
            this.server = server;
        }

        static CatalogServer start() throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            byte[] body = MODEL_CATALOG_JSON.getBytes(StandardCharsets.UTF_8);
            server.createContext("/catalog.json", exchange -> {
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(200, body.length);
                try (var response = exchange.getResponseBody()) {
                    response.write(body);
                }
            });
            server.start();
            return new CatalogServer(server);
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/catalog.json";
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    private record GitFixture(Path jupiterProject, Path docsWorkspace, Path mcpWorkspace,
                              Path blueCaveProject, Path websiteProject) {
    }
}
