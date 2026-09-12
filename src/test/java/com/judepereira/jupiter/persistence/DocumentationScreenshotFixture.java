package com.judepereira.jupiter.persistence;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * Deterministic application state used by documentation screenshots.
 *
 * <p>This deliberately lives in the persistence package so it can use the same
 * repository write path as the application, including encryption, without
 * introducing screenshot-only production APIs.</p>
 */
public final class DocumentationScreenshotFixture {

    private static final Instant BASE = Instant.parse("2026-09-08T07:20:00Z");
    private static final String AGENT_ID = "engineer";
    private static final String AGENT_NAME = "Engineer";
    private static final String MODEL_ID = "openai/gpt-5.6-sol";
    private static final String THINKING = "HIGH";

    private DocumentationScreenshotFixture() {
    }

    public static Fixture seed(AppStateRepository repository, FixturePaths paths) {
        repository.updateAutoGitUpdateEnabled(false);

        long jupiterProjectId = repository.insertProject(
                "Jupiter", normalized(paths.jupiterProject()), 1, BASE.minusSeconds(1_200));
        long blueCaveProjectId = repository.insertProject(
                "Blue Cave", normalized(paths.blueCaveProject()), 2, BASE.minusSeconds(900));
        long websiteProjectId = repository.insertProject(
                "Website", normalized(paths.websiteProject()), 3, BASE.minusSeconds(600));

        repository.updateProjectWorkspaceInitCommands(jupiterProjectId, """
                ./mvnw -q -DskipTests compile
                ./mvnw -q test -DskipE2E=true
                """.stripTrailing());
        repository.updateProjectEnvironmentVariables(jupiterProjectId,
                "[{\"name\":\"JAVA_HOME\",\"value\":\"/opt/jdk-25\"},{\"name\":\"JUPITER_PROFILE\",\"value\":\"docs\"}]");
        repository.updateProjectCommandEnvironmentAllowlist(jupiterProjectId, "HOME, PATH, CI");

        long jupiterMainWorkspaceId = repository.insertWorkspace(
                jupiterProjectId, "main", normalized(paths.jupiterProject()), 1, BASE.minusSeconds(1_000));
        long docsWorkspaceId = repository.insertWorkspace(
                jupiterProjectId, "docs/screenshots", normalized(paths.docsWorkspace()), 2, BASE.minusSeconds(500));
        long mcpWorkspaceId = repository.insertWorkspace(
                jupiterProjectId, "feature/mcp-auth", normalized(paths.mcpWorkspace()), 3, BASE.minusSeconds(400));

        long blueCaveWorkspaceId = repository.insertWorkspace(
                blueCaveProjectId, "main", normalized(paths.blueCaveProject()), 1, BASE.minusSeconds(800));
        long websiteWorkspaceId = repository.insertWorkspace(
                websiteProjectId, "main", normalized(paths.websiteProject()), 1, BASE.minusSeconds(700));

        long flakySessionId = repository.insertSession(
                jupiterMainWorkspaceId, "Fix flaky Playwright tests", 1,
                BASE.minusSeconds(780), false, Persistence.ReviewSource.SESSION, null);
        long oauthSessionId = repository.insertSession(
                jupiterMainWorkspaceId, "Review OAuth flow", 2,
                BASE.minusSeconds(740), false, Persistence.ReviewSource.SESSION, null);

        long activeSessionId = repository.insertSession(
                docsWorkspaceId, "Build documentation screenshots", 1,
                BASE.minusSeconds(300), false, Persistence.ReviewSource.SESSION, null);
        long mobileSessionId = repository.insertSession(
                docsWorkspaceId, "Polish mobile layout", 2,
                BASE.minusSeconds(240), false, Persistence.ReviewSource.SESSION, null);
        repository.updateSessionUnread(mobileSessionId, true);

        long mcpSessionId = repository.insertSession(
                mcpWorkspaceId, "Add MCP authentication", 1,
                BASE.minusSeconds(180), false, Persistence.ReviewSource.SESSION, null);

        long sdkSessionId = repository.insertSession(
                blueCaveWorkspaceId, "Review Java SDK", 1,
                BASE.minusSeconds(660), false, Persistence.ReviewSource.SESSION, null);
        long homepageSessionId = repository.insertSession(
                websiteWorkspaceId, "Refresh homepage", 1,
                BASE.minusSeconds(620), false, Persistence.ReviewSource.SESSION, null);

        seedShortConversation(repository, flakySessionId,
                "Why is the panel test flaky on CI?",
                "The failure is timing-related. I’d wait for the SSE connection explicitly instead of sleeping before the assertion.",
                BASE.minusSeconds(700));
        seedShortConversation(repository, oauthSessionId,
                "Check the device authorization flow for edge cases.",
                "The polling path already backs off on rate limits. The remaining useful test is expiry while the browser is still open.",
                BASE.minusSeconds(640));
        seedShortConversation(repository, mobileSessionId,
                "The settings dialog still feels cramped on mobile.",
                "I’d keep the navigation stacked above the content at 390px and avoid changing the desktop layout.",
                BASE.minusSeconds(150));
        seedShortConversation(repository, sdkSessionId,
                "Can you review the Java SDK changes?",
                "The public API is small and consistent. I’d only rename the retry option before releasing it.",
                BASE.minusSeconds(580));
        seedShortConversation(repository, homepageSessionId,
                "Tighten the homepage copy without changing the layout.",
                "Done. The first section now says what the product does before explaining how it works.",
                BASE.minusSeconds(560));

        seedPendingConversation(repository, mcpSessionId, BASE.minusSeconds(90));
        ActiveConversation activeConversation = seedActiveConversation(repository, activeSessionId);

        long subagentSessionId = repository.insertSession(
                docsWorkspaceId,
                "Subagent: Explore",
                3,
                BASE.minusSeconds(15),
                false,
                Persistence.ReviewSource.SESSION,
                null,
                true,
                activeSessionId,
                "task-catalog-1",
                "explore",
                "Explore",
                activeConversation.toolAssistantMessageId());
        seedSubagentConversation(repository, subagentSessionId);

        repository.insertToolCallTrace(
                activeSessionId,
                activeConversation.toolAssistantMessageId(),
                1,
                "read-catalog-test",
                "read_file",
                true,
                "{\"path\":\"src/test/java/com/judepereira/jupiter/e2e/DocumentationScreenshotsTest.java\"}",
                "Read the documentation screenshot test.",
                "{}",
                BASE.plusSeconds(88),
                BASE.plusSeconds(84));
        repository.insertToolCallTrace(
                activeSessionId,
                activeConversation.toolAssistantMessageId(),
                2,
                "search-wiki-placeholders",
                "search_code",
                true,
                "{\"query\":\"TODO: add screenshot\",\"path\":\".wiki\"}",
                "Found the visual documentation pages that need captures.",
                "{}",
                BASE.plusSeconds(91),
                BASE.plusSeconds(89));
        repository.insertToolCallTrace(
                activeSessionId,
                activeConversation.toolAssistantMessageId(),
                3,
                "display-architecture",
                "display_image",
                true,
                "{\"path\":\"docs/architecture.png\",\"alt\":\"Jupiter architecture fixture\"}",
                "Rendered the deterministic architecture fixture.",
                "{\"displayType\":\"image\",\"path\":\"docs/architecture.png\",\"alt\":\"Jupiter architecture fixture\",\"mediaType\":\"image/png\"}",
                BASE.plusSeconds(95),
                BASE.plusSeconds(92));
        repository.insertToolCallTrace(
                activeSessionId,
                activeConversation.toolAssistantMessageId(),
                4,
                "task-catalog-1",
                "task",
                true,
                "{\"agentId\":\"explore\",\"requestSummary\":\"Map the wiki screenshot coverage\",\"task\":\"Inspect the screenshot harness and make sure every visual wiki feature has a deterministic capture.\",\"expectedOutput\":\"A short coverage report.\"}",
                "Mapped every visual wiki section to a deterministic capture.",
                "{}",
                BASE.plusSeconds(101),
                BASE.plusSeconds(96));

        long screenshotTestFileId = repository.insertChangedFile(
                activeSessionId,
                "src/test/java/com/judepereira/jupiter/e2e/DocumentationScreenshotsTest.java",
                SCREENSHOT_TEST_DIFF,
                1,
                BASE.plusSeconds(180));
        repository.insertChangedFile(
                activeSessionId,
                "src/test/java/com/judepereira/jupiter/persistence/DocumentationScreenshotFixture.java",
                SCREENSHOT_FIXTURE_DIFF,
                2,
                BASE.plusSeconds(185));
        repository.insertChangedFile(
                activeSessionId,
                "scripts/generate_screenshots.sh",
                SCREENSHOT_SCRIPT_DIFF,
                3,
                BASE.plusSeconds(190));
        repository.updateSessionReviewState(
                activeSessionId, true, Persistence.ReviewSource.SESSION, screenshotTestFileId);

        repository.updateLifecycleHookSettings(
                "printf '%s completed\\n' \"$JUPITER_SESSION_NAME\" >> /tmp/jupiter-hooks.log",
                "printf '%s failed\\n' \"$JUPITER_SESSION_NAME\" >> /tmp/jupiter-hooks.log",
                "printf '%s subagent-complete\\n' \"$JUPITER_SESSION_NAME\" >> /tmp/jupiter-hooks.log",
                30);

        long mcpServerId = repository.insertMcpServer(
                "GitHub",
                "https://mcp.example.test/github",
                true,
                "[{\"name\":\"Authorization\",\"value\":\"Bearer ${env.GITHUB_TOKEN}\"}]",
                BASE.plusSeconds(30));
        repository.replaceMcpServerProjectExposures(mcpServerId, List.of(jupiterProjectId, blueCaveProjectId));

        // Make the screenshot workspace/session the exact state the UI opens into.
        repository.updateProjectLastOpened(jupiterProjectId, BASE.plusSeconds(240));
        repository.updateWorkspaceLastOpened(docsWorkspaceId, BASE.plusSeconds(240));
        repository.updateSessionLastOpened(activeSessionId, BASE.plusSeconds(240));
        repository.updateAppState(jupiterProjectId, docsWorkspaceId, activeSessionId);

        return new Fixture(jupiterProjectId, docsWorkspaceId, activeSessionId, subagentSessionId);
    }

