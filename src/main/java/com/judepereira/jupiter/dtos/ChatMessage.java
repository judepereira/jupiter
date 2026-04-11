package com.judepereira.jupiter.dtos;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.ai.chat.messages.Message;
import java.util.UUID;

@Data
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class ChatMessage {
    @EqualsAndHashCode.Include
    private final String id = UUID.randomUUID().toString();

    private Message message;

    private ToolCallTrace toolTrace;

    public ChatMessage(Message message) {
        this.message = message;
    }

    public ChatMessage(ToolCallTrace toolTrace) {
        this.toolTrace = toolTrace;
    }
}
