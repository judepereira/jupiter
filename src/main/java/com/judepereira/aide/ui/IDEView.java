package com.judepereira.aide.ui;

import com.judepereira.aide.ui.components.ChatComposer;
import com.judepereira.aide.ui.components.ReviewView;
import com.judepereira.aide.ui.entities.ChatEntry;
import com.vaadin.flow.component.splitlayout.SplitLayout;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;

@Route(value = "")
@PageTitle("Aide")
class IDEView extends BaseLayout {
    private final ChatComposer chatComposer = new ChatComposer();
    private final ReviewView reviewView = new ReviewView();

    IDEView() {
        setSizeFull();

        SplitLayout splitLayout = new SplitLayout(chatComposer, reviewView);
        splitLayout.setSizeFull();
        splitLayout.setSplitterPosition(62);
        splitLayout.getStyle()
                .set("margin-top", BAR_THICKNESS)
                .set("margin-left", BAR_THICKNESS)
                .set("margin-right", BAR_THICKNESS)
                .set("height", "calc(100vh - " + BAR_THICKNESS + ")")
                .set("width", "calc(100vw - (" + BAR_THICKNESS + " * 2))");

        chatComposer.setItems(
                new ChatEntry(false, "Hello, how can I assist you today?"),
                new ChatEntry(true, "I need help with this issue"),
                new ChatEntry(false, "Sure, let me look into it."),
                new ChatEntry(true, "Lorem ipsum..."));

        addAndExpand(splitLayout);
    }
}
