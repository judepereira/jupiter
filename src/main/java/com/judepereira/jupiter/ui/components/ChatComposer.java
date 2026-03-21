package com.judepereira.jupiter.ui.components;

import com.judepereira.jupiter.ai.ChatClientService;
import com.judepereira.jupiter.dtos.ChatMessage;
import com.judepereira.jupiter.ui.TaskContext;
import com.vaadin.flow.component.Key;
import com.vaadin.flow.component.KeyModifier;
import com.vaadin.flow.component.checkbox.Checkbox;
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
        messageInput.setMinHeight("100px");
        messageInput.setMaxHeight("300px");
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

        VerticalLayout controlPanel = new VerticalLayout(messageInput, useCmdEnter);
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

        ChatMessage streamingEntry = new ChatMessage(new AssistantMessage(""));
        conversationView.getUI().ifPresent(ui -> ui.access(() -> conversationView.addMessage(streamingEntry)));

        val taskContext = this.activeTaskSupplier.get();

        Thread.ofVirtual().start(() -> {
            StringBuilder content = new StringBuilder();
            chatClientService.streamResponse(conversation)
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
