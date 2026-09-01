package com.judepereira.jupiter.e2e;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.ViewportSize;
import com.microsoft.playwright.options.WaitForSelectorState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MobileSystemBalloonPositionE2ETest extends E2ETestSupport {

    @Test
    void mobileSystemBalloonStaysAnchoredBelowTopBarAndAboveBottomNav(@TempDir Path tempDir) throws Exception {
        Path fakeHome = Files.createDirectories(tempDir.resolve("fake-home"));
        Path sqliteDbFile = tempDir.resolve("sqlite-db/jupiter.db");
        Files.createDirectories(sqliteDbFile.getParent());
        Path screenshotsDir = Files.createDirectories(Path.of("target", "playwright-screenshots", "MobileSystemBalloonPositionE2ETest"));

        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", fakeHome.toString());

        try (RunningApp app = startApp(fakeHome, sqliteDbFile);
             BrowserContext context = newBrowserContext(new Browser.NewContextOptions()
                     .setViewportSize(new ViewportSize(390, 844))
                     .setIsMobile(true)
                     .setHasTouch(true))) {
            Page page = context.newPage();

            page.navigate(app.baseUrl());
            page.waitForLoadState();
            page.locator("#system-balloon-root").waitFor(new com.microsoft.playwright.Locator.WaitForOptions()
                    .setState(WaitForSelectorState.ATTACHED));

            addTestBalloon(page, "mobile-system-balloon", "Mobile system balloon",
                    "Verify the mobile top offset and wrapping.");

            var balloon = page.locator("#system-balloon-root .system-balloon");
            balloon.waitFor();
            assertThat(balloon.isVisible()).isTrue();
            assertThat(balloon.textContent()).contains("Mobile system balloon");
            assertThat(balloon.textContent()).contains("Verify the mobile top offset and wrapping.");
            page.waitForFunction("() => { const balloon = document.querySelector('#system-balloon-root .system-balloon'); return balloon && getComputedStyle(balloon).opacity === '1'; }");

            @SuppressWarnings("unchecked")
            Map<String, Object> geometry = (Map<String, Object>) page.evaluate("""
                    () => {
                        const root = document.getElementById('system-balloon-root');
                        const balloon = document.querySelector('#system-balloon-root .system-balloon');
                        const rootRect = root.getBoundingClientRect();
                        const balloonRect = balloon.getBoundingClientRect();
                        return {
                            mq480: window.matchMedia('(max-width: 480px)').matches,
                            rootRectTop: rootRect.top,
                            rootRectLeft: rootRect.left,
                            rootRectRight: window.innerWidth - rootRect.right,
                            rootRectBottom: window.innerHeight - rootRect.bottom,
                            rootRectBottomEdge: rootRect.bottom,
                            balloonRectTop: balloonRect.top,
                            balloonRectBottomEdge: balloonRect.bottom,
                            balloonRectLeft: balloonRect.left,
                            balloonRectRight: window.innerWidth - balloonRect.right,
                            balloonRectWidth: balloonRect.width,
                            balloonRectHeight: balloonRect.height,
                            rootRectWidth: rootRect.width,
                            topBarBottom: document.querySelector('#top-bar').getBoundingClientRect().bottom,
                            bottomRailTop: document.querySelector('#bottom-rail').getBoundingClientRect().top
                        };
                    }
                    """);

            assertThat(geometry.get("mq480")).isEqualTo(Boolean.TRUE);
            double rootTop = ((Number) geometry.get("rootRectTop")).doubleValue();
            double rootBottom = ((Number) geometry.get("rootRectBottom")).doubleValue();
            double balloonTop = ((Number) geometry.get("balloonRectTop")).doubleValue();
            double rootBottomEdge = ((Number) geometry.get("rootRectBottomEdge")).doubleValue();
            double balloonBottomEdge = ((Number) geometry.get("balloonRectBottomEdge")).doubleValue();
            double topBarBottom = ((Number) geometry.get("topBarBottom")).doubleValue();
            double bottomRailTop = ((Number) geometry.get("bottomRailTop")).doubleValue();
            assertThat(rootTop).isGreaterThan(topBarBottom);
            assertThat(rootBottom).isGreaterThan(0.0);
            assertThat(rootBottomEdge).isLessThan(bottomRailTop);
            assertThat(balloonTop).isGreaterThanOrEqualTo(rootTop);
            assertThat(balloonBottomEdge).isLessThanOrEqualTo(rootBottomEdge);
            assertThat(((Number) geometry.get("balloonRectWidth")).doubleValue()).isLessThanOrEqualTo(((Number) geometry.get("rootRectWidth")).doubleValue());
            assertThat(((Number) geometry.get("balloonRectRight")).doubleValue()).isGreaterThanOrEqualTo(((Number) geometry.get("rootRectRight")).doubleValue());
            assertThat(((Number) geometry.get("balloonRectLeft")).doubleValue())
                    .isGreaterThanOrEqualTo(((Number) geometry.get("rootRectLeft")).doubleValue());
            assertThat(((Number) geometry.get("balloonRectHeight")).doubleValue()).isGreaterThan(0.0);

            captureScreenshot(page, screenshotsDir, "mobile-system-balloon.png");
        } finally {
            if (previousHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", previousHome);
            }
        }
    }
}
