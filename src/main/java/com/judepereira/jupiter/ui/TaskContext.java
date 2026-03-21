package com.judepereira.jupiter.ui;

import com.judepereira.jupiter.ai.ChatClientService;
import com.judepereira.jupiter.db.entities.Task;
import com.judepereira.jupiter.dtos.ChatMessage;
import com.judepereira.jupiter.ui.components.ChatComposer;
import lombok.Data;

import java.util.function.Consumer;
import java.util.function.BooleanSupplier;

/**
 * Container for per-task UI and AI state.
 */
@Data
public class TaskContext {
    private final Task task;
    private final ChatClientService chatClientService;

    public TaskContext(Task task, ChatClientService chatClientService, Consumer<ChatMessage> onMessageAdded, BooleanSupplier isActive) {
        this.task = task;
        this.chatClientService = chatClientService;
    }
}
