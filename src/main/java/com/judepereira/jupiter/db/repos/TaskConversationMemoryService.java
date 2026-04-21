package com.judepereira.jupiter.db.repos;

import com.judepereira.jupiter.db.entities.Conversation;
import com.judepereira.jupiter.dtos.ChatMessage;
import com.judepereira.jupiter.dtos.ToolCallTrace;
import com.judepereira.jupiter.ui.TaskContext;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

@Service
public class TaskConversationMemoryService {

    private final TaskRepository taskRepository;
    private final ConversationRepository conversationRepository;

    public TaskConversationMemoryService(TaskRepository taskRepository, ConversationRepository conversationRepository) {
        this.taskRepository = taskRepository;
        this.conversationRepository = conversationRepository;
    }

    private String normalize(String slug) {
        return Objects.requireNonNull(slug, "Slug cannot be null").trim().toLowerCase(Locale.ENGLISH);
    }

    public List<ChatMessage> getConversation(TaskContext tc) {
        String key = normalize(tc.getTask().getSlug());
        return taskRepository.findBySlugIgnoreCase(key)
                .map(task -> {
                    List<Conversation> rows = conversationRepository.findByTaskOrderByCreatedAtAscIdAsc(task);
                    List<ChatMessage> out = new ArrayList<>(rows.size());
                    for (Conversation row : rows) {
                        String role = row.getRole();
                        String content = row.getContent();
                        if ("user".equalsIgnoreCase(role)) {
                            out.add(new ChatMessage(new UserMessage(content), tc));
                        } else {
                            if (row.getRole().equalsIgnoreCase("tool")) {
                                out.add(new ChatMessage(new ToolCallTrace(
                                        row.getToolName(), row.getToolArgsPayload(), row.getToolResultPayload(),
                                        row.getToolErrorPayload(), row.getToolStartedAt(), row.getToolDurationMillis()), tc));
                            } else {
                                out.add(new ChatMessage(new AssistantMessage(content), tc));
                            }
                        }
                    }
                    return Collections.unmodifiableList(out);
                })
                .orElseGet(List::of);
    }

    public void appendMessage(String slug, ChatMessage message) {
        if (message == null || (message.getMessage() == null && message.getToolTrace() == null)) {
            return;
        }
        String key = normalize(slug);
        taskRepository.findBySlugIgnoreCase(key).ifPresent(task -> {
            String role;
            String content = "";
            if (message.getToolTrace() != null) {
                role = "tool";
                content = "";
            } else {
                if (message.getMessage() != null) {
                    content = message.getMessage().getText() == null ? "" : message.getMessage().getText();
                }
                if (message.getMessage() instanceof UserMessage) {
                    role = "user";
                } else if (message.getMessage() instanceof AssistantMessage) {
                    role = "assistant";
                } else {
                    role = "assistant";
                }
            }

            Conversation conv;
            if (message.getToolTrace() != null) {
                var t = message.getToolTrace();
                Instant ts = t.startedAt() == null ? Instant.now() : t.startedAt();
                Long duration = t.durationMillis() == null ? null : t.durationMillis();
                Instant createdAt = ts == null ? Instant.now() : ts;
                conv = new Conversation(task, role, content, createdAt, t.toolName(), t.toolArgsPayload(),
                        t.toolResultPayload(), t.toolErrorPayload(), ts, duration);
            } else {
                conv = new Conversation(task, role, content, Instant.now());
            }
            conversationRepository.save(conv);
        });
    }
}
