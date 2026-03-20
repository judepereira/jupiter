package com.judepereira.jupiter.dtos;

import lombok.Data;
import org.springframework.ai.chat.messages.Message;

import java.util.UUID;

@Data
public class ChatMessage {
    private final String id = UUID.randomUUID().toString();
    private final Message message;
}
