package com.judepereira.aide.ui.components;

import com.flowingcode.vaadin.addons.markdown.MarkdownViewer;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridVariant;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.markdown.Markdown;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.data.renderer.ComponentRenderer;
import lombok.val;
import org.springframework.ai.chat.messages.Message;

import java.util.Objects;

public class ConversationView extends Grid<Message> {

    public ConversationView() {
        addThemeVariants(GridVariant.LUMO_WRAP_CELL_CONTENT);
        addColumn(new ComponentRenderer<Component, Message>(entry -> {
            val row = new VerticalLayout();
            row.setPadding(false);
            row.add(new MarkdownViewer(Objects.requireNonNullElse(entry.getText(), "")));
            return row;
        }))
                .setAutoWidth(true)
                .setFlexGrow(1);

        setSelectionMode(SelectionMode.NONE);
        setAllRowsVisible(true);
        addThemeName("no-border");
    }
}
