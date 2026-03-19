package com.judepereira.aide.task;

import com.judepereira.aide.project.Project;
import com.judepereira.aide.project.ProjectRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.regex.Pattern;

@Service
public class TaskService {

    private static final Pattern SLUG_PATTERN = Pattern.compile("^[A-Za-z0-9_-]+$");

    private final TaskRepository taskRepository;
    private final ProjectRepository projectRepository;

    public TaskService(TaskRepository taskRepository, ProjectRepository projectRepository) {
        this.taskRepository = taskRepository;
        this.projectRepository = projectRepository;
    }

    @Transactional(readOnly = true)
    public List<Task> listTasks() {
        return taskRepository.findAll();
    }

    @Transactional(readOnly = true)
    public Optional<Task> findBySlug(String slug) {
        if (slug == null) return Optional.empty();
        return taskRepository.findBySlugIgnoreCase(slug.trim());
    }

    @Transactional
    public Task createTask(String title, String slug, Collection<Long> projectIds) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("Task title is required and cannot be blank");
        }

        if (slug == null || slug.isBlank()) {
            throw new IllegalArgumentException("Task slug is required and cannot be blank");
        }

        String trimmedSlug = slug.trim();
        if (!SLUG_PATTERN.matcher(trimmedSlug).matches()) {
            throw new IllegalArgumentException("Task slug contains invalid characters. Allowed: A-Z a-z 0-9 _ -");
        }

        if (taskRepository.existsBySlugIgnoreCase(trimmedSlug)) {
            throw new IllegalArgumentException("A task with slug '" + trimmedSlug + "' already exists");
        }

        if (projectIds == null || projectIds.isEmpty()) {
            throw new IllegalArgumentException("At least one project must be selected");
        }

        if (projectIds.size() != 1) {
            throw new IllegalArgumentException("Exactly one project must be selected for now");
        }

        // load projects and ensure existence
        Set<Project> projects = new HashSet<>();
        for (Long pid : projectIds) {
            Project p = projectRepository.findById(pid).orElseThrow(() -> new IllegalArgumentException("Project with id " + pid + " does not exist"));
            projects.add(p);
        }

        Task t = new Task(title.trim(), trimmedSlug, projects);
        return taskRepository.save(t);
    }
}
