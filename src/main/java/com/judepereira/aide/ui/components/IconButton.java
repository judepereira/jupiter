package com.judepereira.aide.ui.components;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.icon.Icon;

public class IconButton extends Button {

    public IconButton(Icon icon) {
        super(icon);
        addThemeVariants(ButtonVariant.LUMO_ICON, ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY);
        getStyle().setColor("var(--lumo-contrast-60pct)");
        getStyle().setPadding("0");
        getStyle().setMargin("0");
    }

    public void setLightMode() {
        getStyle().setColor("white");
    }
}
