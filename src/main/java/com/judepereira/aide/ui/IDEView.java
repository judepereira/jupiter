package com.judepereira.aide.ui;

import com.judepereira.aide.ai.ChatClientService;
import com.judepereira.aide.ui.components.ChatComposer;
import com.judepereira.aide.ui.components.ReviewView;
import com.vaadin.flow.component.splitlayout.SplitLayout;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;

@Route(value = "")
@PageTitle("Aide")
class IDEView extends BaseLayout {
    private final ChatComposer chatComposer;
    private final ReviewView reviewView = new ReviewView();
    private final ChatClientService chatClientService;

    IDEView(ChatClientService chatClientService) {
        setSizeFull();
        chatComposer = new ChatComposer(chatClientService);
        SplitLayout splitLayout = new SplitLayout(chatComposer, reviewView);
        splitLayout.setSizeFull();
        splitLayout.setSplitterPosition(62);
        splitLayout.getStyle()
                .set("margin-top", BAR_THICKNESS)
                .set("margin-left", BAR_THICKNESS)
                .set("margin-right", BAR_THICKNESS)
                .set("height", "calc(100vh - " + BAR_THICKNESS + ")")
                .set("width", "calc(100vw - (" + BAR_THICKNESS + " * 2))");

        addAndExpand(splitLayout);
        this.chatClientService = chatClientService;
    }
}
