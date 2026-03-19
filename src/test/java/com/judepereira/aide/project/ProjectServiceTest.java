package com.judepereira.aide.project;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProjectServiceTest {

    private ProjectRepository repo;
    private ProjectService svc;

    @BeforeEach
    void setUp() {
        repo = Mockito.mock(ProjectRepository.class);
        svc = new ProjectService(repo);
    }

    @Test
    void createProject_validatesNameAndPathAndDuplicates() {
        when(repo.existsByPathIgnoreCase("/some/path")).thenReturn(true);

        IllegalArgumentException ex1 = assertThrows(IllegalArgumentException.class, () -> svc.createProject(null, "/some/path"));
        assertTrue(ex1.getMessage().toLowerCase().contains("name"));

        IllegalArgumentException ex2 = assertThrows(IllegalArgumentException.class, () -> svc.createProject("Name", null));
        assertTrue(ex2.getMessage().toLowerCase().contains("path"));

        IllegalArgumentException ex3 = assertThrows(IllegalArgumentException.class, () -> svc.createProject("Name", " /some/path "));
        assertTrue(ex3.getMessage().toLowerCase().contains("already exists"));

        // when not duplicate, should save
        when(repo.existsByPathIgnoreCase("/unique")).thenReturn(false);
        Project p = new Project("P", "/unique");
        when(repo.save(any())).thenReturn(p);

        Project created = svc.createProject(" P ", " /unique ");
        assertNotNull(created);
        assertEquals("P", created.getName());
        assertEquals("/unique", created.getPath());
        verify(repo).save(any());
    }
}
