package com.judepereira.jupiter.ui.components;

import com.judepereira.jupiter.dtos.Dir;
import com.judepereira.jupiter.db.entities.Project;
import com.judepereira.jupiter.db.services.ProjectService;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.component.treegrid.TreeGrid;
import com.vaadin.flow.data.provider.hierarchy.TreeData;
import com.vaadin.flow.data.provider.hierarchy.TreeDataProvider;
import org.jetbrains.annotations.UnknownNullability;

import java.io.File;
import java.util.Arrays;
import java.util.function.Consumer;

/**
 * Reusable dialog component for creating a new Project by selecting a filesystem path.
 */
public class CreateProjectDialog {
    private final ProjectService projectService;
    private final Consumer<Project> onCreated;

    public CreateProjectDialog(ProjectService projectService, Consumer<Project> onCreated) {
        this.projectService = projectService;
        this.onCreated = onCreated;
    }

    public void open() {
        Dialog d = new Dialog();
        d.setWidth("800px");
        d.setHeaderTitle("Add Project");

        TextField name = new TextField("Project Name");
        name.setWidthFull();
        name.getStyle().setPaddingTop("0");

        TextField pathField = new TextField("Project Path");
        pathField.setWidthFull();
        pathField.setReadOnly(true);
        pathField.getStyle().setPaddingTop("0");

        TreeGrid<Dir> tree = new TreeGrid<>();
        tree.setHeight("400px");
        TreeData<Dir> data = new TreeData<>();

        data.addRootItems(new Dir(new File(System.getProperty("user.home")), true));

        TreeDataProvider<Dir> provider = new TreeDataProvider<>(data) {
            @Override
            public boolean hasChildren(Dir item) {
                return item.getFile().isDirectory();
            }
        };
        tree.setDataProvider(provider);
        tree.addHierarchyColumn(s -> s);

        // lazy-load on expand
        tree.addExpandListener(ev -> {
            for (Dir dir : ev.getItems()) {
                File[] dirs = dir.getFile().listFiles(File::isDirectory);
                if (dirs != null) {
                    Arrays.sort(dirs, (o1, o2) -> o1.getName().startsWith(".") ? 1 :
                            o2.getName().startsWith(".") ? -1 : o1.getName().compareTo(o2.getName()));
                    for (File dfile : dirs) {
                        if (!data.getChildren(dir).contains(new Dir(dfile))) {
                            data.addItem(dir, new Dir(dfile));
                        }
                    }
                }

                provider.refreshItem(dir);
                pathField.setValue(dir.getFile().getAbsolutePath());
                itemSelected(dir, pathField, name);
                tree.select(dir);
            }
        });

        tree.addItemClickListener(ev -> {
            itemSelected(ev.getItem(), pathField, name);
            tree.expand(ev.getItem());
        });

        Button save = new Button("Create project", _ -> {
            String n = name.getValue();
            String p = pathField.getValue();
            if (n == null || n.isBlank()) {
                AppNotifications.showError("Project name required");
                return;
            }
            if (p == null || p.isBlank()) {
                AppNotifications.showError("Select a path");
                return;
            }
            try {
                var created = projectService.createProject(n, p);
                d.close();
                if (onCreated != null) onCreated.accept(created);
            } catch (IllegalArgumentException ex) {
                AppNotifications.showError(ex.getMessage());
            } catch (Exception ex) {
                AppNotifications.show("Failed to create project: " + ex.getMessage());
            }
        });
        save.addThemeVariants(ButtonVariant.LUMO_SUCCESS);

        Button cancel = new Button("Cancel", _ -> d.close());
        cancel.addThemeVariants(ButtonVariant.LUMO_TERTIARY);

        d.getFooter().add(cancel, save);

        VerticalLayout vl = new VerticalLayout(name, pathField, tree);
        vl.setPadding(false);

        d.add(vl);
        d.open();
    }

    private static void itemSelected(@UnknownNullability Dir d, TextField pathField, TextField name) {
        File p = d.getFile();
        pathField.setValue(p.getAbsolutePath());
        name.setValue(p.getName());
    }
}
