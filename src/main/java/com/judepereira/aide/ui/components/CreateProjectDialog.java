package com.judepereira.aide.ui.components;

import com.judepereira.aide.project.Project;
import com.judepereira.aide.project.ProjectService;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.FlexLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.component.treegrid.TreeGrid;
import com.vaadin.flow.data.provider.hierarchy.TreeData;
import com.vaadin.flow.data.provider.hierarchy.TreeDataProvider;

import java.io.File;
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
        d.setHeight("600px");

        TextField name = new TextField("Project name");
        name.setWidthFull();

        TextField pathField = new TextField("Selected path");
        pathField.setWidthFull();
        pathField.setReadOnly(true);

        TreeGrid<String> tree = new TreeGrid<>();
        TreeData<String> data = new TreeData<>();
        String root = System.getProperty("user.home");
        data.addItem(null, root);

        // preload root children so the tree shows expandable affordance
        File rootFile = new File(root);
        File[] rootDirs = rootFile.listFiles(File::isDirectory);
        if (rootDirs != null) {
            for (File dfile : rootDirs) {
                data.addItem(root, dfile.getAbsolutePath());
            }
        }

        TreeDataProvider<String> provider = new TreeDataProvider<>(data);
        tree.setDataProvider(provider);
        tree.addHierarchyColumn(s -> s).setHeader("Directories");

        // lazy-load on expand
        tree.addExpandListener(ev -> {
            for (String node : ev.getItems()) {
                File fnode = new File(node);
                File[] dirs = fnode.listFiles(File::isDirectory);
                if (dirs != null) {
                    for (File dfile : dirs) {
                        if (!data.getChildren(node).contains(dfile.getAbsolutePath())) {
                            data.addItem(node, dfile.getAbsolutePath());
                        }
                    }
                }
            }
            provider.refreshAll();
        });

        tree.addItemClickListener(ev -> {
            String p = ev.getItem();
            pathField.setValue(p);
        });

        Button save = new Button("Create project", e -> {
            String n = name.getValue();
            String p = pathField.getValue();
            if (n == null || n.isBlank()) { Notification.show("Project name required"); return; }
            if (p == null || p.isBlank()) { Notification.show("Select a path"); return; }
            try {
                var created = projectService.createProject(n, p);
                d.close();
                if (onCreated != null) onCreated.accept(created);
            } catch (Exception ex) {
                Notification.show("Failed to create project: " + ex.getMessage());
            }
        });

        Button cancel = new Button("Cancel", e -> d.close());

        FlexLayout foot = new FlexLayout(save, cancel);
        foot.getStyle().set("justify-content", "flex-end");

        d.add(name, pathField, tree, foot);
        d.open();
    }
}
