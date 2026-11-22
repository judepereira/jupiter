package com.judepereira.jupiter.e2e;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.ViewportSize;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ContextUsagePreviewTest {
    @Test
    void rendersApplicationChatFragmentWithContextUsage() throws Exception {
        Path output = Path.of("target/context-usage-preview.png");
        Files.createDirectories(output.getParent());
        String fragment = Files.readString(Path.of("src/main/resources/templates/fragments/chat.html"));
        String start = "                <div\n                    id=\"session-context-usage\"";
        int from = fragment.indexOf(start);
        int to = fragment.indexOf("                </div>", from) + "                </div>".length();
        assertTrue(from >= 0 && to > from, "context usage fragment must exist in chat.html");
        String markup = fragment.substring(from, to).replace("th:text=", "data-th-text=");
        String css = Files.readString(Path.of("src/main/resources/static/css/chat.css"));
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
            try (BrowserContext context = browser
                    .newContext(new Browser.NewContextOptions().setViewportSize(new ViewportSize(920, 240)))) {
                Page page = context.newPage();
                page.setContent("<html><head><style>" + css + "</style></head><body>" + markup + "</body></html>");
                page.locator("[data-context-ring]").evaluate(
                        "el => { el.style.setProperty('--usage-angle', '140.4deg'); el.setAttribute('aria-label', 'Context usage 39%'); }");
                assertTrue(page.locator("[aria-label='Context usage 39%']").isVisible());
                assertTrue(page.locator("#session-context-usage").innerText().isEmpty());
                page.screenshot(new Page.ScreenshotOptions().setPath(output));
            } finally {
                browser.close();
            }
        }
        assertTrue(Files.size(output) > 0);
    }
}
