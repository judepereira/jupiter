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

        // Ensure streaming attributes and pending markers are present and form still posts to send
        assertThat(s).contains("data-stream-url", "data-pending", "class=\"chat-message-text\"")
                .contains("id=\"chat-send-form\"", "hx-post=\"/ui/chat/send\"", "hx-target=\"#chat-messages-list\"");
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
}
