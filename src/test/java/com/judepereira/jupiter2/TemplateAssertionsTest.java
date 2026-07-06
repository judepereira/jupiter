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

        // Ensure the messages list has the polling HTMX attributes when hasPending is true (static template contains the attributes)
        assertThat(s).contains("id=\"chat-messages-list\"", "hx-get=${hasPending} ? '/ui/chat/poll'", "hx-target=${hasPending} ? '#chat-messages-list'");

        // Ensure the form now targets the messages list rather than the container
        assertThat(s).contains("id=\"chat-send-form\"", "hx-post=\"/ui/chat/send\"", "hx-target=\"#chat-messages-list\"");
    }

    @Test
    public void appJs_contains_chat_listener_guards() throws Exception {
        Path p = Path.of("src/main/resources/static/app.js");
        String s = Files.readString(p);

        // Ensure we added a guard to avoid binding the htmx afterOnLoad listener repeatedly
        assertThat(s).contains("htmxAfterOnLoadBound", "htmx:beforeSwap", "wasNearBottomBeforeSwap");
    }
}
