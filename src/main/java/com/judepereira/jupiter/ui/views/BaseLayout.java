package com.judepereira.jupiter.ui.views;

import com.judepereira.jupiter.ui.components.IconButton;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.ThemableLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import lombok.Getter;

import java.util.Arrays;

@Getter
public abstract class BaseLayout extends VerticalLayout {

    private enum NavRailPosition {
        LEFT("left", "border-right"),
        RIGHT("right", "border-left"),
        TOP("top", "");

        NavRailPosition(String cssRule, String cssBorderAttr) {
            this.cssRule = cssRule;
            this.cssBorderAttr = cssBorderAttr;
        }

        private final String cssRule;
        private final String cssBorderAttr;
    }

    public static final String BAR_THICKNESS = "39px";

    private final HorizontalLayout topBar;
    private final VerticalLayout leftRail;
    private final VerticalLayout rightRail;

    BaseLayout() {
        setPadding(false);
        setSpacing(false);

        topBar = buildNavRail(new HorizontalLayout(), NavRailPosition.TOP, new IconButton(VaadinIcon.HOME.create()));
        leftRail = buildNavRail(new VerticalLayout(), NavRailPosition.LEFT, new IconButton(VaadinIcon.COG.create()));
        rightRail = buildNavRail(new VerticalLayout(), NavRailPosition.RIGHT, new IconButton(VaadinIcon.LAYOUT.create()));

        add(topBar);
        add(leftRail);
        add(rightRail);
    }

    private <T extends FlexComponent & ThemableLayout> T buildNavRail(final T content, final NavRailPosition pos, final IconButton... buttons) {
        content.setPadding(false);

        Arrays.stream(buttons).forEach(content::add);

        if (pos == NavRailPosition.TOP) {
            content.setHeight(BAR_THICKNESS);
            Arrays.stream(buttons).forEach(IconButton::setLightMode);
            content.setWidthFull();
        } else {
            content.setWidth(BAR_THICKNESS);
        }

        content.getStyle()
                .set("position", "fixed")
                .set(pos.cssRule, "0")
                .set("bottom", "0")
                .set("padding", "var(--lumo-space-xs)");

        if (pos == NavRailPosition.TOP) {
            content.getStyle().set("background", "var(--lumo-contrast-90pct)");
            content.getStyle().set("top", "0");
        } else {
            content.getStyle().set("background", "var(--lumo-contrast-5pct)");
            content.getStyle().set("top", BAR_THICKNESS);
        }


        if (!pos.cssBorderAttr.isEmpty()) {
            content.getStyle().set(pos.cssBorderAttr, "1px solid var(--lumo-contrast-10pct)");
        }

        return content;
    }
}
