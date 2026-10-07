import { getCurrentOpenSubagentSessionId, processHtmxElement } from "./shared.js";

export function getRawChatMarkdown(el) {
    try {
        if (!el) return "";
        return el.dataset && el.dataset.rawMarkdown != null ? el.dataset.rawMarkdown : el.textContent || "";
    } catch (_) {
        return "";
    }
}

export function renderChatMarkdown(el, rawText) {
    try {
        if (!el) return;
        if (!el.dataset) el.dataset = {};
        el.dataset.rawMarkdown = rawText != null ? String(rawText) : "";

        if (window.marked && window.DOMPurify) {
            try {
                var html = null;
                if (typeof window.marked.parse === "function") {
                    html = window.marked.parse(el.dataset.rawMarkdown, { breaks: true });
                } else if (typeof window.marked === "function") {
                    html = window.marked(el.dataset.rawMarkdown, { breaks: true });
                } else if (window.marked && typeof window.marked.parse === "undefined") {
                    html = String(el.dataset.rawMarkdown);
                }
                if (html != null && window.DOMPurify && typeof window.DOMPurify.sanitize === "function") {
                    el.innerHTML = window.DOMPurify.sanitize(html);
                    try {
                        if (el.classList) el.classList.add("markdown-rendered");
                        if (el.dataset) el.dataset.markdownRendered = "true";
                    } catch (_) {}
                    return;
                }
            } catch (_) {}
        }

        try {
            if (el.classList) el.classList.remove("markdown-rendered");
            if (el.dataset) delete el.dataset.markdownRendered;
        } catch (_) {}
        el.textContent = el.dataset.rawMarkdown;
    } catch (_) {}
}

export function renderAllChatMarkdown(root) {
    try {
        const base = root || document;
        const msgs = base.querySelectorAll && base.querySelectorAll(".chat-message-text");
        if (!msgs) return;
        msgs.forEach((el) => {
            try {
                const raw = getRawChatMarkdown(el);
                renderChatMarkdown(el, raw);
            } catch (_) {}
        });
    } catch (_) {}
}

function formatChatDuration(startTs, completedTs) {
    const start = Number(startTs);
    const end = Number(completedTs);
    if (!Number.isFinite(start) || !Number.isFinite(end)) return "";

    let totalSeconds = Math.max(0, Math.floor((end - start) / 1000));
    const hours = Math.floor(totalSeconds / 3600);
    totalSeconds -= hours * 3600;
    const minutes = Math.floor(totalSeconds / 60);
    const seconds = totalSeconds - minutes * 60;

    if (hours > 0) {
        return minutes > 0 ? hours + "h " + minutes + "m" : hours + "h";
    }
    if (minutes > 0) {
        return seconds > 0 ? minutes + "m " + seconds + "s" : minutes + "m";
    }
    return seconds + "s";
}

const chatCompletionTimeFormatter = new Intl.DateTimeFormat(undefined, { timeStyle: "short" });
const chatCompletionDateFormatter = new Intl.DateTimeFormat(undefined, {
    weekday: "long",
    day: "numeric",
    month: "long",
});

function formatChatCompletedTs(completedTs) {
    const completed = new Date(Number(completedTs));
    if (Number.isNaN(completed.getTime())) return "";

    const now = new Date();
    const sameDay =
        completed.getFullYear() === now.getFullYear() &&
        completed.getMonth() === now.getMonth() &&
        completed.getDate() === now.getDate();
    if (sameDay) {
        return chatCompletionTimeFormatter.format(completed);
    }

    const parts = chatCompletionDateFormatter.formatToParts(completed);
    const weekday = parts.find((part) => part.type === "weekday");
    const day = parts.find((part) => part.type === "day");
    const month = parts.find((part) => part.type === "month");
    const dateText = [weekday && weekday.value, day && day.value, month && month.value].filter(Boolean).join(" ");
    return dateText + ", " + chatCompletionTimeFormatter.format(completed);
}

