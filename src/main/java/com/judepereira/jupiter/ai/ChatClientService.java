package com.judepereira.jupiter.ai;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ChatClientService {

    private final ChatClient.Builder chatClientBuilder;
    private final ToolFileProvider toolFileProvider;

    public ChatClientService(ChatClient.Builder chatClientBuilder, ToolFileProvider toolFileProvider) {
        this.chatClientBuilder = chatClientBuilder;
        this.toolFileProvider = toolFileProvider;
    }

    public String getResponse(List<Message> chatHistory) {
        var client = chatClientBuilder.build();
        var prompt = client.prompt()
                .messages(chatHistory);

        // Register tools so the model can call them
        prompt.tools(toolFileProvider);

        return prompt.call().content();
    }
}
