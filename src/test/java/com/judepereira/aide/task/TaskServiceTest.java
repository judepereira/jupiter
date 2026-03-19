package com.judepereira.aide.task;

import com.judepereira.aide.project.Project;
import com.judepereira.aide.project.ProjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TaskServiceTest {

    private TaskRepository taskRepo;
    private ProjectRepository projectRepo;
    private TaskService svc;

    @BeforeEach
    void setUp() {
        taskRepo = mock(TaskRepository.class);
        projectRepo = mock(ProjectRepository.class);
        svc = new TaskService(taskRepo, projectRepo);
    }

    @Test
    void createTask_validatesSlugAndProjects() {
        when(taskRepo.existsBySlugIgnoreCase("valid-slug")).thenReturn(false);
        when(projectRepo.findById(1L)).thenReturn(Optional.of(new Project("P","/p")));
        when(taskRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // null title
        assertThrows(IllegalArgumentException.class, () -> svc.createTask(null, "s", List.of(1L)));

        // invalid slug
        assertThrows(IllegalArgumentException.class, () -> svc.createTask("T", "bad slug!", List.of(1L)));

        // no projects
        assertThrows(IllegalArgumentException.class, () -> svc.createTask("T", "valid-slug", List.of()));

        // multiple projects
        assertThrows(IllegalArgumentException.class, () -> svc.createTask("T", "valid-slug", List.of(1L,2L)));

        // project doesn't exist
        when(projectRepo.findById(2L)).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> svc.createTask("T", "valid-slug", List.of(2L)));

        // happy path
        Task created = svc.createTask(" T ", " valid-slug ", List.of(1L));
        assertNotNull(created);
        assertEquals("valid-slug", created.getSlug());
        assertEquals("T", created.getTitle());
        assertEquals(1, created.getProjects().size());
    }

    @Test
    void existsBySlug_ignoreCase_preventsDuplicates() {
        when(taskRepo.existsBySlugIgnoreCase("dupe-slug")).thenReturn(true);
        when(projectRepo.findById(1L)).thenReturn(Optional.of(new Project("P","/p")));

        assertThrows(IllegalArgumentException.class, () -> svc.createTask("Title", "dupe-slug", List.of(1L)));
    }
}
