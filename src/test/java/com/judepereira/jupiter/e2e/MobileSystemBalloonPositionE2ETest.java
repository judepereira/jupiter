package com.judepereira.jupiter.e2e;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.ViewportSize;
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
            page.locator("#system-balloon-root").waitFor();

            page.evaluate("""
                    () => {
                        const root = document.getElementById('system-balloon-root');
                        if (!root) {
                            throw new Error('Missing system balloon root');
                        }

                        const node = document.createElement('div');
                        node.className = 'system-balloon success is-visible';
                        node.dataset.balloonId = 'mobile-system-balloon';
                        node.dataset.type = 'success';

                        const content = document.createElement('div');
                        content.className = 'system-balloon__content';

                        const title = document.createElement('p');
                        title.className = 'system-balloon__title';
                        title.textContent = 'Mobile system balloon';
                        content.appendChild(title);

                        const body = document.createElement('p');
                        body.className = 'system-balloon__body';
                        body.textContent = 'Verify the mobile top offset and wrapping.';
                        content.appendChild(body);

                        const close = document.createElement('button');
                        close.type = 'button';
                        close.className = 'system-balloon__close';
                        close.setAttribute('aria-label', 'Close notification');
                        close.textContent = '×';

                        node.appendChild(content);
                        node.appendChild(close);
                        root.insertBefore(node, root.firstChild);
                    }
                    """);

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
                        const rootStyle = getComputedStyle(root);
                        return {
                            mq480: window.matchMedia('(max-width: 480px)').matches,
                            rootPosition: rootStyle.position,
                            rootRectTop: rootRect.top,
                            rootRectLeft: rootRect.left,
                            rootRectRight: window.innerWidth - rootRect.right,
                            rootRectBottom: window.innerHeight - rootRect.bottom,
                            balloonRectTop: balloonRect.top,
                            balloonRectLeft: balloonRect.left,
                            balloonRectRight: window.innerWidth - balloonRect.right,
                            balloonRectWidth: balloonRect.width,
                            balloonRectHeight: balloonRect.height,
                            rootRectWidth: rootRect.width
                        };
                    }
                    """);

            assertThat(geometry.get("mq480")).isEqualTo(Boolean.TRUE);
            assertThat(geometry.get("rootPosition")).isEqualTo("fixed");
            assertThat(((Number) geometry.get("rootRectTop")).doubleValue()).isBetween(48.0, 60.0);
            assertThat(((Number) geometry.get("rootRectLeft")).doubleValue()).isBetween(7.0, 12.0);
            assertThat(((Number) geometry.get("rootRectRight")).doubleValue()).isBetween(7.0, 12.0);
            assertThat(((Number) geometry.get("rootRectBottom")).doubleValue()).isBetween(48.0, 60.0);
            assertThat(((Number) geometry.get("balloonRectTop")).doubleValue()).isGreaterThanOrEqualTo(((Number) geometry.get("rootRectTop")).doubleValue());
            assertThat(((Number) geometry.get("balloonRectWidth")).doubleValue()).isLessThanOrEqualTo(((Number) geometry.get("rootRectWidth")).doubleValue() + 4.0);
            assertThat(((Number) geometry.get("balloonRectRight")).doubleValue()).isBetween(7.0, 12.0);
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
