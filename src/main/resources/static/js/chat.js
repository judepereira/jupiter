import { configureChatComposer, initChatComposer, resizeChatTextarea } from "./chat/composer.js";
import { configureCommandPicker } from "./chat/commands.js";
import { bindChatMessageCopyButtons, formatAllChatSubtitles, renderAllChatMarkdown } from "./chat/markdown.js";
import { initWorkspaceRailSync, invalidateWorkspaceRailRefresh, syncFaviconWithRail } from "./chat/rail-sync.js";
import { bindAutoScrollListeners } from "./chat/scroll.js";
import {
    activePrimaryPendingAssistantRow,
    bindPendingStreams,
    requestStopActiveChat,
    updateChatSendButtonState,
} from "./chat/streams.js";

let chatHtmxListenersBound = false;

function resetContextUsage() {
    const ring = document.querySelector("#session-context-usage [data-context-ring]");
    if (ring) {
        ring.style.setProperty("--usage-angle", "0deg");
        ring.setAttribute("aria-label", "Context usage not available");
        const value = ring.firstElementChild;
        if (value) value.textContent = "";
    }
}

function bindChatHtmxLifecycleListeners() {
    if (chatHtmxListenersBound) {
        return;
    }
    chatHtmxListenersBound = true;

    document.body.addEventListener(
        "htmx:beforeRequest",
        function (evt) {
            const requestUrl = evt?.detail?.requestConfig?.path || "";
            if (/\/ui\/(projects|workspaces|sessions)\/[^?]*(activate|collapse|close|add|create)/.test(requestUrl)) {
                invalidateWorkspaceRailRefresh();
                if (/\/activate(?:[/?#]|$)/.test(requestUrl)) {
                    resetContextUsage();
                }
            }
        },
        true,
    );
    document.body.addEventListener(
        "htmx:afterSwap",
        function () {
            Promise.resolve().then(() => {
                bindPendingStreams();
                syncFaviconWithRail();
            });
        },
        true,
    );
    document.body.addEventListener(
        "htmx:afterSettle",
        function () {
            Promise.resolve().then(() => {
                bindPendingStreams();
                syncFaviconWithRail();
            });
        },
        true,
    );

    document.body.addEventListener(
        "htmx:afterSwap",
        function (evt) {
            try {
                const target = (evt && evt.detail && evt.detail.target) || evt.target || document;
                const liveList = document.getElementById("chat-messages-list");
                const base = liveList
                    ? liveList
                    : target && target.querySelector && target.querySelector(".chat-message-text")
                      ? target
                      : document;
                Promise.resolve().then(() => {
                    renderAllChatMarkdown(base);
                    formatAllChatSubtitles(base);
                });
            } catch (_) {}
        },
        true,
    );
    document.body.addEventListener(
        "htmx:afterSettle",
        function (evt) {
            try {
                const target = (evt && evt.detail && evt.detail.target) || evt.target || document;
                const liveList = document.getElementById("chat-messages-list");
                const base = liveList
                    ? liveList
                    : target && target.querySelector && target.querySelector(".chat-message-text")
                      ? target
                      : document;
                Promise.resolve().then(() => {
                    renderAllChatMarkdown(base);
                    formatAllChatSubtitles(base);
                });
            } catch (_) {}
        },
        true,
    );

    document.body.addEventListener(
        "htmx:afterSwap",
        function () {
            Promise.resolve().then(() => {
                initChatComposer();
                updateChatSendButtonState();
            });
        },
        true,
    );
    document.body.addEventListener(
        "htmx:afterSettle",
        function () {
            Promise.resolve().then(() => {
                initChatComposer();
                updateChatSendButtonState();
            });
        },
        true,
    );
}

configureCommandPicker({ resizeChatTextarea, bindPendingStreams });
configureChatComposer({ activePrimaryPendingAssistantRow, requestStopActiveChat, updateChatSendButtonState });

bindAutoScrollListeners();
bindChatMessageCopyButtons();
initChatComposer();
try {
    renderAllChatMarkdown();
    formatAllChatSubtitles();
} catch (_) {}
initWorkspaceRailSync();
bindPendingStreams();
syncFaviconWithRail();
bindChatHtmxLifecycleListeners();
