package com.judepereira.jupiter.db.services;

import com.judepereira.jupiter.db.project.Project;
import com.judepereira.jupiter.db.repos.ProjectRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ProjectService {

    private final ProjectRepository projectRepository;

    public ProjectService(ProjectRepository projectRepository) {
        this.projectRepository = projectRepository;
    }

    @Transactional(readOnly = true)
    public List<Project> listProjects() {
        return projectRepository.findAll();
    }

    @Transactional
    public Project createProject(String name, String path) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Project name is required and cannot be blank");
        }
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("Project path is required and cannot be blank");
        }

        String trimmedName = name.trim();
        String trimmedPath = path.trim();

        if (projectRepository.existsByPathIgnoreCase(trimmedPath)) {
            throw new IllegalArgumentException("A project with path '" + trimmedPath + "' already exists");
        }

        Project p = new Project(trimmedName, trimmedPath);
        return projectRepository.save(p);
    }

    @Transactional(readOnly = true)
    public Project getById(Long id) {
        return projectRepository.findById(id).orElse(null);
    }
}
