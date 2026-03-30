package com.judepereira.jupiter.ui.components;

import com.judepereira.jupiter.ai.ChatClientService;
import com.judepereira.jupiter.dtos.ChatMessage;
import com.judepereira.jupiter.ui.TaskContext;
import com.vaadin.flow.component.Key;
import com.vaadin.flow.component.KeyModifier;
import com.vaadin.flow.component.accordion.Accordion;
import com.vaadin.flow.component.accordion.AccordionPanel;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.component.orderedlayout.FlexLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.data.value.ValueChangeMode;
import lombok.extern.log4j.Log4j2;
import lombok.val;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

@Log4j2
public class ChatComposer extends VerticalLayout {

    private final ConversationView conversationView = new ConversationView();
    private final TextArea messageInput = new TextArea();
    private final Checkbox useCmdEnter = new Checkbox("Use ⌘ + Enter to send", true);
    private final Accordion composerAccordion = new Accordion();
    private final AccordionPanel todoPanel;
    private final VerticalLayout todosContent = new VerticalLayout();
    private final TextField addTodoField = new TextField();

    private final ChatClientService chatClientService;
    private final Consumer<ChatMessage> onMessageAdded;
    private final Supplier<TaskContext> activeTaskSupplier;

    public ChatComposer(final ChatClientService chatClientService,
                        final Consumer<ChatMessage> onMessageAdded,
                        final Supplier<TaskContext> activeTaskSupplier) {
        this.chatClientService = chatClientService;
        this.onMessageAdded = onMessageAdded;
        this.activeTaskSupplier = activeTaskSupplier;
        setSizeFull();
        conversationView.setWidthFull();
        conversationView.setMinHeight("0");

        messageInput.setWidthFull();
        messageInput.setPlaceholder("Let's build...");
        messageInput.setValueChangeMode(ValueChangeMode.EAGER);
        // min/max heights already configured above
        messageInput.getStyle().set("resize", "none");
        messageInput.getStyle().set("overflow", "hidden");

        useCmdEnter.getStyle().set("font-size", "var(--lumo-font-size-s)");
        useCmdEnter.getElement().getStyle().set("--vaadin-checkbox-size", "14px");

        messageInput.addKeyDownListener(Key.ENTER, event -> {
            boolean metaPressed = event.getModifiers().contains(KeyModifier.META);
            if (shouldSend(metaPressed)) {
                sendMessage();
            }
        });

        messageInput.setMinHeight("100px");
        messageInput.setMaxHeight("300px");

        // Build todos accordion panel
        var todosLayout = new VerticalLayout();
        todosLayout.setPadding(false);
        todosLayout.setSpacing(false);

        addTodoField.setPlaceholder("Add todo...");
        addTodoField.setWidthFull();

        var addBtn = new com.judepereira.jupiter.ui.components.IconButton(com.vaadin.flow.component.icon.VaadinIcon.PLUS.create());
        addBtn.setLightMode();

        var addRow = new FlexLayout(addTodoField, addBtn);
        addRow.setWidthFull();
        addRow.setAlignItems(FlexLayout.Alignment.CENTER);
        addRow.getStyle().set("gap", "var(--lumo-space-s)");

        todosContent.setPadding(false);
        todosContent.setSpacing(false);
        todosContent.setWidthFull();

        todosLayout.add(todosContent, addRow);

        // Create an explicit AccordionPanel, set its content and add it to the Accordion.
        // This avoids relying on the return type of Accordion.add(...) which may vary
        // between Vaadin versions.
        AccordionPanel panel = new AccordionPanel();
        panel.setContent(todosLayout);
        composerAccordion.add(panel);
        this.todoPanel = panel;
        // collapsed by default
        composerAccordion.close();

        // wire add
        addBtn.addClickListener(ev -> {
            var txt = addTodoField.getValue();
            if (txt == null || txt.trim().isEmpty()) return;
            var taskContext = activeTaskSupplier.get();
            if (taskContext == null) return;
            try {
                taskContext.addTodo(txt.trim());
                addTodoField.clear();
                refreshTodosForActiveTask();
            } catch (Exception ex) {
                com.judepereira.jupiter.ui.components.AppNotifications.showError("Failed to add todo: " + ex.getMessage());
            }
        });

        // refresh when accordion opened
        composerAccordion.addOpenedChangeListener(ev -> {
            if (ev.getOpenedPanel() == todoPanel) {
                refreshTodosForActiveTask();
            }
        });

        VerticalLayout controlPanel = new VerticalLayout(composerAccordion, messageInput, useCmdEnter);
        controlPanel.setPadding(false);

        var conversationWrapper = new FlexLayout();
        conversationWrapper.setSizeFull();
        conversationWrapper.add(conversationView, controlPanel);
        conversationWrapper.setFlexDirection(FlexLayout.FlexDirection.COLUMN);
        conversationWrapper.setFlexGrow(1, conversationView);
        controlPanel.getStyle().setMarginTop("auto");

        add(conversationWrapper);
        expand(conversationWrapper);
    }

