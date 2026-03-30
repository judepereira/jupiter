package com.judepereira.jupiter.ui;

import com.judepereira.jupiter.ai.ChatClientService;
import com.judepereira.jupiter.db.repos.TodoService;
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
    private final TodoService todoService;

    public TaskContext(Task task, ChatClientService chatClientService, TodoService todoService) {
        this.task = task;
        this.chatClientService = chatClientService;
        this.todoService = todoService;
    }

    // Convenience helpers so UI can perform task-scoped todo operations without reaching into global state
    public java.util.List<com.judepereira.jupiter.db.entities.Todo> listTodos() {
        return todoService.listTodos(task.getSlug());
    }

    public com.judepereira.jupiter.db.entities.Todo addTodo(String text) {
        return todoService.addTodo(task.getSlug(), text);
    }

    public com.judepereira.jupiter.db.entities.Todo completeTodo(Long id) {
        return todoService.completeTodo(task.getSlug(), id);
    }

    public com.judepereira.jupiter.db.entities.Todo reopenTodo(Long id) {
        return todoService.reopenTodo(task.getSlug(), id);
    }
}
