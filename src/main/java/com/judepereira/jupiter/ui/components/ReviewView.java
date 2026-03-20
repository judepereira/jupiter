package com.judepereira.jupiter.ui.components;

import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;

public class ReviewView extends VerticalLayout {

     public ReviewView() {
         add(new Span("No files modified yet."));
     }
}
