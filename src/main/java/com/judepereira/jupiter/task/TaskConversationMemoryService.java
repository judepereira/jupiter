package com.judepereira.jupiter.task;

import com.judepereira.jupiter.dtos.ChatMessage;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class TaskConversationMemoryService {

    private final Map<String, List<ChatMessage>> store = new ConcurrentHashMap<>();

    private String normalize(String slug) {
        return Objects.requireNonNull(slug, "Slug cannot be null").trim().toLowerCase(Locale.ENGLISH);
    }

    public List<ChatMessage> getConversation(String slug) {
        String key = normalize(slug);
        List<ChatMessage> list = store.get(key);
        return list == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(list));
    }

    public void saveConversation(String slug, List<ChatMessage> conversation) {
        String key = normalize(slug);
        // store a defensive copy
        store.put(key, new ArrayList<>(conversation == null ? List.of() : conversation));
    }

    public void clearConversation(String slug) {
        String key = normalize(slug);
        store.remove(key);
    }
}
