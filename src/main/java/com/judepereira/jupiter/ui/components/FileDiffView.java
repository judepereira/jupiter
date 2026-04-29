package com.judepereira.jupiter.ui.components;

import com.judepereira.jupiter.git.GitFileDiff;
import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.html.Pre;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import lombok.Getter;

/**
 * Renders a single file diff. Simple constructor to allow swapping implementation later.
 */
public class FileDiffView extends VerticalLayout {

    @Getter
    private final GitFileDiff gitFileDiff;

    public FileDiffView(GitFileDiff gitFileDiff) {
        this.gitFileDiff = gitFileDiff;
        setPadding(false);
        setWidthFull();

        H4 title = new H4(gitFileDiff.getPath());
        Pre diff = new Pre(gitFileDiff.getDiff());
        diff.getStyle().set("white-space", "pre-wrap");
        add(title, diff);
    }
}