function formatChatSubtitle(subtitle) {
    try {
        if (!subtitle || !subtitle.dataset) return;
        const metadataParts = [
            subtitle.dataset.agentLabel,
            subtitle.dataset.modelLabel || subtitle.dataset.modelId,
            subtitle.dataset.thinkingLevel,
        ].filter((part) => part != null && String(part).trim());
        const metadataText = metadataParts.join(" · ");
        const duration = formatChatDuration(subtitle.dataset.startTs, subtitle.dataset.completedTs);
        const completedTs = formatChatCompletedTs(subtitle.dataset.completedTs);
        const completionText = duration && completedTs ? duration + " · " + completedTs : duration || completedTs;
        const textNode = subtitle.querySelector(".chat-message-subtitle-text") || subtitle;
        textNode.textContent =
            metadataText && completionText ? metadataText + " · " + completionText : metadataText || completionText;
    } catch (_) {}
}

export function formatAllChatSubtitles(root) {
    try {
        const base = root || document;
        const subtitles = base.querySelectorAll && base.querySelectorAll(".chat-message-subtitle");
        if (!subtitles) return;
        subtitles.forEach(formatChatSubtitle);
    } catch (_) {}
}

const chatCopyResetTimers = new WeakMap();

function copyChatResponse(text) {
    const activeElement = document.activeElement;
    const activeSelection =
        activeElement instanceof HTMLInputElement || activeElement instanceof HTMLTextAreaElement
            ? {
                  start: activeElement.selectionStart,
                  end: activeElement.selectionEnd,
                  direction: activeElement.selectionDirection,
              }
            : null;
    const documentSelection = document.getSelection();
    const selectionRanges = documentSelection
        ? Array.from({ length: documentSelection.rangeCount }, (_, index) =>
              documentSelection.getRangeAt(index).cloneRange(),
          )
        : [];
    const history = document.getElementById("chat-history");
    const scroll = {
        windowX: window.scrollX,
        windowY: window.scrollY,
        historyLeft: history && history.scrollLeft,
        historyTop: history && history.scrollTop,
    };
    const textarea = document.createElement("textarea");

    try {
        textarea.value = text;
        textarea.readOnly = true;
        textarea.tabIndex = -1;
        textarea.setAttribute("aria-label", "Text to copy");
        textarea.style.position = "fixed";
        textarea.style.left = "-9999px";
        textarea.style.top = "0";
        textarea.style.width = "1px";
        textarea.style.height = "1px";
        textarea.style.opacity = "0";
        document.body.appendChild(textarea);
        textarea.focus({ preventScroll: true });
        textarea.select();
        if (document.execCommand("copy") !== true) throw new Error("document.execCommand('copy') returned false");
    } finally {
        textarea.remove();
        try {
            if (activeElement && activeElement.isConnected) {
                activeElement.focus({ preventScroll: true });
                if (activeSelection && activeSelection.start != null && activeSelection.end != null) {
                    activeElement.setSelectionRange(
                        activeSelection.start,
                        activeSelection.end,
                        activeSelection.direction,
                    );
                }
            }
        } catch (_) {}
        try {
            if (documentSelection) {
                documentSelection.removeAllRanges();
                selectionRanges.forEach((range) => documentSelection.addRange(range));
            }
        } catch (_) {}
        try {
            window.scrollTo(scroll.windowX, scroll.windowY);
            if (history) history.scrollTo(scroll.historyLeft, scroll.historyTop);
        } catch (_) {}
    }
}

