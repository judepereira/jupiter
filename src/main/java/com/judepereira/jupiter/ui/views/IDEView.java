package com.judepereira.jupiter.ui.views;

import com.judepereira.jupiter.ai.ChatClientService;
import com.judepereira.jupiter.db.entities.Project;
import com.judepereira.jupiter.db.entities.Task;
import com.judepereira.jupiter.db.repos.TaskConversationMemoryService;
import com.judepereira.jupiter.db.repos.TaskRepository;
import com.judepereira.jupiter.db.repos.TaskService;
import com.judepereira.jupiter.db.services.ProjectService;
import com.judepereira.jupiter.dtos.ChatMessage;
import com.judepereira.jupiter.ui.TaskContext;
import com.judepereira.jupiter.ui.components.ChatComposer;
import com.judepereira.jupiter.ui.components.CreateTaskDialog;
import com.judepereira.jupiter.ui.components.IconButton;
import com.judepereira.jupiter.ui.components.ReviewView;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.splitlayout.SplitLayout;
import com.vaadin.flow.router.*;
import lombok.val;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;

@Route("ide/:task")
@PageTitle("Jupiter")
class IDEView extends BaseLayout implements BeforeEnterObserver {
    private final Map<Task, TaskContext> contexts = new HashMap<>();

    private final ChatComposer chatComposer;
    private final ReviewView reviewView = new ReviewView();
    private final ChatClientService chatClientService;
    private final TaskService taskService;
    private final ProjectService projectService;
    private final TaskConversationMemoryService memoryService;
    private final TaskRepository taskRepository;
    private final com.judepereira.jupiter.db.repos.TodoService todoService;

    private Task currentTask;
    private ComboBox<Task> taskSelector;

    private final SplitLayout splitLayout;

    IDEView(ChatClientService chatClientService, TaskService taskService,
            ProjectService projectService, TaskConversationMemoryService memoryService, TaskRepository taskRepository,
            com.judepereira.jupiter.db.repos.TodoService todoService) {
        setSizeFull();
        this.chatClientService = chatClientService;
        this.taskService = taskService;
        this.projectService = projectService;
        this.memoryService = memoryService;

        // Use the task-context-aware consumer so we don't accidentally capture
        // `currentTask` (which may be null at construction time) and risk NPEs
        chatComposer = new ChatComposer(chatClientService,
                message -> {
                    var tc = getCurrentTaskContext();
                    if (tc != null) {
                        memoryService.appendMessage(tc.getTask().getSlug(), message);
                    }
                },
                this::getCurrentTaskContext);
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
        // ensure top bar controls stay in a single horizontal row and vertically centered
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
        var projectPaths = task.getProjects().stream().map(Project::getPath).toList();
        var scopedClient = chatClientService.forProjectPaths(projectPaths, task.getSlug());
        // TaskContext now holds only task, chat client and todo service.
        return new TaskContext(task, scopedClient, todoService);
    }

    private void switchTask(Task next, boolean updateUrl) {
        this.currentTask = next;

        contexts.computeIfAbsent(next, _ -> createTaskContext(next));

        var conv = memoryService.getConversation(next.getSlug());
        chatComposer.setConversation(conv);
        // refresh todos UI when switching tasks
        chatComposer.refreshTodosFromTask();

        if (updateUrl) {
            getUI().ifPresent(ui -> {
                val rr = new RouteParameters("task", next.getSlug());
                ui.navigate(IDEView.class, rr);
            });
        }

        next.setLastAccessed(System.currentTimeMillis());
        taskRepository.save(next);
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
