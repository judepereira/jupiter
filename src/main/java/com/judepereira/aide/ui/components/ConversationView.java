package com.judepereira.aide.ui.components;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.data.renderer.ComponentRenderer;
import lombok.val;
import org.springframework.ai.chat.messages.Message;

import java.util.Objects;

public class ConversationView extends Grid<Message> {

    public ConversationView() {
        removeAllColumns();
        addColumn(new ComponentRenderer<Component, Message>(entry -> {
            val row = new VerticalLayout();
            row.setPadding(false);
            row.add(new Span(Objects.requireNonNullElse(entry.getText(), "")));
            return row;
        }))
                .setAutoWidth(true)
                .setFlexGrow(1);

        setSelectionMode(SelectionMode.NONE);
        setAllRowsVisible(true);
        addThemeName("no-border");
    }
}
