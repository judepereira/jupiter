package com.judepereira.jupiter.ui.components;

import com.flowingcode.vaadin.addons.markdown.MarkdownViewer;
import com.judepereira.jupiter.dtos.ChatMessage;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.Html;
import com.vaadin.flow.component.accordion.Accordion;
import com.vaadin.flow.component.accordion.AccordionPanel;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.grid.dataview.GridListDataView;
import com.vaadin.flow.component.html.Pre;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.data.renderer.ComponentRenderer;
import com.vaadin.flow.dom.Style;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import lombok.val;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.NonNull;

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

                val title = new Span();
                title.add(new Html("<strong>%s</strong>".formatted(t.toolName())));

                val panel = new AccordionPanel(title);
                panel.setWidthFull();

                if (StringUtils.isNotBlank(t.toolArgsPayload())) {
                    var args = t.toolArgsPayload();
                    args = args.length()> 200? args.substring(0, 200) + "..." : args;
                    Html argsEl = new Html(" <code>%s</code>".formatted(args));
                    argsEl.getStyle().setFont("monospace");
                    title.add(new Span(": "), argsEl);
                }

                if (StringUtils.isNotBlank(t.toolResultPayload())) {
                    panel.add(pre(t.toolResultPayload()));
                }

                if (StringUtils.isNotBlank(t.toolErrorPayload())) {
                    panel.add(pre(t.toolErrorPayload()));
                }

                val accordion = new Accordion();
                accordion.add(panel);
                accordion.close();

                row.add(accordion);
            } else {
                MarkdownViewer md = new MarkdownViewer(Objects.requireNonNullElse(entry.getMessage().getText(), ""));
                md.setWidthFull();
                row.add(md);
            }
            return row;
        }))
                .setAutoWidth(true)
                .setFlexGrow(1);

        setSelectionMode(SelectionMode.NONE);
        setAllRowsVisible(true);
        addThemeName("no-border");
        this.messageGridListDataView = setItems(messages);
    }

    private static @NonNull Pre pre(String text) {
        Pre pre = new Pre(text);
        pre.setWidthFull();
        pre.getStyle().setPadding("15px");
        pre.getStyle().setBoxSizing(Style.BoxSizing.BORDER_BOX);
        pre.getStyle().set("text-wrap", "wrap");
        pre.setWidthFull();
        return pre;
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
