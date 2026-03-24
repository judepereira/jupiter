package com.judepereira.jupiter.ui.views;

import com.judepereira.jupiter.db.entities.Task;
import com.judepereira.jupiter.db.repos.TaskService;
import com.judepereira.jupiter.db.services.ProjectService;
import com.judepereira.jupiter.ui.components.CreateTaskDialog;
import com.vaadin.flow.component.Html;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.ListItem;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.html.UnorderedList;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.dom.Style;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouteParameters;
import com.vaadin.flow.router.RouterLink;
import lombok.val;

import java.util.List;

@Route("")
public class RootView extends VerticalLayout {

    private final ProjectService projectService;

    public RootView(final TaskService taskService, ProjectService projectService) {
        setSizeFull();
        setPadding(false);
        setJustifyContentMode(FlexComponent.JustifyContentMode.CENTER);
        setAlignItems(FlexComponent.Alignment.CENTER);

        H1 title = new H1("Welcome to Jupiter");
        title.getStyle().setMarginBottom("16px");
        title.getStyle().setFontWeight("400");

        val newTaskButton = new Button("create a new one");
        newTaskButton.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE);

        Span subtitle = new Span(new Span("Select an existing task or "),
                newTaskButton, new Span(" to get started."));

        subtitle.getStyle().setMaxWidth("550px");
        subtitle.getStyle().setTextAlign(Style.TextAlign.CENTER);

        newTaskButton.addClickListener(_ -> {
            val d = new CreateTaskDialog(taskService, projectService, task ->
                    getUI().ifPresent(ui -> ui.navigate(IDEView.class,
                            new RouteParameters("task", task.getSlug()))));
            d.open();
        });

        add(title, subtitle);

        List<Task> tasks = taskService.listTasks();

        if (tasks.isEmpty()) {
            subtitle.removeAll();
            newTaskButton.setText("Create a new task");
            subtitle.add(new Span("Tasks form a key aspect of using Jupiter. " +
                            "They span one or more projects, which are git repositories that are checked out. "),
                    new Html("<br>"), new Html("<br>"),
                    newTaskButton, new Span(" to get started."));
        } else {
            UnorderedList ul = new UnorderedList();
            ul.getStyle().set("list-style", "none");
            ul.getStyle().set("padding", "0");
            ul.getStyle().set("margin", "0");

            for (Task t : tasks) {
                RouterLink link = new RouterLink(t.getTitle(), IDEView.class, new RouteParameters("task", t.getSlug()));
                Span slug = new Span(" — " + t.getSlug());
                slug.getStyle().set("color", "var(--lumo-secondary-text-color)");
                slug.getStyle().set("font-size", "0.9em");

                ListItem li = new ListItem(link, slug);
                li.getStyle().set("padding", "3px 0");
                ul.add(li);
            }

            add(ul);
        }
        this.projectService = projectService;
    }
}
