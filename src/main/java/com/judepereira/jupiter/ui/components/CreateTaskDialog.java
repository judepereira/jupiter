package com.judepereira.jupiter.ui.components;

import com.judepereira.jupiter.db.entities.Project;
import com.judepereira.jupiter.db.services.ProjectService;
import com.judepereira.jupiter.db.entities.Task;
import com.judepereira.jupiter.db.repos.TaskService;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.listbox.MultiSelectListBox;
import com.vaadin.flow.component.orderedlayout.Scroller;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.renderer.ComponentRenderer;
import com.vaadin.flow.data.value.ValueChangeMode;
import com.vaadin.flow.function.SerializableFunction;
import lombok.extern.log4j.Log4j2;
import lombok.val;

import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Reusable dialog component for creating a new Task.
 */
@Log4j2
public class CreateTaskDialog {
    public static final String ERR_MAX_PROJECTS_1 = "At most one project may be selected (multi project selection is coming soon :))";
    public static final String ASSOCIATE_THIS_TASK_WITH_THE_FOLLOWING_PROJECTS = "Associate this task with the following project(s):";
    public static final String OR_ADD_A_NEW_PROJECT = "Add a new project";

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
        d.setHeaderTitle("New Task");

        TextField branchName = new TextField("Branch Name");
        branchName.getStyle().setPaddingTop("0");
        branchName.setWidthFull();

        val projectSelectorLabel = new Span(ASSOCIATE_THIS_TASK_WITH_THE_FOLLOWING_PROJECTS);

        var projects = projectService.listProjects();
        MultiSelectListBox<Project> projectSelect = new MultiSelectListBox<>();
        projectSelect.setItems(projects);
        projectSelect.setWidthFull();

        projectSelect.setRenderer(new ComponentRenderer<>(project -> {
            val path = new Span(project.getPath());
            path.getStyle().setFontSize("small");
            return new Span(new Span(project.getName()), new Span(" — "), path);
        }));

        projectSelect.addValueChangeListener(ev -> {
            Set<Project> s = ev.getValue();
            if (s.size() > 1) {
                Project chosen = s.stream().reduce((_, second) -> second).orElse(null);
                projectSelect.deselectAll();
                projectSelect.select(chosen);
                AppNotifications.showError(ERR_MAX_PROJECTS_1);
            }
        });

        val projectScroller = new Scroller(projectSelect);
        projectScroller.setMaxHeight("125px");
        projectScroller.setWidthFull();

        if (projects.isEmpty()) {
            projectSelect.setEnabled(false);
            projectSelectorLabel.setText("Tasks in Jupiter are associated with one or more projects. " +
                    "A project is a directory that's checked out already. " +
                    "In order to create your first task, create new project.");
            projectScroller.setVisible(false);
        }

        TextField title = new TextField("Title");
        title.setWidthFull();
        title.setValueChangeMode(ValueChangeMode.EAGER);
        title.getStyle().setPaddingTop("0");

        title.addValueChangeListener(ev -> branchName.setValue(generateSlug(ev.getValue())));

        Button createProject = new Button(projects.isEmpty() ? "Add your first project  🎉" : OR_ADD_A_NEW_PROJECT, ev -> {
            var dlg = new CreateProjectDialog(projectService, created -> {
                var refreshed = projectService.listProjects();
                projectSelect.setItems(refreshed);
                projectSelect.setEnabled(!refreshed.isEmpty());
                if (created != null) {
                    projectScroller.setVisible(true);
                    projectSelect.select(created);
                    projectSelectorLabel.setText(ASSOCIATE_THIS_TASK_WITH_THE_FOLLOWING_PROJECTS);
                    ev.getSource().setText(OR_ADD_A_NEW_PROJECT);
                }
            });
            dlg.open();
        });
        createProject.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE, ButtonVariant.LUMO_SMALL);

        Button save = new Button("Create", _ -> {
            String t = title.getValue();
            String slug = branchName.getValue();
            Set<Project> selected = projectSelect.getSelectedItems();
            if (t == null || t.isBlank()) {
                AppNotifications.showError("Title is required");
                return;
            }
            if (slug == null || slug.isBlank() || !slug.matches("^[A-Za-z0-9_-]+$")) {
                AppNotifications.showError("Branch name is required and must match pattern A-Za-z0-9_-");
                return;
            }
            if (selected == null || selected.isEmpty()) {
                AppNotifications.showError("A task must be associated with at least one project");
                return;
            }
            if (selected.size() != 1) {
                AppNotifications.showError(ERR_MAX_PROJECTS_1);
                return;
            }

            try {
                var created = taskService.createTask(t, slug, selected.stream().map(Project::getId).collect(Collectors.toSet()));
                d.close();
                if (onCreated != null) {
                    onCreated.accept(created);
                }
            } catch (IllegalArgumentException ex) {
                AppNotifications.showError(ex.getMessage());
                log.error("Failed to create task", ex);
            } catch (Exception ex) {
                AppNotifications.show("Failed to create task: " + ex.getMessage());
                log.error("Failed to create task", ex);
            }
        });

        save.addThemeVariants(ButtonVariant.LUMO_SUCCESS);

        Button cancel = new Button("Cancel", _ -> d.close());
        cancel.addThemeVariants(ButtonVariant.LUMO_TERTIARY);

        d.getFooter().add(cancel, save);

        VerticalLayout content = new VerticalLayout(title, branchName, projectSelectorLabel, projectScroller, createProject);
        content.setPadding(false);
        d.add(content);
        d.open();
    }

    private String generateSlug(String input) {
        if (input == null) {
            return "";
        }
        String s = input.trim().toLowerCase().replaceAll("\\s+", "-");
        s = s.replaceAll("[^A-Za-z0-9_-]", "");
        s = s.replaceAll("[-_]{2,}", "-");
        return s;
    }
}
