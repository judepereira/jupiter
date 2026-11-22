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

function bindChatHtmxLifecycleListeners() {
    if (chatHtmxListenersBound) {
        return;
    }
    chatHtmxListenersBound = true;

    document.addEventListener(
        "htmx:before:request",
        function (evt) {
            const requestUrl = evt?.detail?.ctx?.request?.action || "";
            if (/\/ui\/(projects|workspaces|sessions)\/[^?]*(activate|collapse|close|add|create)/.test(requestUrl)) {
                invalidateWorkspaceRailRefresh();
            }
        },
        true,
    );
    document.addEventListener(
        "htmx:after:swap",
        function () {
            Promise.resolve().then(() => {
                bindPendingStreams();
                syncFaviconWithRail();
            });
        },
        true,
    );
    document.addEventListener(
        "htmx:after:settle",
        function () {
            Promise.resolve().then(() => {
                bindPendingStreams();
                syncFaviconWithRail();
            });
        },
        true,
    );

    document.addEventListener(
        "htmx:after:swap",
        function (evt) {
            try {
                const target = evt?.detail?.ctx?.target || evt.target || document;
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
    document.addEventListener(
        "htmx:after:settle",
        function (evt) {
            try {
                const target = evt?.detail?.ctx?.target || evt.target || document;
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

    document.addEventListener(
        "htmx:after:swap",
        function () {
            Promise.resolve().then(() => {
                initChatComposer();
                updateChatSendButtonState();
            });
        },
        true,
    );
    document.addEventListener(
        "htmx:after:settle",
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