    private static ActiveConversation seedActiveConversation(AppStateRepository repository, long sessionId) {
        turn(repository, sessionId, 1, 1,
                "Can we make the documentation screenshots reproducible? I want the same projects, workspaces and conversation every time.",
                "Yes. I’m treating screenshots as a fixture, not as a manual browser session. The database, Git state, clock, viewport and rendering environment all need to be deterministic.",
                BASE.minusSeconds(120));

        turn(repository, sessionId, 2, 3,
                "Use a real worktree for `docs/screenshots`, too. I don’t want fake paths that only work in the UI.",
                "Agreed. The fixture creates actual Git worktrees first and then points Jupiter at them. That keeps the workspace rail and anything that shells out to Git honest.",
                BASE.minusSeconds(85));

        turn(repository, sessionId, 3, 5,
                "What are we using for the actual captures?",
                "Two fixed profiles:\n\n```text\ndesktop: 1440 × 900 @ 2x\nmobile:   390 × 844 @ 3x\n```\n\nBoth use Playwright Chromium with a fixed clock, light mode and reduced motion. The resulting PNGs are 2880×1800 and 1170×2532.",
                BASE.minusSeconds(48));

        long toolAssistantMessageId = turn(repository, sessionId, 4, 7,
                "Can the catalog cover tool calls, images and subagents as well as the normal chat?",
                "Yes. I’ve put those states into the fixture itself, so the catalog can photograph the real persisted projection rather than invoking a model during screenshot generation.",
                BASE.minusSeconds(20));

        turn(repository, sessionId, 5, 9,
                "And keep this native on macOS. I want Jupiter’s real font stack.",
                "That stays unchanged. The runner installs Playwright’s pinned Chromium build natively and leaves Jupiter’s CSS fonts alone. The fixed 2x and 3x device scale factors keep the PNGs Retina-sharp.",
                BASE.minusSeconds(8));

        return new ActiveConversation(toolAssistantMessageId);
    }

