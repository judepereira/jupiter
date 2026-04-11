package com.judepereira.jupiter.ai;

import com.judepereira.jupiter.ai.tools.ToolUtils;
import com.judepereira.jupiter.dtos.ToolCallTrace;
import com.judepereira.jupiter.shell.Shell;
import com.judepereira.jupiter.shell.Shell.ExecutionResult;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.core.io.ClassPathResource;
import reactor.core.publisher.Flux;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Consumer;

public class ChatClientService {

    private static final String SYSTEM_PROMPT_RESOURCE = "prompts/jupiter-system-prompt.txt";

    private final ChatClient.Builder chatClientBuilder;
    private final String systemPrompt;

    public ChatClientService(ChatClient.Builder chatClientBuilder) {
        this.chatClientBuilder = chatClientBuilder;
        this.systemPrompt = loadSystemPrompt();
    }

    public Flux<String> streamResponse(List<Object> tools, List<Message> chatHistory, String projectRoot,
                                       Consumer<ToolCallTrace> traceConsumer) {
        if (projectRoot == null || projectRoot.isBlank()) {
            throw new IllegalArgumentException("projectRoot must be provided");
        }

        var projectDir = new File(projectRoot);
        if (!projectDir.exists() || !projectDir.isDirectory()) {
            throw new IllegalArgumentException("projectRoot must point to an existing directory: " + projectRoot);
        }

        boolean gitRepo = isGitRepo(projectDir);

        String runtimeInfo = """
                Here's some useful information about the environment you are running in:
                  Working directory: %s
                  Git repo detected: %s
                  Platform: %s
                  Today's date: %s
                """.formatted(projectRoot, gitRepo ? "yes" : "no",
                System.getProperty("os.name"), LocalDateTime.now());

        var effectiveSystemPrompt = systemPrompt + "\n\n" + runtimeInfo;

        var client = chatClientBuilder.build();
        var prompt = client.prompt()
                .system(effectiveSystemPrompt)
                .messages(chatHistory)
                .tools(ToolUtils.wrap(tools, traceConsumer));

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

    private boolean isGitRepo(File projectDir) {
        try {
            ExecutionResult res = Shell.execute(projectDir, "git", "status");
            return res.getExitCode() == 0;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to determine git repository status for: " + projectDir, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while checking git repository status for: " + projectDir, e);
        }
    }
}
