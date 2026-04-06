package com.judepereira.jupiter.ui;

import com.judepereira.jupiter.ai.ChatClientService;
import com.judepereira.jupiter.ai.tools.FileTool;
import com.judepereira.jupiter.ai.tools.TodoTool;
import com.judepereira.jupiter.db.entities.Task;
import com.judepereira.jupiter.db.entities.Todo;
import com.judepereira.jupiter.db.repos.TodoService;
import lombok.Data;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Container for per-task UI and AI state.
 */
@Data
public class TaskContext {
    private final Task task;
    private final ChatClientService chatClientService;
    private final TodoService todoService;
    private final List<Object> tools = new ArrayList<>();

    public TaskContext(Task task, ChatClientService chatClientService, TodoService todoService) {
        this.task = task;
        this.chatClientService = chatClientService;
        this.todoService = todoService;
        this.tools.add(new TodoTool(todoService, task.getSlug()));
        this.tools.add(new FileTool(new File(task.getProjects().stream().findAny().get().getPath()).toPath()));
    }


    public List<Todo> listTodos() {
        return todoService.listTodos(task.getSlug());
    }

    public Todo addTodo(String text) {
        return todoService.addTodo(task.getSlug(), text);
    }

    public Todo completeTodo(Long id) {
        return todoService.completeTodo(task.getSlug(), id);
    }

    public Todo reopenTodo(Long id) {
        return todoService.reopenTodo(task.getSlug(), id);
    }
}
