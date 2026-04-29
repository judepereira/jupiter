package com.judepereira.jupiter.ui.components;

import com.judepereira.jupiter.db.entities.Project;
import com.judepereira.jupiter.db.entities.Task;
import com.judepereira.jupiter.git.GitDiffService;
import com.judepereira.jupiter.git.GitFileDiff;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.judepereira.jupiter.ui.components.AppNotifications;
import lombok.extern.log4j.Log4j2;

import java.util.List;

@Log4j2
public class ReviewView extends VerticalLayout {

    private final GitDiffService gitDiffService;
    private final VerticalLayout content = new VerticalLayout();

    public ReviewView(GitDiffService gitDiffService) {
        this.gitDiffService = gitDiffService;
        setPadding(false);
        setSizeFull();
        content.setPadding(false);
        add(content);
        showEmptyState();
    }

    private void showEmptyState() {
        content.removeAll();
        content.add(new Span("No files modified yet."));
    }

    public void renderTask(Task task) {
        content.removeAll();

        if (task == null) {
            showEmptyState();
            return;
        }

        try {
            Project project = task.getProjects().stream().findFirst().orElseThrow(
                    () -> new IllegalArgumentException("Task has no associated project"));

            String projectPath = project.getPath();
            List<String> changed = gitDiffService.listChangedFiles(projectPath);

            if (changed == null || changed.isEmpty()) {
                content.add(new Span("No files modified yet."));
                return;
            }

            for (String relPath : changed) {
                GitFileDiff fileDiff = gitDiffService.getFileDiff(projectPath, relPath);
                FileDiffView v = new FileDiffView(fileDiff);
                content.add(v);
            }

        } catch (Exception e) {
            log.error("Failed to render task review", e);
            AppNotifications.showError(e.getMessage());
            content.add(new Span("Error: " + e.getMessage()));
        }
    }
}
