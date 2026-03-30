package com.judepereira.jupiter.ai;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Service
public class ChatClientService {

    private static final String SYSTEM_PROMPT_RESOURCE = "prompts/jupiter-system-prompt.txt";

    private final ChatClient.Builder chatClientBuilder;
    private final ToolFileProvider toolFileProvider;
    private final String systemPrompt;

    public ChatClientService(ChatClient.Builder chatClientBuilder, ToolFileProvider toolFileProvider) {
        this.chatClientBuilder = chatClientBuilder;
        this.toolFileProvider = toolFileProvider;
        this.systemPrompt = loadSystemPrompt();
    }

    /**
     * Create a task-scoped ChatClientService whose ToolFileProvider is limited to the
     * supplied project paths. This returns a lightweight wrapper that uses the same
     * ChatClient.Builder but a task-scoped ToolFileProvider instance.
     */
    public ChatClientService forProjectPaths(List<String> projectPaths) {
        var scopedProvider = new ToolFileProvider(projectPaths);
        return new ChatClientService(this.chatClientBuilder, scopedProvider);
    }

    public String getResponse(List<Message> chatHistory) {
        var client = chatClientBuilder.build();
        var prompt = client.prompt()
                .system(systemPrompt)
                .messages(chatHistory);

        // Register tools so the model can call them
        prompt.tools(toolFileProvider);

        return prompt.call().content();
    }

    public Flux<String> streamResponse(List<Message> chatHistory) {
        var client = chatClientBuilder.build();
        var prompt = client.prompt()
                .system(systemPrompt)
                .messages(chatHistory);

        // Register tools so the model can call them
        prompt.tools(toolFileProvider);

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
