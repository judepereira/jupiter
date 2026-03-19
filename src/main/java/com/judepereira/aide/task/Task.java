package com.judepereira.aide.task;

import com.judepereira.aide.project.Project;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.util.HashSet;
import java.util.Set;

@Data
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@Entity
@Table(name = "task", indexes = {@Index(columnList = "slug", name = "idx_task_slug")})
public class Task {

    @EqualsAndHashCode.Include
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE)
    private Long id;

    @NotBlank
    @Column(nullable = false)
    private String title;

    @NotBlank
    @Column(nullable = false, unique = true)
    private String slug;

    @ManyToMany
    @JoinTable(name = "task_project",
            joinColumns = @JoinColumn(name = "task_id"),
            inverseJoinColumns = @JoinColumn(name = "project_id"))
    private Set<Project> projects = new HashSet<>();

    public Task(String title, String slug, Set<Project> projects) {
        this.title = title;
        this.slug = slug;
        this.projects = projects == null ? new HashSet<>() : projects;
    }
}
