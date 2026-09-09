package com.judepereira.jupiter.e2e;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.ViewportSize;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class SlashCommandPickerE2ETest extends E2ETestSupport {

    @Test
    void slashCommandPickerIsAnchoredAboveChatInput(@TempDir Path tempDir) throws Exception {
        Path fakeHome = Files.createDirectories(tempDir.resolve("fake-home"));
        Path projectDir = Files.createDirectories(fakeHome.resolve("child-project"));
        Path sqliteDbFile = tempDir.resolve("sqlite-db/jupiter.db");
        Files.createDirectories(sqliteDbFile.getParent());
        Path screenshotsDir = Files.createDirectories(Path.of("target", "playwright-screenshots", "SlashCommandPickerE2ETest"));

        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", fakeHome.toString());

        try (RunningApp app = startApp(fakeHome, sqliteDbFile);
             BrowserContext context = newBrowserContext()) {
            Page page = context.newPage();

            page.navigate(app.baseUrl());
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("New tab")).waitFor();

            openProject(page, "Alpha", projectDir);

            page.locator("#chat-input").fill("/");

            var dialog = page.getByRole(AriaRole.DIALOG);
            dialog.waitFor();
            assertThat(dialog).isVisible();
            assertThat(page.locator(".command-modal-backdrop")).hasCount(0);
            page.locator(".command-modal-item").first().waitFor();

            var textareaBox = page.locator("#chat-input").boundingBox();
            var dialogBox = dialog.boundingBox();
            assertNotNull(textareaBox);
            assertNotNull(dialogBox);

            double dialogBottom = dialogBox.y + dialogBox.height;
            double textareaTop = textareaBox.y;
            assertThat(dialogBottom).isGreaterThanOrEqualTo(textareaTop - 24.0);
            assertThat(dialogBottom).isLessThanOrEqualTo(textareaTop + 4.0);
            assertThat(dialogBox.height).isLessThanOrEqualTo(404.0);

            captureScreenshot(page, screenshotsDir, "slash-command-picker.png");
        } finally {
            if (previousHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", previousHome);
            }
        }
    }

    @Test
    void slashCommandPickerRepositionsOnVisualViewportScrollAndRemovesListenersOnClose(@TempDir Path tempDir) throws Exception {
        Path fakeHome = Files.createDirectories(tempDir.resolve("fake-home"));
        Path projectDir = Files.createDirectories(fakeHome.resolve("child-project"));
        Path sqliteDbFile = tempDir.resolve("sqlite-db/jupiter.db");
        Files.createDirectories(sqliteDbFile.getParent());

        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", fakeHome.toString());

        try (RunningApp app = startApp(fakeHome, sqliteDbFile);
             BrowserContext context = newBrowserContext(new Browser.NewContextOptions()
                     .setViewportSize(new ViewportSize(390, 844))
                     .setIsMobile(true)
                     .setHasTouch(true))) {
            Page page = context.newPage();

            page.navigate(app.baseUrl());
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("New tab")).waitFor();
            openProject(page, "Alpha", projectDir);
            page.evaluate("""
                    () => {
                        const viewport = window.visualViewport;
                        if (!viewport) {
                            throw new Error('VisualViewport is not available');
                        }
                        const counts = {resize: 0, scroll: 0};
                        const listeners = {resize: [], scroll: []};
                        const originalAdd = viewport.addEventListener.bind(viewport);
                        const originalRemove = viewport.removeEventListener.bind(viewport);
                        viewport.addEventListener = (type, listener, options) => {
                            if (listeners[type]) {
                                listeners[type].push(listener);
                                counts[type]++;
                            }
                            return originalAdd(type, listener, options);
                        };
                        viewport.removeEventListener = (type, listener, options) => {
                            if (listeners[type]) {
                                const index = listeners[type].indexOf(listener);
                                if (index >= 0) {
                                    listeners[type].splice(index, 1);
                                    counts[type]--;
                                }
                            }
                            return originalRemove(type, listener, options);
                        };
                        window.__commandPickerVisualViewportListenerCounts = counts;
                    }
                    """);
            page.locator("#chat-input").fill("/");
            page.getByRole(AriaRole.DIALOG).waitFor();
            page.locator(".command-modal-item").first().waitFor();

            Number initialTop = (Number) page.locator("#command-modal").evaluate("element => parseFloat(element.style.top)");
            page.evaluate("""
                    () => {
                        document.querySelector('#chat-input').style.transform = 'translateY(-100px)';
                        window.visualViewport.dispatchEvent(new Event('scroll'));
                    }
                    """);
            page.waitForFunction("initialTop => parseFloat(document.querySelector('#command-modal').style.top) !== initialTop", initialTop);

            Number movedTop = (Number) page.locator("#command-modal").evaluate("element => parseFloat(element.style.top)");
            assertThat(movedTop).isNotEqualTo(initialTop);

            page.locator(".command-modal-input").press("Escape");
            assertThat(page.getByRole(AriaRole.DIALOG)).hasCount(0);
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> listenerCounts = (java.util.Map<String, Object>) page.evaluate("() => window.__commandPickerVisualViewportListenerCounts");
            assertThat(listenerCounts.get("resize")).isEqualTo(0);
            assertThat(listenerCounts.get("scroll")).isEqualTo(0);
        } finally {
            if (previousHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", previousHome);
            }
        }
    }

    @Test
    void slashCommandPickerRestoresChatInputFocusOnEscape(@TempDir Path tempDir) throws Exception {
        Path fakeHome = Files.createDirectories(tempDir.resolve("fake-home"));
        Path projectDir = Files.createDirectories(fakeHome.resolve("child-project"));
        Path sqliteDbFile = tempDir.resolve("sqlite-db/jupiter.db");
        Files.createDirectories(sqliteDbFile.getParent());

        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", fakeHome.toString());

        try (RunningApp app = startApp(fakeHome, sqliteDbFile);
             BrowserContext context = newBrowserContext()) {
            Page page = context.newPage();

            page.navigate(app.baseUrl());
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("New tab")).waitFor();

            openProject(page, "Alpha", projectDir);

            page.locator("#chat-input").fill("/");
            page.getByRole(AriaRole.DIALOG).waitFor();
            page.locator(".command-modal-input").press("Escape");

            assertThat(page.getByRole(AriaRole.DIALOG)).hasCount(0);
            assertThat(page.locator("#chat-input")).isFocused();
        } finally {
            if (previousHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", previousHome);
            }
        }
    }
}
