package com.judepereira.jupiter.ui.components;

import com.judepereira.jupiter.ai.ChatClientService;
import com.judepereira.jupiter.dtos.ChatMessage;
import com.vaadin.flow.component.Key;
import com.vaadin.flow.component.KeyModifier;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.orderedlayout.FlexLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.data.value.ValueChangeMode;
import java.util.function.Consumer;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import java.util.List;

public class ChatComposer extends VerticalLayout {

    private final ConversationView conversationView = new ConversationView();
    private final TextArea messageInput = new TextArea();
    private final Checkbox useCmdEnter = new Checkbox("Use ⌘ + Enter to send", true);

    private final ChatClientService chatClientService;
    private Consumer<ChatMessage> onMessageAdded;

    public ChatComposer(final ChatClientService chatClientService) {
        this(chatClientService, null);
    }

    /**
     * Backwards-compatible constructor which accepts an optional callback that's invoked
     * whenever a message is appended to the conversation (user or assistant). The callback
     * is not invoked when a conversation is loaded via setConversation(...).
     */
    public ChatComposer(final ChatClientService chatClientService, Consumer<ChatMessage> onMessageAdded) {
        this.chatClientService = chatClientService;
        this.onMessageAdded = onMessageAdded;
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
        // invoke callback exactly once when message is added to view
        if (onMessageAdded != null) onMessageAdded.accept(entry);
    }

    public void setConversation(List<ChatMessage> entries) {
        conversationView.setMessages(entries);
    }

    public void setOnMessageAdded(Consumer<ChatMessage> onMessageAdded) {
        this.onMessageAdded = onMessageAdded;
    }

    public void clearConversation() {
        conversationView.clearMessages();
    }

    public List<ChatMessage> getConversationSnapshot() {
        return conversationView.snapshot();
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

        Thread.ofVirtual().start(() -> {
            AssistantMessage response = new AssistantMessage(chatClientService.getResponse(conversationView.getMessages().stream().map(ChatMessage::getMessage).toList()));
            getUI().ifPresent(ui -> ui.access(() -> addEntry(new ChatMessage(response))));
        });
    }
}
