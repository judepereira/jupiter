package com.judepereira.jupiter.ui.components;

import com.judepereira.jupiter.dtos.ChatMessage;
import com.vaadin.flow.component.messages.MessageList;
import com.vaadin.flow.component.messages.MessageListItem;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import lombok.val;
import org.apache.commons.lang3.StringUtils;

import java.util.*;

@Log4j2
public class ConversationView extends MessageList {

    @Getter
    private final List<ChatMessage> messages = Collections.synchronizedList(new ArrayList<>());

    public ConversationView() {
        setMarkdown(true);
        setItems(new LinkedList<>());
    }

    private final Map<ChatMessage, MessageListItem> messageToItem = new HashMap<>();

    private MessageListItem renderChatMessage(ChatMessage entry) {
        val md = new StringBuilder();

        if (entry.getToolTrace() != null) {
            var t = entry.getToolTrace();

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
        }
        MessageListItem item = new MessageListItem(md.toString());
        return item;
    }

    public synchronized void addMessage(ChatMessage message) {
        messageToItem.computeIfAbsent(message, (_) -> {
            val item = renderChatMessage(message);
            addItem(item);
            messages.add(message);
            return item;
        });
    }

    public synchronized void setMessages(List<ChatMessage> newMessages) {
        setItems(new LinkedList<>());
        newMessages.forEach(this::addMessage);
    }

    public synchronized void appendText(final String text, ChatMessage message) {
        val vl = messageToItem.get(message);
        vl.appendText(text);
    }

    public synchronized void clearMessages() {
        setItems(new LinkedList<>());
    }

    public synchronized List<ChatMessage> snapshot() {
        return new ArrayList<>(messages);
    }
}
