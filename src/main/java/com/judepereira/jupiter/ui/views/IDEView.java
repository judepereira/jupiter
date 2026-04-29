package com.judepereira.jupiter.ui.views;

import com.judepereira.jupiter.ai.ChatClientService;
import com.judepereira.jupiter.db.entities.Task;
import com.judepereira.jupiter.db.repos.TaskConversationMemoryService;
import com.judepereira.jupiter.db.repos.TaskRepository;
import com.judepereira.jupiter.db.repos.TaskService;
import com.judepereira.jupiter.db.repos.TodoService;
import com.judepereira.jupiter.db.services.ProjectService;
import com.judepereira.jupiter.ui.TaskContext;
import com.judepereira.jupiter.ui.components.ChatComposer;
import com.judepereira.jupiter.ui.components.CreateTaskDialog;
import com.judepereira.jupiter.ui.components.IconButton;
import com.judepereira.jupiter.ui.components.ReviewView;
import com.judepereira.jupiter.ui.components.AppNotifications;
import com.judepereira.jupiter.git.GitDiffService;
import com.judepereira.jupiter.db.entities.Project;
import com.judepereira.jupiter.ui.views.TaskProjectWatcher;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.Key;
import com.vaadin.flow.component.KeyModifier;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.splitlayout.SplitLayout;
import com.vaadin.flow.router.*;
import lombok.val;
import lombok.extern.log4j.Log4j2;
import org.springframework.ai.chat.client.ChatClient;

import java.util.HashMap;
import java.util.Map;

@Log4j2
@Route("ide/:task")
@PageTitle("Jupiter")
class IDEView extends BaseLayout implements BeforeEnterObserver {
    private final Map<Task, TaskContext> contexts = new HashMap<>();

    private final ChatComposer chatComposer;
    private final ReviewView reviewView;
    private final ChatClientService chatClientService;
    private final TaskService taskService;
    private final ProjectService projectService;
    private final TaskConversationMemoryService memoryService;
    private final TaskRepository taskRepository;
    private final TodoService todoService;
    private final ChatClient.Builder chatClientBuilder;

    private Task currentTask;
    private ComboBox<Task> taskSelector;

    private final SplitLayout splitLayout;

    private final GitDiffService gitDiffService;
    private final Map<Task, TaskProjectWatcher> watchers = new HashMap<>();

    IDEView(GitDiffService gitDiffService, TaskService taskService, ProjectService projectService,
            TaskConversationMemoryService memoryService, TaskRepository taskRepository,
            TodoService todoService, ChatClient.Builder chatClientBuilder) {
        setSizeFull();
        this.chatClientService = new ChatClientService(chatClientBuilder);
        this.taskService = taskService;
        this.projectService = projectService;
        this.memoryService = memoryService;
        this.gitDiffService = gitDiffService;

        chatComposer = new ChatComposer(
                message -> {
                    memoryService.appendMessage(message.getTaskContext().getTask().getSlug(), message);
                },
                this::getCurrentTaskContext);
        reviewView = new ReviewView(gitDiffService);
        splitLayout = new SplitLayout(chatComposer, reviewView);
        splitLayout.setSizeFull();
        splitLayout.setSplitterPosition(62);
        splitLayout.getStyle()
                .set("margin-top", BAR_THICKNESS)
                .set("margin-left", BAR_THICKNESS)
                .set("margin-right", BAR_THICKNESS)
                .set("height", "calc(100vh - " + BAR_THICKNESS + ")")
                .set("width", "calc(100vw - (" + BAR_THICKNESS + " * 2))");

        addAndExpand(splitLayout);

        buildTopBarControls();
        this.taskRepository = taskRepository;
        this.todoService = todoService;
        this.chatClientBuilder = chatClientBuilder;

        initShortcuts();
    }

