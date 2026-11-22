export function getHtmxRequestPath(evt) {
    const ctx = evt?.detail?.ctx;
    return typeof ctx?.request?.action === "string" ? ctx.request.action : "";
}

export function getChatComposerForm() {
    return document.getElementById("chat-send-form");
}

export function getChatTextarea() {
    return document.getElementById("chat-input");
}

export function getCurrentOpenSubagentSessionId() {
    try {
        const container = document.getElementById("chat-container");
        const sessionId =
            container && container.dataset && container.dataset.subagentSessionId != null
                ? String(container.dataset.subagentSessionId).trim()
                : "";
        return sessionId || "";
    } catch (_) {
        return "";
    }
}

export function processHtmxElement(element) {
    try {
        if (!element || (element.dataset && element.dataset.htmxProcessed === "true")) {
            return;
        }
        if (window.htmx && typeof window.htmx.process === "function") {
            window.htmx.process(element);
        }
        element.dataset.htmxProcessed = "true";
    } catch (_) {}
}

export function focusChatInput(preferredTextarea) {
    try {
        const textarea = preferredTextarea && preferredTextarea.isConnected ? preferredTextarea : getChatTextarea();
        if (!textarea) {
            return;
        }
        try {
            textarea.focus({ preventScroll: true });
        } catch (_) {
            textarea.focus();
        }
    } catch (_) {}
}

export function getActiveChatSessionId() {
    try {
        const form = getChatComposerForm();
        const fromForm =
            form && form.dataset && form.dataset.sessionId != null ? String(form.dataset.sessionId).trim() : "";
        if (fromForm) {
            return fromForm;
        }
        const container = document.getElementById("chat-container");
        return container && container.dataset && container.dataset.sessionId != null
            ? String(container.dataset.sessionId).trim()
            : "";
    } catch (_) {
        return "";
    }
}

export function isDraftSaveRequestPath(path) {
    const value = String(path || "");
    return /\/ui\/sessions\/[^/?#]+\/draft(?:[/?#]|$)/.test(value);
}

export function shouldFlushDraftBeforeRequest(path) {
    const value = String(path || "");
    if (!value) {
        return false;
    }
    if (value.includes("/ui/chat/send")) {
        return false;
    }
    if (isDraftSaveRequestPath(value)) {
        return false;
    }
    return /\/ui\/(chat\/primary|chat\/subagent\/|projects|workspaces|sessions)\b/.test(value);
}
