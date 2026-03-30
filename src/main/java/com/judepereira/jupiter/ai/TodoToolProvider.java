package com.judepereira.jupiter.ai;

import com.judepereira.jupiter.db.entities.Todo;
import com.judepereira.jupiter.db.repos.TodoService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Exposes todo-related operations to the AI as tools. Can be created as an
 * application-level bean (no task context) or as a package-visible task-scoped
 * instance bound to a specific task slug.
 */
@Component
public class TodoToolProvider {

    private final TodoService todoService;
    private final String taskSlug; // null for app-level (non-scoped) instance

    // Application-level bean constructor
    public TodoToolProvider(TodoService todoService) {
        this.todoService = todoService;
        this.taskSlug = null;
    }

    // Package-visible constructor for task-scoped usage
    TodoToolProvider(TodoService todoService, String taskSlug) {
        this.todoService = todoService;
        this.taskSlug = taskSlug == null ? null : taskSlug.trim();
    }

    // package-visible accessor for creating scoped instances
    TodoService todoService() {
        return this.todoService;
    }

    private boolean isScoped() {
        return this.taskSlug != null && !this.taskSlug.isBlank();
    }

    @Tool
    public String listTodos() {
        if (!isScoped()) {
            return "Task context required: this tool must be used when bound to a task-scoped instance.";
        }
        List<Todo> todos = todoService.listTodos(taskSlug);
        if (todos.isEmpty()) {
            return "No todos for task: " + taskSlug;
        }

        StringBuilder sb = new StringBuilder();
        AtomicInteger completed = new AtomicInteger();
        AtomicInteger index = new AtomicInteger(1);

        for (Todo t : todos) {
            boolean done = t.getCompletedAt() != null;
            if (done) completed.incrementAndGet();
            sb.append(index.getAndIncrement()).append(". ")
                    .append("[").append(done ? "x" : " ").append("] ")
                    .append("#").append(t.getId()).append(" ")
                    .append(t.getText()).append("\n");
        }

        int total = todos.size();
        int comp = completed.get();
        int open = total - comp;
        sb.append("\nSummary: Total=").append(total).append(", Completed=").append(comp).append(", Open=").append(open);
        return sb.toString();
    }

    @Tool
    public String addTodo(String text) {
        if (!isScoped()) {
            return "Task context required: cannot add todo without a task-scoped instance.";
        }
        try {
            Todo created = todoService.addTodo(taskSlug, text);
            return "Added todo: #" + created.getId() + " - " + created.getText();
        } catch (IllegalArgumentException ex) {
            return "Failed to add todo: " + ex.getMessage();
        }
    }

    @Tool
    public String completeTodo(Long todoId) {
        if (!isScoped()) {
            return "Task context required: cannot complete todo without a task-scoped instance.";
        }
        if (todoId == null) return "todoId is required";
        try {
            Todo t = todoService.completeTodo(taskSlug, todoId);
            return "Completed todo: #" + t.getId() + " - " + t.getText();
        } catch (IllegalArgumentException ex) {
            return "Failed to complete todo: " + ex.getMessage();
        }
    }

    @Tool
    public String reopenTodo(Long todoId) {
        if (!isScoped()) {
            return "Task context required: cannot reopen todo without a task-scoped instance.";
        }
        if (todoId == null) return "todoId is required";
        try {
            Todo t = todoService.reopenTodo(taskSlug, todoId);
            return "Reopened todo: #" + t.getId() + " - " + t.getText();
        } catch (IllegalArgumentException ex) {
            return "Failed to reopen todo: " + ex.getMessage();
        }
    }
}
