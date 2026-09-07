package com.judepereira.jupiter.security;

import lombok.RequiredArgsConstructor;
import org.flywaydb.core.api.callback.BaseCallback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class EncryptionMigrationCallback extends BaseCallback {
    private final EncryptionMigrationService migration;

    @Override
    public boolean supports(Event event, Context context) {
        return event == Event.AFTER_MIGRATE;
    }

    @Override
    public boolean canHandleInTransaction(Event event, Context context) {
        return false;
    }

    @Override
    public void handle(Event event, Context context) {
        migration.run(context.getConnection());
    }

    @Override
    public String getCallbackName() {
        return "encryption migration";
    }
}
