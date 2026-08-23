package com.judepereira.jupiter.e2e;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.AriaRole;
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

        try (Playwright playwright = Playwright.create(); Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
             RunningApp app = startApp(fakeHome, sqliteDbFile);
             BrowserContext context = browser.newContext()) {
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
}
