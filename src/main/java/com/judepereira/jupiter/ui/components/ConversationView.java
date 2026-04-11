package com.judepereira.jupiter.ui.components;

import com.flowingcode.vaadin.addons.markdown.MarkdownViewer;
import com.judepereira.jupiter.dtos.ChatMessage;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.grid.dataview.GridListDataView;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.data.renderer.ComponentRenderer;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import lombok.val;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

@Log4j2
public class ConversationView extends Grid<ChatMessage> {

    @Getter
    private final List<ChatMessage> messages = Collections.synchronizedList(new ArrayList<>());
    private final GridListDataView<ChatMessage> messageGridListDataView;

    public ConversationView() {
        addThemeVariants(GridVariant.LUMO_WRAP_CELL_CONTENT);
        addColumn(new ComponentRenderer<Component, ChatMessage>(entry -> {
            val row = new VerticalLayout();
            row.setPadding(false);
            if (entry.getToolTrace() != null) {
                var t = entry.getToolTrace();
                StringBuilder sb = new StringBuilder();
                sb.append("**Tool:** ").append(t.toolName()).append("\n\n");
                if (t.startedAt() != null) {
                    sb.append("**Started At:** ").append(t.startedAt().toString()).append("\n\n");
                }
                if (t.durationMillis() != null) {
                    sb.append("**Duration (ms):** ").append(t.durationMillis().toString()).append("\n\n");
                }
                sb.append("**Args:**\n```").append(t.toolArgsPayload() == null ? "" : t.toolArgsPayload()).append("```\n\n");
                if (t.toolResultPayload() != null) {
                    sb.append("**Result:**\n```").append(t.toolResultPayload()).append("```\n");
                }
                if (t.toolErrorPayload() != null) {
                    sb.append("**Error:**\n```").append(t.toolErrorPayload()).append("```\n");
                }
                MarkdownViewer md = new MarkdownViewer(sb.toString());
                md.setWidthFull();
                row.add(md);
                return row;
            } else {
                MarkdownViewer md = new MarkdownViewer(Objects.requireNonNullElse(entry.getMessage().getText(), ""));
                md.setWidthFull();
                row.add(md);
                return row;
            }
        }))
                .setAutoWidth(true)
                .setFlexGrow(1);

        setSelectionMode(SelectionMode.NONE);
        setAllRowsVisible(true);
        addThemeName("no-border");
        this.messageGridListDataView = setItems(messages);
    }

    public synchronized void addMessage(ChatMessage message) {
        messages.add(message);
        messageGridListDataView.refreshAll();
        scrollToItem(message);
    }

    public synchronized void setMessages(List<ChatMessage> newMessages) {
        messages.clear();
        if (newMessages != null && !newMessages.isEmpty()) {
            messages.addAll(newMessages);
        }
        messageGridListDataView.refreshAll();
        if (!messages.isEmpty()) {
            scrollToItem(messages.getLast());
        }
    }

    public synchronized void refreshMessage(ChatMessage message) {
        messageGridListDataView.refreshItem(message);
        scrollToItem(message);
    }

    public synchronized void clearMessages() {
        messages.clear();
        messageGridListDataView.refreshAll();
    }

    public synchronized List<ChatMessage> snapshot() {
        return new ArrayList<>(messages);
    }
}
