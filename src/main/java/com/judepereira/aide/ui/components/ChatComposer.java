package com.judepereira.aide.ui.components;

import com.judepereira.aide.ai.ChatClientService;
import com.vaadin.flow.component.Key;
import com.vaadin.flow.component.KeyModifier;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.orderedlayout.FlexLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.data.value.ValueChangeMode;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

public class ChatComposer extends VerticalLayout {

    private final ConversationView conversationView = new ConversationView();
    private final TextArea messageInput = new TextArea();
    private final Checkbox useCmdEnter = new Checkbox("Use ⌘ + Enter to send", true);
    private final List<Message> messages = new ArrayList<>();
    private final ChatClientService chatClientService;

    public ChatComposer(final ChatClientService chatClientService) {
        this.chatClientService = chatClientService;
        setSizeFull();
        //todo
//        setThemeVariants(VerticalLayoutVariant.LUMO_SPACING_XS, VerticalLayoutVariant.LUMO_PADDING);

        var conversationWrapper = new FlexLayout(conversationView);
        conversationWrapper.setSizeFull();

        conversationView.setWidthFull();
        conversationView.setHeightFull();

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

        add(conversationWrapper, messageInput, useCmdEnter);
        expand(conversationWrapper);
    }

    public void addEntry(Message entry) {
        messages.add(entry);
        refreshConversation();
    }

    private boolean shouldSend(boolean metaPressed) {
        return !useCmdEnter.getValue() || metaPressed;
    }

    private void sendMessage() {
        var text = messageInput.getValue() == null ? "" : messageInput.getValue().trim();
        if (text.isEmpty()) {
            return;
        }

        addEntry(new UserMessage(text));
        messageInput.clear();

        Thread.ofVirtual().start(() -> {
            AssistantMessage response = new AssistantMessage(chatClientService.getResponse(messages));

            getUI().ifPresent(ui -> ui.access(() -> {
                addEntry(response);
                refreshConversation();
            }));
        });
    }

    private void refreshConversation() {
        conversationView.setItems(messages);
    }
}


