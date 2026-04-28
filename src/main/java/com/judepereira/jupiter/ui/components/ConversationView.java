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

    private String toolTraceMarkdown(ChatMessage entry) {
        var t = entry.getToolTrace();
        val md = new StringBuilder();
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

        return md.toString();
    }

    private MessageListItem renderChatMessage(ChatMessage entry) {
        final String content;
        final String username;

        if (entry.getToolTrace() != null) {
            content = toolTraceMarkdown(entry);
            username = "Jupiter";
        } else {
            content = entry.getMessage().getText();
            username = switch (entry.getMessage().getMessageType()) {
                case USER -> "You";
                default -> "Jupiter";
            };
        }

        MessageListItem item = new MessageListItem(content);
        item.setUserName(username);
        return item;
    }

    public synchronized void addMessage(ChatMessage message) {
        if (message.getToolTrace() != null) {
            ChatMessage last = messages.isEmpty() ? null : messages.get(messages.size() - 1);

            if (last != null && last.getToolTrace() != null) {
                var existingItem = messageToItem.get(last);
                if (existingItem == null) {
                    throw new IllegalStateException("Missing MessageListItem mapping for previous tool message");
                }

                String md = toolTraceMarkdown(message);
                existingItem.appendText("\n\n" + md);
                messages.add(message);
                messageToItem.put(message, existingItem);
                return;
            }
        }

        val item = renderChatMessage(message);
        messageList.addItem(item);
        messages.add(message);
        messageToItem.put(message, item);
    }

    public synchronized void setMessages(List<ChatMessage> newMessages) {
        messageList.setItems(new LinkedList<>());
        messages.clear();
        messageToItem.clear();
        newMessages.forEach(this::addMessage);
        scroller.scrollToBottom();
    }

    public synchronized void appendText(final String text, ChatMessage message) {
        val vl = messageToItem.get(message);
        vl.appendText(text);
    }

    public synchronized void clearMessages() {
        messageList.setItems(new LinkedList<>());
        messages.clear();
        messageToItem.clear();
    }

    public synchronized List<ChatMessage> snapshot() {
        return new ArrayList<>(messages);
    }
}
