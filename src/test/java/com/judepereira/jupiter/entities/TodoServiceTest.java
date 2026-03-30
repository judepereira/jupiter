package com.judepereira.jupiter.entities;

import com.judepereira.jupiter.db.entities.Task;
import com.judepereira.jupiter.db.entities.Todo;
import com.judepereira.jupiter.db.repos.TaskRepository;
import com.judepereira.jupiter.db.repos.TodoRepository;
import com.judepereira.jupiter.db.repos.TodoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TodoServiceTest {

    private TodoRepository todoRepo;
    private TaskRepository taskRepo;
    private TodoService svc;

    @BeforeEach
    void setUp() {
        todoRepo = mock(TodoRepository.class);
        taskRepo = mock(TaskRepository.class);
        svc = new TodoService(todoRepo, taskRepo);
    }

    @Test
    void addTodo_validatesBlankAndMaxLength() {
        // blank
        when(taskRepo.findBySlugIgnoreCase("t")).thenReturn(Optional.of(new Task("T","t",null)));
        assertThrows(IllegalArgumentException.class, () -> svc.addTodo("t", "   "));

        // null treated as blank
        assertThrows(IllegalArgumentException.class, () -> svc.addTodo("t", null));

        // too long
        String longText = "x".repeat(1025);
        assertThrows(IllegalArgumentException.class, () -> svc.addTodo("t", longText));
    }

    @Test
    void addTodo_taskNotFound() {
        when(taskRepo.findBySlugIgnoreCase("missing")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> svc.addTodo("missing", "ok"));
    }

    @Test
    void addTodo_happyPath_trimsAndSaves() {
        Task task = new Task("T","task-slug",null);
        when(taskRepo.findBySlugIgnoreCase("task-slug")).thenReturn(Optional.of(task));
        when(todoRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Todo created = svc.addTodo(" task-slug ", "  do something  ");
        assertNotNull(created);
        assertEquals("do something", created.getText());
        assertEquals(task, created.getTask());
        assertNotNull(created.getCreatedAt());
    }

    @Test
    void listTodos_returnsOrderedList() {
        Task task = new Task("T","s",null);
        Todo a = new Todo(task, "one");
        Todo b = new Todo(task, "two");
        when(taskRepo.findBySlugIgnoreCase("s")).thenReturn(Optional.of(task));
        when(todoRepo.findAllByTaskOrderByCreatedAtAsc(task)).thenReturn(List.of(a,b));

        List<Todo> out = svc.listTodos(" s ");
        assertEquals(2, out.size());
        assertSame(a, out.get(0));
        assertSame(b, out.get(1));
    }

    @Test
    void completeTodo_setsCompletedAtAndSaves() {
        Task task = new Task("T","slug",null);
        Todo todo = new Todo(task, "t");
        when(taskRepo.findBySlugIgnoreCase("slug")).thenReturn(Optional.of(task));
        when(todoRepo.findByIdAndTask(1L, task)).thenReturn(Optional.of(todo));
        when(todoRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Todo done = svc.completeTodo(" slug ", 1L);
        assertNotNull(done.getCompletedAt());
        assertTrue(done.getCompletedAt().isBefore(Instant.now().plusSeconds(1)));
    }

    @Test
    void reopenTodo_clearsCompletedAtAndSaves() {
        Task task = new Task("T","slug",null);
        Todo todo = new Todo(task, "t");
        todo.setCompletedAt(Instant.now());
        when(taskRepo.findBySlugIgnoreCase("slug")).thenReturn(Optional.of(task));
        when(todoRepo.findByIdAndTask(2L, task)).thenReturn(Optional.of(todo));
        when(todoRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Todo reopened = svc.reopenTodo("slug", 2L);
        assertNull(reopened.getCompletedAt());
    }

    @Test
    void completeAndReopen_errors_whenTaskOrTodoNotFound() {
        // task not found
        when(taskRepo.findBySlugIgnoreCase("nope")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> svc.completeTodo("nope", 1L));
        assertThrows(IllegalArgumentException.class, () -> svc.reopenTodo("nope", 1L));

        // task exists but todo missing
        Task task = new Task("T","s",null);
        when(taskRepo.findBySlugIgnoreCase("s")).thenReturn(Optional.of(task));
        when(todoRepo.findByIdAndTask(999L, task)).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> svc.completeTodo("s", 999L));
        assertThrows(IllegalArgumentException.class, () -> svc.reopenTodo("s", 999L));
    }
}
