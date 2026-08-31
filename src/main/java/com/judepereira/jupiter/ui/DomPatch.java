package com.judepereira.jupiter.ui;

/** A renderer-neutral instruction for one DOM update. */
public record DomPatch(String html, String targetId, String swapMode) {
}
