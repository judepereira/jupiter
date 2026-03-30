package com.judepereira.jupiter.db.repos;

import com.judepereira.jupiter.db.entities.Task;
import com.judepereira.jupiter.db.entities.Todo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
public class TodoService {

    private static final int MAX_TEXT = 1024;

    private final TodoRepository todoRepository;
    private final TaskRepository taskRepository;

    public TodoService(TodoRepository todoRepository, TaskRepository taskRepository) {
        this.todoRepository = todoRepository;
        this.taskRepository = taskRepository;
    }

    @Transactional(readOnly = true)
    public List<Todo> listTodos(String taskSlug) {
        String slug = safeTrimSlug(taskSlug);
        Task task = taskRepository.findBySlugIgnoreCase(slug)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskSlug));
        return todoRepository.findAllByTaskOrderByCreatedAtAsc(task);
    }

    @Transactional
    public Todo addTodo(String taskSlug, String text) {
        String t = normalizeText(text);
        if (t.isBlank()) {
            throw new IllegalArgumentException("Todo text is required and cannot be blank");
        }
        if (t.length() > MAX_TEXT) {
            throw new IllegalArgumentException("Todo text exceeds max length of " + MAX_TEXT);
        }

        String slug = safeTrimSlug(taskSlug);
        Task task = taskRepository.findBySlugIgnoreCase(slug)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskSlug));

        Todo todo = new Todo(task, t);
        return todoRepository.save(todo);
    }

    @Transactional
    public Todo completeTodo(String taskSlug, Long todoId) {
        String slug = safeTrimSlug(taskSlug);
        Task task = taskRepository.findBySlugIgnoreCase(slug)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskSlug));

        Todo todo = todoRepository.findByIdAndTask(todoId, task)
                .orElseThrow(() -> new IllegalArgumentException("Todo not found for id " + todoId + " and task " + taskSlug));

        todo.setCompletedAt(Instant.now());
        return todoRepository.save(todo);
    }

    @Transactional
    public Todo reopenTodo(String taskSlug, Long todoId) {
        String slug = safeTrimSlug(taskSlug);
        Task task = taskRepository.findBySlugIgnoreCase(slug)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskSlug));

        Todo todo = todoRepository.findByIdAndTask(todoId, task)
                .orElseThrow(() -> new IllegalArgumentException("Todo not found for id " + todoId + " and task " + taskSlug));

        todo.setCompletedAt(null);
        return todoRepository.save(todo);
    }

    private String normalizeText(String text) {
        return text == null ? "" : text.trim();
    }

    private String trimSlug(String slug) {
        return slug == null ? null : slug.trim();
    }

    private String safeTrimSlug(String slug) {
        if (slug == null || slug.trim().isEmpty()) {
            throw new IllegalArgumentException("Task slug is required");
        }
        return slug.trim();
    }
}