    private static void seedSubagentConversation(AppStateRepository repository, long sessionId) {
        repository.insertConversationMessage(
                sessionId,
                "subagent-user-1",
                "user",
                1,
                1,
                "Primary task:\nInspect the screenshot harness and make sure every visual wiki feature has a deterministic capture.\n\nExpected output:\nA short coverage report.",
                null,
                null,
                true,
                true,
                false,
                BASE.plusSeconds(76));

        Instant assistantStartedAt = BASE.plusSeconds(77);
        Instant assistantCompletedAt = BASE.plusSeconds(83);
        long assistantMessageId = repository.insertConversationMessage(
                sessionId,
                "subagent-assistant-1",
                "assistant",
                1,
                2,
                "The catalog covers the main shell, projects, workspaces, sessions, review, terminal, settings, MCP, OAuth, usage, slash commands, tool calls and this subagent transcript. The mobile capture gets its own 390px state as well.",
                null,
                null,
                true,
                true,
                false,
                "explore",
                "Explore",
                "openai/gpt-5.6-luna",
                "MEDIUM",
                null,
                assistantCompletedAt,
                assistantStartedAt);
        repository.insertToolCallTrace(
                sessionId,
                assistantMessageId,
                1,
                "subagent-read-runner",
                "read_file",
                true,
                "{\"path\":\"scripts/generate_screenshots.sh\"}",
                "Read the native screenshot runner.",
                "{}",
                BASE.plusSeconds(82),
                BASE.plusSeconds(79));
    }

