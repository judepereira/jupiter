package com.judepereira.aide.ui.components;

import com.judepereira.aide.project.Project;
import com.judepereira.aide.project.ProjectService;
import com.judepereira.aide.task.Task;
import com.judepereira.aide.task.TaskService;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.listbox.MultiSelectListBox;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.FlexLayout;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.textfield.TextField;

import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Reusable dialog component for creating a new Task.
 */
public class CreateTaskDialog {
    private final TaskService taskService;
    private final ProjectService projectService;
    private final Consumer<Task> onCreated;

    public CreateTaskDialog(TaskService taskService, ProjectService projectService, Consumer<Task> onCreated) {
        this.taskService = taskService;
        this.projectService = projectService;
        this.onCreated = onCreated;
    }

    public void open() {
        Dialog d = new Dialog();
        d.setWidth("600px");

        TextField title = new TextField("Title");
        title.setWidthFull();

        Span slugDisplay = new Span();
        slugDisplay.getStyle().set("font-size", "var(--lumo-font-size-s)");

        var projects = projectService.listProjects();
        MultiSelectListBox<Project> projectSelect = new MultiSelectListBox<>();
        projectSelect.setItems(projects);
        projectSelect.setItemLabelGenerator(Project::getName);
        projectSelect.setWidthFull();

        // Enforce single selection deterministically
        projectSelect.addValueChangeListener(ev -> {
            Set<Project> s = ev.getValue();
            if (s.size() > 1) {
                Project chosen = s.stream().reduce((_, second) -> second).orElse(null);
                projectSelect.deselectAll();
                projectSelect.select(chosen);
                Notification.show("Only one project can be selected");
            }
        });

        if (projects.isEmpty()) {
            projectSelect.setEnabled(false);
            projectSelect.getElement().setProperty("title", "No projects available. Create one first.");
        }

        title.addValueChangeListener(ev -> slugDisplay.setText(generateSlug(ev.getValue())));
        // live show slug
        slugDisplay.setText(generateSlug(title.getValue()));

        Button createProject = new Button("Create project", _ -> {
            var dlg = new CreateProjectDialog(projectService, created -> {
                var refreshed = projectService.listProjects();
                projectSelect.setItems(refreshed);
                projectSelect.setEnabled(!refreshed.isEmpty());
                if (created != null) projectSelect.select(created);
            });
            dlg.open();
        });

        Button save = new Button("Create", _ -> {
            String t = title.getValue();
            String slug = slugDisplay.getText();
            Set<Project> selected = projectSelect.getSelectedItems();
            if (t == null || t.isBlank()) { Notification.show("Title is required"); return; }
            if (slug == null || slug.isBlank() || !slug.matches("^[A-Za-z0-9_-]+$")) {
                Notification.show("Slug is required and must match pattern A-Za-z0-9_- ");
                return;
            }
            if (selected == null || selected.isEmpty()) { Notification.show("Select a project"); return; }
            if (selected.size() != 1) { Notification.show("Exactly one project must be selected"); return; }

            try {
                var created = taskService.createTask(t, slug, selected.stream().map(Project::getId).collect(Collectors.toSet()));
                d.close();
                if (onCreated != null) onCreated.accept(created);
            } catch (Exception ex) {
                Notification.show("Failed to create task: " + ex.getMessage());
            }
        });

        Button cancel = new Button("Cancel", _ -> d.close());

        FlexLayout foot = new FlexLayout(save, cancel);
        foot.getStyle().set("justify-content", "flex-end");

        d.add(title, slugDisplay, projectSelect, createProject, foot);
        d.open();
    }

    private String generateSlug(String input) {
        if (input == null) return "";
        String s = input.trim().replaceAll("\\s+", "-");
        s = s.replaceAll("[^A-Za-z0-9_-]", "");
        s = s.replaceAll("[-_]{2,}", "-");
        return s;
    }
}
