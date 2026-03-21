package com.judepereira.jupiter.db.repos;

import com.judepereira.jupiter.db.entities.Conversation;
import com.judepereira.jupiter.dtos.ChatMessage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

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

    @Transactional(readOnly = true)
    public List<ChatMessage> getConversation(String slug) {
        String key = normalize(slug);
        return taskRepository.findBySlugIgnoreCase(key)
                .map(task -> {
                    List<Conversation> rows = conversationRepository.findByTaskOrderByCreatedAtAsc(task);
                    List<ChatMessage> out = new ArrayList<>(rows.size());
                    for (Conversation row : rows) {
                        String role = row.getRole();
                        String content = row.getContent();
                        if ("user".equalsIgnoreCase(role)) {
                            out.add(new ChatMessage(new UserMessage(content)));
                        } else {
                            // assistant (and unknown/default)
                            out.add(new ChatMessage(new AssistantMessage(content)));
                        }
                    }
                    return Collections.unmodifiableList(out);
                })
                .orElseGet(List::of);
    }

    @Transactional
    public void saveConversation(String slug, List<ChatMessage> conversation) {
        String key = normalize(slug);
        taskRepository.findBySlugIgnoreCase(key).ifPresent(task -> {
            // remove existing
            conversationRepository.deleteByTask(task);

            if (conversation == null || conversation.isEmpty()) {
                return;
            }

            Instant base = Instant.now();
            List<Conversation> toSave = new ArrayList<>(conversation.size());
            for (int i = 0; i < conversation.size(); i++) {
                ChatMessage cm = conversation.get(i);
                if (cm == null || cm.getMessage() == null) continue;
                String content = cm.getMessage().getText() == null ? "" : cm.getMessage().getText();
                String role;
                if (cm.getMessage() instanceof UserMessage) {
                    role = "user";
                } else if (cm.getMessage() instanceof AssistantMessage) {
                    role = "assistant";
                } else {
                    // default to assistant for unknown message types
                    role = "assistant";
                }
                Instant createdAt = base.plusNanos(i);
                toSave.add(new Conversation(task, role, content, createdAt));
            }
            conversationRepository.saveAll(toSave);
        });
    }

    @Transactional
    public void appendMessage(String slug, ChatMessage message) {
        if (message == null || message.getMessage() == null) return;
        String key = normalize(slug);
        taskRepository.findBySlugIgnoreCase(key).ifPresent(task -> {
            String content = message.getMessage().getText() == null ? "" : message.getMessage().getText();
            String role;
            if (message.getMessage() instanceof UserMessage) {
                role = "user";
            } else if (message.getMessage() instanceof AssistantMessage) {
                role = "assistant";
            } else {
                role = "assistant";
            }
            Conversation conv = new Conversation(task, role, content, Instant.now());
            conversationRepository.save(conv);
        });
    }
}
