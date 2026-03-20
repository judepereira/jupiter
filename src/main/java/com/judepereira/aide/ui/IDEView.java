package com.judepereira.aide.ui;

import com.judepereira.aide.ai.ChatClientService;

import com.judepereira.aide.project.ProjectService;
import com.judepereira.aide.task.Task;
import com.judepereira.aide.task.TaskConversationMemoryService;
import com.judepereira.aide.task.TaskService;
import com.judepereira.aide.ui.components.ChatComposer;
import com.judepereira.aide.ui.components.IconButton;
import com.judepereira.aide.ui.components.ReviewView;
import com.judepereira.aide.ui.components.CreateTaskDialog;
import com.vaadin.flow.component.splitlayout.SplitLayout;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.QueryParameters;
import java.util.List;
import java.util.Map;
import java.util.Collections;

@Route(value = "")
@PageTitle("Jupiter")
class IDEView extends BaseLayout implements BeforeEnterObserver {
    private final ChatComposer chatComposer;
    private final ReviewView reviewView = new ReviewView();
    private final ChatClientService chatClientService;
    private final TaskService taskService;
    private final ProjectService projectService;
    private final TaskConversationMemoryService memoryService;

    private Task currentTask;
    private ComboBox<Task> taskSelector;
    // suppression flag to avoid programmatic setValue() triggering navigation/handlers
    private boolean suppressSelectEvents = false;

    private final SplitLayout splitLayout;

    IDEView(ChatClientService chatClientService, TaskService taskService, ProjectService projectService, TaskConversationMemoryService memoryService) {
        setSizeFull();
        this.chatClientService = chatClientService;
        this.taskService = taskService;
        this.projectService = projectService;
        this.memoryService = memoryService;

        chatComposer = new ChatComposer(chatClientService);
        splitLayout = new SplitLayout(chatComposer, reviewView);
        splitLayout.setSizeFull();
        splitLayout.setSplitterPosition(62);
        splitLayout.getStyle()
                .set("margin-top", BAR_THICKNESS)
                .set("margin-left", BAR_THICKNESS)
                .set("margin-right", BAR_THICKNESS)
                .set("height", "calc(100vh - " + BAR_THICKNESS + ")")
                .set("width", "calc(100vw - (" + BAR_THICKNESS + " * 2))");

        // default main content
        addAndExpand(splitLayout);

        buildTopBarControls();
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        var qp = event.getLocation().getQueryParameters();
        var map = qp.getParameters();
        var list = map.getOrDefault("task", List.of());

        if (!list.isEmpty()) {
            String slug = list.get(0);
            taskService.findBySlug(slug).ifPresentOrElse(t -> {
                // valid slug: show main split and switch to task without updating URL
                setMainContent(splitLayout);
                suppressSelectEvents = true;
                try {
                    taskSelector.setValue(t);
                    switchTask(t, false);
                } finally {
                    suppressSelectEvents = false;
                }
            }, () -> showTaskNotFoundView(slug));
            return;
        }

        // no task param
        var tasks = taskService.listTasks();
        if (!tasks.isEmpty() && currentTask == null) {
            setMainContent(splitLayout);
            Task first = tasks.get(0);
            suppressSelectEvents = true;
            try {
                taskSelector.setValue(first);
                switchTask(first, true);
            } finally {
                suppressSelectEvents = false;
            }
        } else {
            setMainContent(splitLayout);
        }
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
        // initial population is handled in beforeEnter; just wire listener with suppression check
        refreshTasksIntoSelector();
        taskSelector.addValueChangeListener(ev -> {
            if (suppressSelectEvents) return;
            Task next = ev.getValue();
            // when user changes selection, update URL as well
            switchTask(next, true);
        });

        IconButton create = new IconButton(VaadinIcon.PLUS.create());
        create.setLightMode();
        create.getElement().setProperty("title", "Create task");
        create.addClickListener(e -> openCreateTaskDialog());

        controls.add(taskSelector, create);

        // add to top bar
        getTopBar().add(controls);
    }

    private void refreshTasksIntoSelector() {
        var tasks = taskService.listTasks();
        taskSelector.setItems(tasks);
        // do not auto-select here to avoid navigation loops; selection happens in beforeEnter or explicit flows
    }

    /**
     * Centralized task switching logic. When updateUrl is true the router will be used to
     * update the task query parameter.
     */
    private void switchTask(Task next, boolean updateUrl) {
        Task previous = this.currentTask;
        if (previous != null) {
            memoryService.saveConversation(previous.getSlug(), chatComposer.getConversationSnapshot());
        }

        this.currentTask = next;

        if (next == null) {
            chatComposer.clearConversation();
        } else {
            // load conversation from memory in a single call
            var conv = memoryService.getConversation(next.getSlug());
            chatComposer.setConversation(conv);
        }

        if (updateUrl && next != null) {
            getUI().ifPresent(ui -> {
                Map<String, List<String>> params = Collections.singletonMap("task", Collections.singletonList(next.getSlug()));
                QueryParameters qp = new QueryParameters(params);
                ui.navigate(IDEView.class, qp);
            });
        }
    }

    private void openCreateTaskDialog() {
        var dlg = new CreateTaskDialog(taskService, projectService, created -> {
            if (created == null) return;
            // refresh and select created
            refreshTasksIntoSelector();
            suppressSelectEvents = true;
            try {
                taskSelector.setValue(created);
                switchTask(created, true);
            } finally {
                suppressSelectEvents = false;
            }
        });
        dlg.open();
    }

    // create-project dialog extracted to CreateProjectDialog component

    private void showTaskNotFoundView(String slug) {
        Div notFound = new Div();
        notFound.setSizeFull();
        notFound.getStyle().set("display", "flex");
        notFound.getStyle().set("flex-direction", "column");
        notFound.getStyle().set("align-items", "flex-start");
        notFound.getStyle().set("padding", "var(--lumo-space-m)");

        notFound.add(new Span("Task '" + slug + "' not found"));
        notFound.add(new Span("Existing tasks:"));
        var tasks = taskService.listTasks();
        tasks.forEach(t -> {
            Button b = new Button(t.getTitle(), ev -> {
                // navigate to task via router using query params
                getUI().ifPresent(ui -> {
                    Map<String, List<String>> params = Collections.singletonMap("task", Collections.singletonList(t.getSlug()));
                    QueryParameters qp = new QueryParameters(params);
                    ui.navigate(IDEView.class, qp);
                });
            });
            notFound.add(b);
        });

        notFound.add(new Span("Use the task selector in the top toolbar to open a task, or use + to create one."));

        // Replace main content area (below top bar) with notFound
        setMainContent(notFound);
    }

    private void setMainContent(Component content) {
        // remove all components except the three rails (topBar, leftRail, rightRail) which are fixed
        // BaseLayout adds topBar, leftRail, rightRail first; main content comes after. We'll remove anything that's not one of those.
        removeAll();
        // re-add rails from BaseLayout (they are private) -> use getters
        add(getTopBar());
        add(getLeftRail());
        add(getRightRail());
        addAndExpand(content);
    }
}
