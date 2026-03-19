package com.judepereira.aide.task;

import com.judepereira.aide.dtos.ChatMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TaskConversationMemoryServiceTest {

    private TaskConversationMemoryService svc;

    @BeforeEach
    void setUp() {
        svc = new TaskConversationMemoryService();
    }

    @Test
    void normalizationAndDefensiveCopyBehaviors() {
        String slug = " MyTask ";
        List<ChatMessage> conv = new ArrayList<>();
        conv.add(new ChatMessage(new org.springframework.ai.chat.messages.UserMessage("hello")));

        svc.saveConversation(slug, conv);

        List<ChatMessage> loaded = svc.getConversation("mytask");
        assertEquals(1, loaded.size());

        // ensure defensive copy: modifying original list doesn't change stored
        conv.add(new ChatMessage(new org.springframework.ai.chat.messages.AssistantMessage("second")));
        List<ChatMessage> loaded2 = svc.getConversation(" mytask ");
        assertEquals(1, loaded2.size());

        // ensure returned list is unmodifiable
        assertThrows(UnsupportedOperationException.class, () -> loaded.add(new ChatMessage(new org.springframework.ai.chat.messages.AssistantMessage("x"))));

        // clear and ensure empty
        svc.clearConversation(" mytask ");
        assertTrue(svc.getConversation("mytask").isEmpty());
    }
}
