package com.judepereira.jupiter2;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

public class TemplateAssertionsTest {

    @Test
    public void chatTemplateTargetsMessagesList_forPollingAndForm() throws Exception {
        Path p = Path.of("src/main/resources/templates/fragments/chat.html");
        String s = Files.readString(p);
        // Ensure no polling attributes remain (streaming-only)
        assertThat(s).doesNotContain("/ui/chat/poll", "every 1s", "hx-get")
                .contains("id=\"chat-messages-list\"");

        // Ensure the standalone row fragment was removed to avoid evaluation when m is null
        assertThat(s).doesNotContain("th:fragment=\"row\"");

        // Ensure streaming attributes and pending markers are present and form still posts to send
        // New behavior: form appends to the messages list (hx-swap="beforeend") targeting #chat-messages-list
        assertThat(s).contains("data-stream-url", "data-pending", "class=\"chat-message-text\"")
                .contains("id=\"chat-send-form\"", "hx-post=\"/ui/chat/send\"", "hx-target=\"#chat-messages-list\"")
                .contains("hx-swap=\"beforeend\"");
    }

    @Test
    public void appJs_contains_chat_listener_guards() throws Exception {
        Path p = Path.of("src/main/resources/static/app.js");
        String s = Files.readString(p);

        // Ensure we added a guard to avoid binding the htmx afterOnLoad listener repeatedly
        // Key behaviors to prevent streaming regressions:
        // - JSON.parse is used for payloads
        // - payload.text != null checks preserve whitespace-only deltas
        // - a bounded flush interval (setTimeout) exists
        // - flushBuffer is invoked before final replacement on done
        assertThat(s).contains("EventSource", "bindPendingStreams", "requestAnimationFrame", "streamBound", "htmx:beforeSwap",
                "JSON.parse", "payload.text != null", "setTimeout", "flushBuffer",
                // Streaming auto-scroll: live state and listener lifecycle
                "shouldStickToBottom", "STREAM_BOTTOM_THRESHOLD_PX", "addEventListener('scroll'", "removeEventListener('scroll'", "stickBeforeFlush");
    }

    @Test
    public void markdown_support_present_in_templates_and_assets() throws Exception {
        Path idx = Path.of("src/main/resources/templates/index.html");
        String index = Files.readString(idx);

        // index.html should include DOMPurify and marked and load them before /app.js
        int iDom = index.indexOf("dompurify");
        int iMarked = index.indexOf("marked");
        int iApp = index.indexOf("/app.js");
        assertThat(iDom).as("dompurify present").isGreaterThanOrEqualTo(0);
        assertThat(iMarked).as("marked present").isGreaterThanOrEqualTo(0);
        assertThat(iApp).as("app.js present").isGreaterThanOrEqualTo(0);
        assertThat(iDom).isLessThan(iApp);
        assertThat(iMarked).isLessThan(iApp);

        // app.js should expose markdown helpers and use DOMPurify/marked and dataset.rawMarkdown
        Path app = Path.of("src/main/resources/static/app.js");
        String appJs = Files.readString(app);
        assertThat(appJs).contains("renderChatMarkdown", "renderAllChatMarkdown", "getRawChatMarkdown",
                "DOMPurify.sanitize", "marked.parse", "dataset.rawMarkdown");
        // additional runtime marker and live-list re-render after HTMX swaps
        assertThat(appJs).contains("markdown-rendered", "renderAllChatMarkdown(base)", "liveList");

        // CSS should include markdown styling for chat messages (lightweight check)
        Path css = Path.of("src/main/resources/static/app.css");
        String cssS = Files.readString(css);
        // Ensure we only apply pre-wrap to non-markdown-rendered spans and avoid the old broad selector
        assertThat(cssS).contains(".chat-message-text:not(.markdown-rendered)")
                .doesNotContain("#chat-messages-list li span");
    }

    @Test
    public void chatResponse_emits_only_rows_and_uses_fragment_param_syntax() throws Exception {
        Path p = Path.of("src/main/resources/templates/fragments/chat-response.html");
        String s = Files.readString(p);

        // Ensure we do not emit a wrapper <div> or nested <ul> for appended rows
        assertThat(s).doesNotContain("<div>", "chat-messages-list-new", "<ul")
                // ensure outdated fragment invocation syntax is not present
                .doesNotContain("row(m=${m})")
                .doesNotContain("fragments/chat :: row");
    }
}
