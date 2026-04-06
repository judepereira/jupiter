package com.judepereira.jupiter.ai;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.core.io.ClassPathResource;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class ChatClientService {

    private static final String SYSTEM_PROMPT_RESOURCE = "prompts/jupiter-system-prompt.txt";

    private final ChatClient.Builder chatClientBuilder;
    private final String systemPrompt;

    public ChatClientService(ChatClient.Builder chatClientBuilder) {
        this.chatClientBuilder = chatClientBuilder;
        this.systemPrompt = loadSystemPrompt();
    }

    public Flux<String> streamResponse(List<Object> tools, List<Message> chatHistory) {
        var client = chatClientBuilder.build();
        var prompt = client.prompt()
                .system(systemPrompt)
                .messages(chatHistory);

        prompt.tools(tools.toArray(Object[]::new));

        return prompt.stream().content();
    }

    private String loadSystemPrompt() {
        var resource = new ClassPathResource(SYSTEM_PROMPT_RESOURCE);
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load system prompt resource: " + SYSTEM_PROMPT_RESOURCE, e);
        }
    }
}
