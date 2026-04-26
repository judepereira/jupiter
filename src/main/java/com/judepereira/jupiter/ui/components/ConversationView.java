package com.judepereira.jupiter.ui.components;

import com.judepereira.jupiter.dtos.ChatMessage;
import com.vaadin.flow.component.messages.MessageList;
import com.vaadin.flow.component.messages.MessageListItem;
import com.vaadin.flow.component.orderedlayout.Scroller;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import lombok.val;
import org.apache.commons.lang3.StringUtils;

import java.util.*;

@Log4j2
public class ConversationView extends VerticalLayout {

    @Getter
    private final List<ChatMessage> messages = Collections.synchronizedList(new ArrayList<>());
    private final MessageList messageList = new MessageList();
    private final Scroller scroller = new Scroller(messageList);

    public ConversationView() {
        setPadding(false);
        setWidthFull();
        scroller.setWidthFull();
        messageList.setMarkdown(true);
        messageList.setItems(new LinkedList<>());
        addAndExpand(scroller);
    }

    private final Map<ChatMessage, MessageListItem> messageToItem = new HashMap<>();

    private MessageListItem renderChatMessage(ChatMessage entry) {
        val md = new StringBuilder();
        String username;

        if (entry.getToolTrace() != null) {
            var t = entry.getToolTrace();

            username = "Jupiter";

            md.append("**%s**".formatted(t.toolName()));

            if (StringUtils.isNotBlank(t.toolArgsPayload())) {
                var args = t.toolArgsPayload();
                args = args.length() > 200 ? args.substring(0, 200) + "..." : args;
                md.append(": `%s`".formatted(args));
            }

            if (StringUtils.isNotBlank(t.toolResultPayload())) {
                md.append("<details>%s</details>".formatted(t.toolResultPayload()));
            }

            if (StringUtils.isNotBlank(t.toolErrorPayload())) {
                md.append("<details>%s</details>".formatted(t.toolErrorPayload()));
            }

        } else {
            md.append(entry.getMessage().getText());
            username = switch (entry.getMessage().getMessageType()) {
                case USER -> "You";
                default -> "Jupiter";
            };
        }
        MessageListItem item = new MessageListItem(md.toString());
        item.setUserName(username);
        return item;
    }

    public synchronized void addMessage(ChatMessage message) {
        messageToItem.computeIfAbsent(message, (_) -> {
            val item = renderChatMessage(message);
            messageList.addItem(item);
            messages.add(message);
            return item;
        });
    }

    public synchronized void setMessages(List<ChatMessage> newMessages) {
        messageList.setItems(new LinkedList<>());
        newMessages.forEach(this::addMessage);
        scroller.scrollToBottom();
    }

    public synchronized void appendText(final String text, ChatMessage message) {
        val vl = messageToItem.get(message);
        vl.appendText(text);
    }

    public synchronized void clearMessages() {
        messageList.setItems(new LinkedList<>());
    }

    public synchronized List<ChatMessage> snapshot() {
        return new ArrayList<>(messages);
    }
}