    private TaskContext getCurrentTaskContext() {
        return contexts.get(currentTask);
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        var task = event.getRouteParameters().get("task");

        if (task.isEmpty()) {
            UI.getCurrent().navigate("");
            return;
        }

        taskService.findBySlug(task.get()).ifPresentOrElse(t -> {
            taskSelector.setValue(t);
            switchTask(t, false);
        }, () -> UI.getCurrent().navigate(""));
    }

    private void buildTopBarControls() {
        HorizontalLayout controls = new HorizontalLayout();
        controls.getStyle().set("align-items", "center");
        controls.getStyle().set("flex-direction", "row");
        controls.getStyle().set("gap", "var(--lumo-space-s)");
        controls.setPadding(false);

        taskSelector = new ComboBox<>();
        taskSelector.getStyle().setBackgroundColor("white");
        taskSelector.setItemLabelGenerator(Task::getTitle);
        taskSelector.setClearButtonVisible(true);
        taskSelector.setWidth("280px");

        refreshTasksIntoSelector();
        taskSelector.addValueChangeListener(ev -> {
            Task next = ev.getValue();
            if (next == null) {
                return;
            }
            switchTask(next, true);
        });

        IconButton create = new IconButton(VaadinIcon.PLUS.create());
        create.setLightMode();
        create.getElement().setProperty("title", "Create task");
        create.addClickListener(_ -> openCreateTaskDialog());

        controls.add(taskSelector, create);

        getTopBar().add(controls);
    }

    private void refreshTasksIntoSelector() {
        var tasks = taskService.listTasks();
        taskSelector.setItems(tasks);
    }

    private TaskContext createTaskContext(Task task) {
        return new TaskContext(task, chatClientService, todoService);
    }

    private void initShortcuts() {
        UI.getCurrent().addShortcutListener(this::openCreateTaskDialog, Key.KEY_N, KeyModifier.ALT);
    }

    private void switchTask(Task next, boolean updateUrl) {
        this.currentTask = next;

        val taskContext = contexts.computeIfAbsent(next, _ -> createTaskContext(next));

        var conv = memoryService.getConversation(taskContext);
        chatComposer.setConversation(conv);
        chatComposer.refreshTodosFromTask();

        if (updateUrl) {
            getUI().ifPresent(ui -> {
                val rr = new RouteParameters("task", next.getSlug());
                ui.navigate(IDEView.class, rr);
            });
        }

        next.setLastAccessed(System.currentTimeMillis());
        taskRepository.save(next);
        // ensure review updates for the selected task
        reviewView.renderTask(next);

        // ensure we have a filesystem watcher for this task's project
        if (!watchers.containsKey(next)) {
            try {
                Project project = next.getProjects().stream().findFirst().orElse(null);
                if (project == null || project.getPath() == null || project.getPath().isBlank()) {
                    AppNotifications.showError("Task has no associated project path");
                } else {
                    var watcher = new TaskProjectWatcher(project.getPath(), changedPath -> {
                        getUI().ifPresent(ui -> ui.access(() -> {
                            if (currentTask != null) {
                                reviewView.renderTask(currentTask);
                            }
                        }));
                    });
                    watcher.start();
                    watchers.put(next, watcher);
                }
            } catch (Exception e) {
                AppNotifications.showError("Failed to start project watcher: " + e.getMessage());
            }
        }
    }

    @Override
    protected void onDetach(DetachEvent detachEvent) {
        watchers.values().forEach(w -> {
            try {
                w.stop();
            } catch (Exception e) {
                log.error("Error stopping watcher during detach", e);
                AppNotifications.showError("Error stopping watcher: " + e.getMessage());
            }
        });
        watchers.clear();
        super.onDetach(detachEvent);
    }

    private void openCreateTaskDialog() {
        var dlg = new CreateTaskDialog(taskService, projectService, created -> {
            if (created == null) {
                return;
            }
            refreshTasksIntoSelector();
            taskSelector.setValue(created);
            switchTask(created, true);
        });
        dlg.open();
    }
}
