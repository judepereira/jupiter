package com.judepereira.aide.ui;

import com.judepereira.aide.ui.components.IconButton;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;

import java.util.Arrays;

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

    BaseLayout() {
        setPadding(false);
        setSpacing(false);
        add(buildNavRail(NavRailPosition.TOP, new IconButton(VaadinIcon.HOME.create())));
        add(buildNavRail(NavRailPosition.LEFT, new IconButton(VaadinIcon.COG.create())));
        add(buildNavRail(NavRailPosition.RIGHT, new IconButton(VaadinIcon.LAYOUT.create())));
    }

    private Component buildNavRail(final NavRailPosition pos, final IconButton... buttons) {
        VerticalLayout content = new VerticalLayout();
        content.setPadding(false);

        Arrays.stream(buttons).forEach(content::add);

        if (pos == NavRailPosition.TOP) {
            content.setHeight(BAR_THICKNESS);
            Arrays.stream(buttons).forEach(IconButton::setLightMode);
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
