package com.judepereira.jupiter.e2e;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.ViewportSize;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class MobileSettingsE2ETest extends E2ETestSupport {

    @Test
    void settingsStacksNavigationAboveContentAndSwitchesTabsOnMobile(@TempDir Path tempDir) throws Exception {
        Path fakeHome = Files.createDirectories(tempDir.resolve("fake-home"));
        Path projectDir = Files.createDirectories(fakeHome.resolve("child-project"));
        Path sqliteDbFile = tempDir.resolve("sqlite-db/jupiter.db");
        Files.createDirectories(sqliteDbFile.getParent());

        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", fakeHome.toString());

        try (Playwright playwright = Playwright.create();
             Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
             RunningApp app = startApp(fakeHome, sqliteDbFile);
             BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                     .setViewportSize(new ViewportSize(390, 844))
                     .setIsMobile(true)
                     .setHasTouch(true))) {
            Page page = context.newPage();

            page.navigate(app.baseUrl());
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("New tab")).waitFor();
            openProject(page, "Alpha", projectDir);

            page.waitForResponse(
                    response -> response.url().contains("/ui/settings") && response.status() == 200,
                    () -> page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Settings")).click());
            com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator("#settings-modal")).isVisible();
            assertThat((Boolean) page.evaluate("() => window.matchMedia('(max-width: 767.98px)').matches")).isTrue();

            var navigationColumn = page.locator("#settings-modal .settings-modal-grid > .col-12.col-md-3").boundingBox();
            var contentColumn = page.locator("#settings-modal .settings-modal-grid > .col-12.col-md-9").boundingBox();
            assertThat(navigationColumn).isNotNull();
            assertThat(contentColumn).isNotNull();
            assertThat(navigationColumn.y + navigationColumn.height).isLessThanOrEqualTo(contentColumn.y);

            com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator("#settings-current-project-tab[aria-selected='true']")).hasCount(1);
            com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator("#settings-current-project")).isVisible();

            page.locator("#settings-model-providers-tab").click();
            com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator("#settings-model-providers-tab.active")).hasCount(1);
            com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator("#settings-model-providers.show")).hasCount(1);
            com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator("#settings-model-providers")).isVisible();
            com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator("#settings-current-project")).not().isVisible();

            page.locator("#settings-help-tab").click();
            com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator("#settings-help-tab.active")).hasCount(1);
            com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator("#settings-help.show")).hasCount(1);
            com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator("#settings-help")).isVisible();
            com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator("#settings-model-providers")).not().isVisible();
        } finally {
            if (previousHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", previousHome);
            }
        }
    }
}
