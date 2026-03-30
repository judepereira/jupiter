package com.judepereira.jupiter.ai;

import com.judepereira.jupiter.db.entities.Task;
import com.judepereira.jupiter.db.entities.Todo;
import com.judepereira.jupiter.db.repos.TodoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TodoToolProviderTest {

    private TodoService svc;

    @BeforeEach
    void setUp() {
        svc = mock(TodoService.class);
    }

    @Test
    void nonScoped_provider_requiresContext() {
        TodoToolProvider provider = new TodoToolProvider(svc);
        String list = provider.listTodos();
        assertTrue(list.contains("Task context required"));

        String add = provider.addTodo("x");
        assertTrue(add.contains("Task context required"));

        String c = provider.completeTodo(1L);
        assertTrue(c.contains("Task context required"));

        String r = provider.reopenTodo(1L);
        assertTrue(r.contains("Task context required"));
    }

    @Test
    void scoped_provider_delegates_and_formats() {
        Todo t1 = new Todo(new Task("T","ts",null), "one");
        t1.setId(5L);
        Todo t2 = new Todo(new Task("T","ts",null), "two");
        t2.setId(6L);
        t2.setCompletedAt(Instant.now());

        when(svc.listTodos("ts")).thenReturn(List.of(t1,t2));
        when(svc.addTodo("ts"," new ")).thenReturn(new Todo(new Task("T","ts",null), "new"));
        Todo added = new Todo(new Task("T","ts",null), "new"); added.setId(9L);
        when(svc.addTodo("ts","trimmed")).thenReturn(added);
        when(svc.completeTodo("ts", 5L)).thenReturn(t1);
        when(svc.reopenTodo("ts", 6L)).thenReturn(t2);

        TodoToolProvider provider = new TodoToolProvider(svc, " ts ");

        String listOut = provider.listTodos();
        assertTrue(listOut.contains("1."));
        assertTrue(listOut.contains("2."));
        assertTrue(listOut.contains("Summary:"));

        // add: simulate service throwing for invalid text
        when(svc.addTodo("ts","bad")).thenThrow(new IllegalArgumentException("bad text"));
        String addFail = provider.addTodo("bad");
        assertTrue(addFail.startsWith("Failed to add todo:"));

        // successful add uses trimmed slug and text
        when(svc.addTodo("ts","ok")).thenReturn(added);
        String addOk = provider.addTodo("ok");
        assertTrue(addOk.contains("Added todo:"));

        String completeOut = provider.completeTodo(5L);
        assertTrue(completeOut.contains("Completed todo:"));

        String reopenOut = provider.reopenTodo(6L);
        assertTrue(reopenOut.contains("Reopened todo:"));
    }

    @Test
    void tool_handles_service_errors_gracefully() {
        TodoToolProvider provider = new TodoToolProvider(svc, "s");
        when(svc.completeTodo("s", 1L)).thenThrow(new IllegalArgumentException("no todo"));
        String res = provider.completeTodo(1L);
        assertTrue(res.startsWith("Failed to complete todo:"));

        when(svc.reopenTodo("s", 2L)).thenThrow(new IllegalArgumentException("no todo"));
        String r = provider.reopenTodo(2L);
        assertTrue(r.startsWith("Failed to reopen todo:"));
    }
}