export function bindChatMessageCopyButtons() {
    try {
        if (document.body.dataset.chatCopyButtonsBound === "true") return;
        document.body.dataset.chatCopyButtonsBound = "true";
        document.body.addEventListener("click", (event) => {
            const button = event.target && event.target.closest && event.target.closest(".chat-message-copy-button");
            if (!button) return;
            const row = button.closest('li[data-role="assistant"]');
            const message = row && row.querySelector(".chat-message-text");
            if (!message || button.dataset.copyPending === "true") return;

            const previousTimer = chatCopyResetTimers.get(button);
            if (previousTimer) window.clearTimeout(previousTimer);
            button.dataset.copyPending = "true";
            try {
                copyChatResponse(getRawChatMarkdown(message));
                button.setAttribute("aria-label", "Copied");
                button.title = "Copied";
                chatCopyResetTimers.set(
                    button,
                    window.setTimeout(() => {
                        chatCopyResetTimers.delete(button);
                        button.setAttribute("aria-label", "Copy response");
                        button.title = "Copy response";
                    }, 1500),
                );
            } catch (error) {
                button.setAttribute("aria-label", "Copy failed");
                button.title = "Copy failed";
                console.error("Unable to copy chat response", error);
            } finally {
                delete button.dataset.copyPending;
            }
        });
    } catch (_) {}
}

function ensureChatMessageCopyButton(row, subtitle) {
    try {
        if (!row || !subtitle || !row.dataset || row.dataset.role !== "assistant") return;
        if (!subtitle.querySelector(".chat-message-copy-button")) {
            const button = document.createElement("button");
            button.type = "button";
            button.className = "chat-message-copy-button btn btn-link";
            button.setAttribute("aria-label", "Copy response");
            button.title = "Copy response";
            button.innerHTML = '<i class="bi bi-copy" aria-hidden="true"></i>';
            subtitle.appendChild(button);
        }
    } catch (_) {}
}

function ensureChatMessageForkButton(row, subtitle) {
    try {
        if (!row || !subtitle || !row.dataset || row.dataset.role !== "assistant") return;
        if (getCurrentOpenSubagentSessionId()) return;

        const assistantPublicId = row.dataset.id != null ? String(row.dataset.id).trim() : "";
        if (!assistantPublicId) return;

        ensureChatMessageCopyButton(row, subtitle);
        let button = subtitle.querySelector(".chat-message-fork-button");
        if (!button) {
            button = document.createElement("button");
            button.type = "button";
            button.className = "chat-message-fork-button btn btn-link";
            button.textContent = "Fork";
            subtitle.appendChild(button);
        }
        button.setAttribute("hx-post", "/ui/chat/fork/" + encodeURIComponent(assistantPublicId));
        button.setAttribute("hx-target", "#shell");
        button.setAttribute("hx-swap", "none");
        processHtmxElement(button);
    } catch (_) {}
}

export function updateChatRowCompletion(row, completedTs) {
    try {
        if (!row || !completedTs) return;
        row.dataset.completedTs = String(completedTs);
        ensureChatMessageSubtitle(row, completedTs);
    } catch (_) {}
}

export function ensureChatMessageSubtitle(row, completedTs) {
    try {
        if (!row || !row.dataset || row.dataset.role !== "assistant") return;
        const completed = completedTs != null ? String(completedTs) : "";
        if (!completed) return;

        row.dataset.completedTs = completed;
        let subtitle = row.querySelector(".chat-message-subtitle");
        if (!subtitle) {
            subtitle = document.createElement("div");
            subtitle.className = "chat-message-subtitle";
            const text = document.createElement("span");
            text.className = "chat-message-subtitle-text";
            subtitle.appendChild(text);
            row.appendChild(subtitle);
        }
        subtitle.dataset.startTs = subtitle.dataset.startTs || row.dataset.startTs || "";
        subtitle.dataset.completedTs = completed;
        subtitle.dataset.agentLabel = subtitle.dataset.agentLabel || row.dataset.agentLabel || "";
        subtitle.dataset.agentId = subtitle.dataset.agentId || row.dataset.agentId || "";
        subtitle.dataset.modelId = subtitle.dataset.modelId || row.dataset.modelId || "";
        subtitle.dataset.modelLabel = subtitle.dataset.modelLabel || row.dataset.modelLabel || "";
        subtitle.dataset.thinkingLevel = subtitle.dataset.thinkingLevel || row.dataset.thinkingLevel || "";
        ensureChatMessageForkButton(row, subtitle);
        formatChatSubtitle(subtitle);
    } catch (_) {}
}
