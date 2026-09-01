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

class DesktopSystemBalloonPositionE2ETest extends E2ETestSupport {

    @Test
    void desktopSystemBalloonStaysBetweenTopBarAndBottomRail(@TempDir Path tempDir) throws Exception {
        Path fakeHome = Files.createDirectories(tempDir.resolve("fake-home"));
        Path sqliteDbFile = tempDir.resolve("sqlite-db/jupiter.db");
        Files.createDirectories(sqliteDbFile.getParent());

        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", fakeHome.toString());

        try (RunningApp app = startApp(fakeHome, sqliteDbFile);
             BrowserContext context = newBrowserContext(new Browser.NewContextOptions()
                     .setViewportSize(new ViewportSize(1280, 720)))) {
            Page page = context.newPage();

            page.navigate(app.baseUrl());
            page.waitForLoadState();
            page.locator("#system-balloon-root").waitFor(new com.microsoft.playwright.Locator.WaitForOptions()
                    .setState(WaitForSelectorState.ATTACHED));
            addTestBalloon(page, "desktop-system-balloon", "Desktop system balloon",
                    "Verify the shared desktop top and bottom reservations.");

            var balloon = page.locator("#system-balloon-root .system-balloon");
            balloon.waitFor();
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
                            rootTop: rootRect.top,
                            rootBottom: rootRect.bottom,
                            balloonTop: balloonRect.top,
                            balloonBottom: balloonRect.bottom,
                            topBarBottom: document.querySelector('#top-bar').getBoundingClientRect().bottom,
                            bottomRailTop: document.querySelector('#bottom-rail').getBoundingClientRect().top
                        };
                    }
                    """);

            assertThat(geometry.get("mq480")).isEqualTo(Boolean.FALSE);
            double rootTop = ((Number) geometry.get("rootTop")).doubleValue();
            double rootBottom = ((Number) geometry.get("rootBottom")).doubleValue();
            double balloonTop = ((Number) geometry.get("balloonTop")).doubleValue();
            double balloonBottom = ((Number) geometry.get("balloonBottom")).doubleValue();
            double topBarBottom = ((Number) geometry.get("topBarBottom")).doubleValue();
            double bottomRailTop = ((Number) geometry.get("bottomRailTop")).doubleValue();
            assertThat(rootTop).isGreaterThan(topBarBottom);
            assertThat(rootBottom).isLessThan(bottomRailTop);
            assertThat(balloonTop).isGreaterThanOrEqualTo(rootTop);
            assertThat(balloonBottom).isLessThanOrEqualTo(rootBottom);
        } finally {
            if (previousHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", previousHome);
            }
        }
    }
}
