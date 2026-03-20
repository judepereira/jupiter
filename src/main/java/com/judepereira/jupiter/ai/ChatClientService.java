package com.judepereira.jupiter.ai;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.function.Function;

@Service
public class ChatClientService {

    private final ChatClient.Builder chatClientBuilder;

    public ChatClientService(ChatClient.Builder chatClientBuilder) {
        this.chatClientBuilder = chatClientBuilder;
    }

    public String getResponse(List<Message> chatHistory) {
        return chatClientBuilder
                .build()
                .prompt()
                .messages(chatHistory)
                .call()
                .content();
    }
}
