package com.judepereira.jupiter.ui.components;

import com.judepereira.jupiter.db.entities.Todo;
import com.judepereira.jupiter.dtos.ChatMessage;
import com.judepereira.jupiter.dtos.ToolCallTrace;
import com.judepereira.jupiter.ui.TaskContext;
import com.vaadin.flow.component.Key;
import com.vaadin.flow.component.KeyModifier;
import com.vaadin.flow.component.accordion.Accordion;
import com.vaadin.flow.component.accordion.AccordionPanel;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.orderedlayout.FlexLayout;
import com.vaadin.flow.component.orderedlayout.Scroller;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.value.ValueChangeMode;
import lombok.extern.log4j.Log4j2;
import lombok.val;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

@Log4j2
public class ChatComposer extends VerticalLayout {

    private final ConversationView conversationView = new ConversationView();
    private final TextArea messageInput = new TextArea();
    private final Checkbox useCmdEnter = new Checkbox("Use ⌘ + Enter to send", true);
    private final AccordionPanel todoPanel;
    private final VerticalLayout todosContent = new VerticalLayout();
    private final TextField addTodoField = new TextField();

    private final Consumer<ChatMessage> onMessageAdded;
    private final Supplier<TaskContext> activeTaskSupplier;

    public ChatComposer(final Consumer<ChatMessage> onMessageAdded,
                        final Supplier<TaskContext> activeTaskSupplier) {
        this.onMessageAdded = onMessageAdded;
        this.activeTaskSupplier = activeTaskSupplier;
        setSizeFull();
        conversationView.setWidthFull();
        conversationView.setMinHeight("0");

        messageInput.setWidthFull();
        messageInput.setPlaceholder("Let's build...");
        messageInput.setValueChangeMode(ValueChangeMode.EAGER);

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

        var todosLayout = new VerticalLayout();
        todosLayout.setPadding(false);
        todosLayout.setSpacing(false);

        addTodoField.setPlaceholder("Add a TODO...");
        addTodoField.setWidthFull();

        var addRow = new FlexLayout(addTodoField);
        addRow.setWidthFull();
        addRow.setAlignItems(FlexLayout.Alignment.CENTER);
        addRow.getStyle().set("gap", "var(--lumo-space-s)");

        todosContent.setPadding(false);
        todosContent.setSpacing(false);
        todosContent.setWidthFull();

        todosLayout.add(todosContent, addRow);

        AccordionPanel panel = new AccordionPanel();
        panel.add(todosLayout);
        Accordion composerAccordion = new Accordion();
        composerAccordion.setWidthFull();
        composerAccordion.add(panel);
        this.todoPanel = panel;

        addTodoField.setValueChangeMode(ValueChangeMode.EAGER);

        addTodoField.addKeyDownListener(Key.ENTER, ev -> {
            var txt = addTodoField.getValue();
            if (txt == null || txt.trim().isEmpty()) {
                return;
            }
            var taskContext = activeTaskSupplier.get();
            if (taskContext == null) {
                return;
            }
            try {
                taskContext.addTodo(txt.trim());
                addTodoField.clear();
                refreshTodosForActiveTask();
            } catch (Exception ex) {
                AppNotifications.showError("Failed to add todo: " + ex.getMessage());
            }
        });

        composerAccordion.addOpenedChangeListener(ev -> {
            if (ev.getOpenedPanel().isPresent() && ev.getOpenedPanel().get() == todoPanel) {
                refreshTodosForActiveTask();
            }
        });

        composerAccordion.close();

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

    public void refreshTodosFromTask() {
        refreshTodosForActiveTask();
    }

    private void refreshTodosForActiveTask() {
        log.info("Refreshing todos for active task");
        todosContent.removeAll();
        var taskContext = activeTaskSupplier.get();
        if (taskContext == null) {
            log.warn("No active task found");
            return;
        }
        List<Todo> todos;
        try {
            todos = taskContext.listTodos();
        } catch (Exception ex) {
            AppNotifications.showError("Failed to load todos: " + ex.getMessage());
            return;
        }

        long completed = todos.stream().filter(t -> t.getCompletedAt() != null).count();
        long total = todos.size();
        if (total == 0) {
            todoPanel.setSummaryText("No TODOs :)");
        } else {
            todoPanel.setSummaryText("TODOs — " + completed + "/" + total + " complete");
        }

        val wrapper = new VerticalLayout();
        wrapper.setWidthFull();
        wrapper.setSpacing(false);

        for (var t : todos) {
            Checkbox cb = new Checkbox(t.getText(), t.getCompletedAt() != null);
            cb.setWidthFull();
            cb.addValueChangeListener(ev -> {
                try {
                    var tc = activeTaskSupplier.get();
                    if (tc == null) {
                        return;
                    }
                    if (ev.getValue()) {
                        tc.completeTodo(t.getId());
                    } else {
                        tc.reopenTodo(t.getId());
                    }

                    refreshTodosForActiveTask();
                } catch (Exception ex) {
                    AppNotifications.showError("Failed to update todo: " + ex.getMessage());
                }
            });
            wrapper.add(cb);
        }

        val scroller = new Scroller(wrapper);
        scroller.setWidthFull();
        scroller.setMaxHeight("150px");

        todosContent.add(scroller);
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


        val taskContext = this.activeTaskSupplier.get();
        if (taskContext == null) {
            AppNotifications.showError("No active task selected. Open or create a task before sending messages.");
            return;
        }

        addEntry(new ChatMessage(new UserMessage(text)));
        messageInput.clear();

        var conversation = conversationView.getMessages().stream().map(ChatMessage::getMessage).filter(Objects::nonNull).toList();

        Thread.ofVirtual().start(() -> {
            StringBuilder content = new StringBuilder();
            var client = taskContext.getChatClientService();

            var projectRoot = taskContext.getTask().getProjects().stream()
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("Active task has no associated project; cannot determine project root"))
                    .getPath();

            final Object assistantLock = new Object();
            final ChatMessage[] streamingEntry = new ChatMessage[1];
            client.streamResponse(taskContext.getTools(), conversation, projectRoot,
                    (ToolCallTrace trace) -> {
                        synchronized (assistantLock) {
                            if (!taskContext.equals(this.activeTaskSupplier.get())) {
                                return;
                            }
                            ChatMessage tm = new ChatMessage(trace);
                            conversationView.getUI().ifPresent(ui -> ui.access(() -> {
                                if (!taskContext.equals(this.activeTaskSupplier.get())) {
                                    return;
                                }
                                if (streamingEntry[0] != null) {
                                    var snapshot = conversationView.snapshot();
                                    int idx = snapshot.indexOf(streamingEntry[0]);
                                    if (idx >= 0) {
                                        snapshot.add(idx, tm);
                                    } else {
                                        snapshot.add(tm);
                                    }
                                    conversationView.setMessages(snapshot);
                                } else {
                                    conversationView.addMessage(tm);
                                }

                                refreshTodosForActiveTask();
                                if (onMessageAdded != null) onMessageAdded.accept(tm);
                            }));
                        }
                    })
                    .doOnNext(token -> {
                        if (token == null) {
                            return;
                        }
                        content.append(token);
                        String current = content.toString();

                        synchronized (assistantLock) {
                            if (streamingEntry[0] == null) {
                                streamingEntry[0] = new ChatMessage(new AssistantMessage(current));
                                conversationView.getUI().ifPresent(ui -> ui.access(() -> {
                                    if (!taskContext.equals(this.activeTaskSupplier.get())) {
                                        return;
                                    }
                                    conversationView.addMessage(streamingEntry[0]);
                                }));
                            } else {
                                conversationView.getUI().ifPresent(ui -> ui.access(() -> {
                                    if (!taskContext.equals(this.activeTaskSupplier.get())) {
                                        return;
                                    }
                                    streamingEntry[0].setMessage(new AssistantMessage(current));
                                    conversationView.refreshMessage(streamingEntry[0]);
                                }));
                            }
                        }
                    })
                    .doOnError(err -> {
                        String current = content + "\n\n[Error: " + err.getMessage() + "]";

                        synchronized (assistantLock) {
                            if (streamingEntry[0] == null) {
                                streamingEntry[0] = new ChatMessage(new AssistantMessage(current));
                                conversationView.getUI().ifPresent(ui -> ui.access(() -> {
                                    if (!taskContext.equals(this.activeTaskSupplier.get())) {
                                        return;
                                    }
                                    conversationView.addMessage(streamingEntry[0]);
                                }));
                            } else {
                                conversationView.getUI().ifPresent(ui -> ui.access(() -> {
                                    streamingEntry[0].setMessage(new AssistantMessage(current));
                                    if (taskContext.equals(this.activeTaskSupplier.get())) {
                                        conversationView.refreshMessage(streamingEntry[0]);
                                    }
                                }));
                            }
                        }
                        if (onMessageAdded != null && taskContext.equals(this.activeTaskSupplier.get())) {
                            onMessageAdded.accept(streamingEntry[0]);
                        }
                    })
                    .doOnComplete(() -> {
                        if (onMessageAdded != null && streamingEntry[0] != null && taskContext.equals(this.activeTaskSupplier.get())) {
                            onMessageAdded.accept(streamingEntry[0]);
                        }
                    })
                    .blockLast();
        });
    }
}