    private static void seedShortConversation(AppStateRepository repository, long sessionId,
                                              String userText, String assistantText, Instant startedAt) {
        turn(repository, sessionId, 1, 1, userText, assistantText, startedAt);
    }

    private static void seedPendingConversation(AppStateRepository repository, long sessionId, Instant startedAt) {
        repository.insertConversationMessage(
                sessionId, "mcp-user-1", "user", 1, 1,
                "Add bearer-token support to the MCP server configuration.",
                null, null, true, true, false, startedAt);
        repository.insertConversationMessage(
                sessionId, "mcp-assistant-1", "assistant", 1, 2,
                "Thinking…",
                null, null, true, false, true,
                AGENT_ID, AGENT_NAME, MODEL_ID, THINKING,
                null, null, startedAt.plusSeconds(1));
    }

    private static long turn(AppStateRepository repository, long sessionId, long turnId, long firstSequence,
                             String userText, String assistantText, Instant startedAt) {
        repository.insertConversationMessage(
                sessionId,
                "session-" + sessionId + "-user-" + turnId,
                "user",
                turnId,
                firstSequence,
                userText,
                null,
                null,
                true,
                true,
                false,
                startedAt);

        Instant assistantStartedAt = startedAt.plusSeconds(1);
        Instant assistantCompletedAt = startedAt.plusSeconds(7);
        return repository.insertConversationMessage(
                sessionId,
                "session-" + sessionId + "-assistant-" + turnId,
                "assistant",
                turnId,
                firstSequence + 1,
                assistantText,
                null,
                null,
                true,
                true,
                false,
                AGENT_ID,
                AGENT_NAME,
                MODEL_ID,
                THINKING,
                null,
                assistantCompletedAt,
                assistantStartedAt);
    }

    private static String normalized(Path path) {
        return path.toAbsolutePath().normalize().toString();
    }

    public record FixturePaths(Path jupiterProject, Path docsWorkspace, Path mcpWorkspace,
                               Path blueCaveProject, Path websiteProject) {
    }

    public record Fixture(long activeProjectId, long activeWorkspaceId, long activeSessionId,
                          long subagentSessionId) {
    }

    private record ActiveConversation(long toolAssistantMessageId) {
    }

    private static final String SCREENSHOT_TEST_DIFF = """
            diff --git a/src/test/java/com/judepereira/jupiter/e2e/DocumentationScreenshotsTest.java b/src/test/java/com/judepereira/jupiter/e2e/DocumentationScreenshotsTest.java
            new file mode 100644
            index 0000000..9d6fbd2
            --- /dev/null
            +++ b/src/test/java/com/judepereira/jupiter/e2e/DocumentationScreenshotsTest.java
            @@ -0,0 +1,8 @@
            +class DocumentationScreenshotsTest {
            +    static final int DESKTOP_WIDTH = 1440;
            +    static final int DESKTOP_HEIGHT = 900;
            +    static final double DESKTOP_DPR = 2.0;
            +
            +    // Build the complete wiki screenshot catalog.
            +    // Every capture starts from deterministic fixture state.
            +}
            """;

    private static final String SCREENSHOT_FIXTURE_DIFF = """
            diff --git a/src/test/java/com/judepereira/jupiter/persistence/DocumentationScreenshotFixture.java b/src/test/java/com/judepereira/jupiter/persistence/DocumentationScreenshotFixture.java
            new file mode 100644
            index 0000000..d71b6ae
            --- /dev/null
            +++ b/src/test/java/com/judepereira/jupiter/persistence/DocumentationScreenshotFixture.java
            @@ -0,0 +1,5 @@
            +final class DocumentationScreenshotFixture {
            +    // Projects, worktrees, sessions, tool traces and settings all use
            +    // fixed IDs-by-insertion-order and timestamps.
            +}
            """;

    private static final String SCREENSHOT_SCRIPT_DIFF = """
            diff --git a/scripts/generate_screenshots.sh b/scripts/generate_screenshots.sh
            new file mode 100755
            index 0000000..94c3e4d
            --- /dev/null
            +++ b/scripts/generate_screenshots.sh
            @@ -0,0 +1,7 @@
            +#!/usr/bin/env bash
            +set -euo pipefail
            +
            +./mvnw -B -ntp exec:java -e \\
            +  -Dexec.classpathScope=test \\
            +  -Dexec.mainClass=com.microsoft.playwright.CLI \\
            +  -Dexec.args="install chromium"
            """;
}
