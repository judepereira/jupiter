package com.judepereira.jupiter.e2e;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StreamRetryBufferE2ETest extends E2ETestSupport {

    @Test
    void retryDiscardsPendingDeltaAndPreservesToolRows(@TempDir Path tempDir) throws Exception {
        Path home = Files.createDirectories(tempDir.resolve("home"));
        Path db = tempDir.resolve("db/jupiter.db");
        Files.createDirectories(db.getParent());
        try (RunningApp app = startApp(home, db); BrowserContext context = newBrowserContext()) {
            Page page = context.newPage();
            page.navigate(app.baseUrl());
            page.evaluate(
                    """
                                () => {
                                const list = document.getElementById('chat-messages-list');
                                const row = document.createElement('li');
                                row.dataset.id = 'retry-fixture';
                                row.dataset.pending = 'true';
                                row.dataset.streamUrl = '/test-stream';
                                row.dataset.role = 'assistant';
                                row.innerHTML = '<span class="chat-message-text">prior </span><span class="chat-retry-status" hidden></span><div class="tool-call-bundle">tool stays</div>';
                                list.appendChild(row);
                                class FakeEventSource {
                                    static instance;
                                    constructor() { FakeEventSource.instance = this; this.listeners = {}; }
                                    addEventListener(name, callback) { this.listeners[name] = callback; }
                                    close() {}
                                    emit(name, data) { this.listeners[name]({ data: JSON.stringify(data) }); }
                                }
                                window.EventSource = FakeEventSource;
                                window.__bindRetryFixture = async () => {
                                    const { bindPendingStreams } = await import('/js/chat/streams/bind.js');
                                    bindPendingStreams();
                                };
                            }""");
            page.evaluate("() => window.__bindRetryFixture()");
            page.waitForFunction("() => window.EventSource.instance");
            page.evaluate("""
                        () => {
                        const source = window.EventSource.instance;
                        source.emit('delta', { text: 'discarded' });
                        source.emit('retry', { text: 'prior ', attempt: 1, delayMillis: 0 });
                    }""");
            var text = page.locator("#chat-messages-list [data-id='retry-fixture'] .chat-message-text");
            assertThat(text).hasText("prior ");
            var tool = page.locator("#chat-messages-list [data-id='retry-fixture'] .tool-call-bundle");
            assertThat(tool).hasText("tool stays");
            var status = page.locator("#chat-messages-list [data-id='retry-fixture'] .chat-retry-status");
            assertThat(status).isVisible();
            page.evaluate("() => window.EventSource.instance.emit('delta', { text: 'final' })");
            assertThat(status).not().isVisible();
            page.evaluate("() => window.EventSource.instance.emit('done', { text: 'prior final' })");
            assertThat(text).hasText("prior final");
            assertThat(tool).hasText("tool stays");
            assertThat(status).not().isVisible();
        }
    }
}
