package com.judepereira.jupiter.ui.components;

import com.flowingcode.vaadin.addons.markdown.MarkdownViewer;
import com.judepereira.jupiter.dtos.ChatMessage;
import com.vaadin.flow.component.Html;
import com.vaadin.flow.component.accordion.Accordion;
import com.vaadin.flow.component.accordion.AccordionPanel;
import com.vaadin.flow.component.html.Pre;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
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
public class ConversationView extends VerticalLayout {

    @Getter
    private final List<ChatMessage> messages = Collections.synchronizedList(new ArrayList<>());

    public ConversationView() {
    }

    private static @NonNull VerticalLayout renderChatMessage(ChatMessage entry) {
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
                args = args.length() > 200 ? args.substring(0, 200) + "..." : args;
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
        add(renderChatMessage(message));
    }

    public synchronized void setMessages(List<ChatMessage> newMessages) {
        removeAll();
        newMessages.forEach(this::addMessage);
        scrollToEnd();
    }

    public synchronized void refreshMessage(ChatMessage message) {
        log.error("refreshMessage not implemented");
//        messageGridListDataView.refreshItem(message);
//        scrollToEnd();
    }

    public synchronized void scrollToEnd() {
        getElement().executeJs("""
                var el = this;
                el.scrollTo(0, el.scrollHeight);
                """);
    }

    public synchronized void clearMessages() {
        removeAll();
    }

    public synchronized List<ChatMessage> snapshot() {
        return new ArrayList<>(messages);
    }
}
