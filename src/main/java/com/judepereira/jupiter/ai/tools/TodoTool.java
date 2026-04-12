package com.judepereira.jupiter.ai.tools;

import com.judepereira.jupiter.db.entities.Todo;
import com.judepereira.jupiter.db.repos.TodoService;
import org.springframework.ai.tool.annotation.Tool;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class TodoTool {

    private final TodoService todoService;
    private final String taskSlug;

    public TodoTool(TodoService todoService, String taskSlug) {
        this.todoService = todoService;
        this.taskSlug = taskSlug;
    }

    @Tool
    public String listTodos() {
        List<Todo> todos = todoService.listTodos(taskSlug);
        if (todos.isEmpty()) {
            return "No todos for task: " + taskSlug;
        }

        StringBuilder sb = new StringBuilder();
        AtomicInteger completed = new AtomicInteger();
        AtomicInteger index = new AtomicInteger(1);

        for (Todo t : todos) {
            boolean done = t.getCompletedAt() != null;
            if (done) {
                completed.incrementAndGet();
            }
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
        try {
            todoService.addTodo(taskSlug, text);
            return "Added";
        } catch (IllegalArgumentException ex) {
            return "Failed to add todo: " + ex.getMessage();
        }
    }

    @Tool
    public String completeTodo(Long todoId) {
        if (todoId == null) {
            return "todoId is required";
        }

        try {
            Todo t = todoService.completeTodo(taskSlug, todoId);
            return "Completed todo: #" + t.getId() + " - " + t.getText();
        } catch (IllegalArgumentException ex) {
            return "Failed to complete todo: " + ex.getMessage();
        }
    }

    @Tool
    public String reopenTodo(Long todoId) {
        if (todoId == null) {
            return "todoId is required";
        }

        try {
            Todo t = todoService.reopenTodo(taskSlug, todoId);
            return "Reopened todo: #" + t.getId() + " - " + t.getText();
        } catch (IllegalArgumentException ex) {
            return "Failed to reopen todo: " + ex.getMessage();
        }
    }
}
