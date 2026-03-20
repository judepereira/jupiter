package com.judepereira.jupiter.ui.components;

import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;

/**
 * Shared notification helpers for consistent application-wide behavior.
 */
public final class AppNotifications {

    private static final int DEFAULT_DURATION_MS = 5_000;

    private AppNotifications() {
    }

    public static Notification show(String message) {
        return show(message, null);
    }

    public static Notification showError(String message) {
        return show(message, NotificationVariant.LUMO_ERROR);
    }

    private static Notification show(String message, NotificationVariant variant) {
        Notification notification = Notification.show(message, DEFAULT_DURATION_MS, Notification.Position.TOP_END);
        if (variant != null) {
            notification.addThemeVariants(variant);
        }
        return notification;
    }
}
