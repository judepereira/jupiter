package com.judepereira.jupiter2.ui.balloon;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonValue;

import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record SystemBalloon(
        UUID id,
        Type type,
        String title,
        String body,
        Instant createdAt
) {
    public enum Type {
        ERROR("error"),
        SUCCESS("success"),
        WARNING("warning");

        private final String value;

        Type(String value) {
            this.value = value;
        }

        @JsonValue
        public String value() {
            return value;
        }
    }
}