    /**
     * Public helper to refresh todos for the currently active task. Safe to call from other views
     * after task switch.
     */
    public void refreshTodosFromTask() {
        // ensure UI access
        getUI().ifPresent(ui -> ui.access(this::refreshTodosForActiveTask));
    }

    // Refreshes the todo list and updates the panel summary. Separated so IDEView/task switches
    // can call into ChatComposer (via activeTaskSupplier) without exposing internals.
    private void refreshTodosForActiveTask() {
        todosContent.removeAll();
        var taskContext = activeTaskSupplier.get();
        if (taskContext == null) return;
        java.util.List<com.judepereira.jupiter.db.entities.Todo> todos;
        try {
            todos = taskContext.listTodos();
        } catch (Exception ex) {
            com.judepereira.jupiter.ui.components.AppNotifications.showError("Failed to load todos: " + ex.getMessage());
            return;
        }

        long completed = todos.stream().filter(t -> t.getCompletedAt() != null).count();
        long total = todos.size();
        todoPanel.setSummaryText("Todos — " + completed + " out of " + total + " complete");

        for (var t : todos) {
            Checkbox cb = new Checkbox(t.getText(), t.getCompletedAt() != null);
            cb.setWidthFull();
            cb.addValueChangeListener(ev -> {
                try {
                    var tc = activeTaskSupplier.get();
                    if (tc == null) return;
                    if (ev.getValue()) {
                        tc.completeTodo(t.getId());
                    } else {
                        tc.reopenTodo(t.getId());
                    }
                    // refresh after update
                    refreshTodosForActiveTask();
                } catch (Exception ex) {
                    com.judepereira.jupiter.ui.components.AppNotifications.showError("Failed to update todo: " + ex.getMessage());
                }
            });
            todosContent.add(cb);
        }
    }

    public void addEntry(ChatMessage entry) {
        conversationView.addMessage(entry);

        if (onMessageAdded != null) {
            onMessageAdded.accept(entry);
        }
    }

    public void setConversation(List<ChatMessage> entries) {
        conversationView.setMessages(entries);
    }

    private boolean shouldSend(boolean metaPressed) {
        return !useCmdEnter.getValue() || metaPressed;
    }


    private void sendMessage() {
        var text = messageInput.getValue() == null ? "" : messageInput.getValue().trim();
        if (text.isEmpty()) {
            return;
        }

        addEntry(new ChatMessage(new UserMessage(text)));
        messageInput.clear();

        var conversation = conversationView.getMessages().stream().map(ChatMessage::getMessage).toList();

        // Get the active task context and ensure it's present before attempting to stream
        val taskContext = this.activeTaskSupplier.get();
        if (taskContext == null) {
            com.judepereira.jupiter.ui.components.AppNotifications.showError("No active task selected. Open or create a task before sending messages.");
            return;
        }

        ChatMessage streamingEntry = new ChatMessage(new AssistantMessage(""));
        conversationView.getUI().ifPresent(ui -> ui.access(() -> conversationView.addMessage(streamingEntry)));

        Thread.ofVirtual().start(() -> {
            StringBuilder content = new StringBuilder();
            var client = taskContext.getChatClientService();
            if (client == null) {
                // task exists but has no task-scoped client: show an error inline and notify
                String current = "[Error: task-scoped chat client unavailable]";
                conversationView.getUI().ifPresent(ui -> ui.access(() -> {
                    streamingEntry.setMessage(new AssistantMessage(current));
                    if (taskContext.equals(this.activeTaskSupplier.get())) {
                        conversationView.refreshMessage(streamingEntry);
                    }
                }));
                com.judepereira.jupiter.ui.components.AppNotifications.showError("Task does not have an associated chat client.");
                if (onMessageAdded != null) {
                    onMessageAdded.accept(streamingEntry);
                }
                return;
            }

            client.streamResponse(conversation)
                    .doOnNext(token -> {
                        if (token == null) {
                            return;
                        }
                        content.append(token);
                        String current = content.toString();
                        if (taskContext.equals(this.activeTaskSupplier.get())) {
                            conversationView.getUI().ifPresent(ui -> ui.access(() -> {
                                streamingEntry.setMessage(new AssistantMessage(current));
                                conversationView.refreshMessage(streamingEntry);
                            }));
                        }
                    })
                    .doOnError(err -> {
                        String current = content + "\n\n[Error: " + err.getMessage() + "]";

                        conversationView.getUI().ifPresent(ui -> ui.access(() -> {
                            streamingEntry.setMessage(new AssistantMessage(current));
                            if (taskContext.equals(this.activeTaskSupplier.get())) {
                                conversationView.refreshMessage(streamingEntry);
                            }
                        }));
                        if (onMessageAdded != null) {
                            onMessageAdded.accept(streamingEntry);
                        }
                    })
                    .doOnComplete(() -> {
                        if (onMessageAdded != null) {
                            onMessageAdded.accept(streamingEntry);
                        }
                    })
                    .blockLast();
        });
    }
}
