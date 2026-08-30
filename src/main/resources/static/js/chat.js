import {configureChatComposer, initChatComposer, resizeChatTextarea} from './chat/composer.js';
import {configureCommandPicker} from './chat/commands.js';
import {formatAllChatSubtitles, renderAllChatMarkdown} from './chat/markdown.js';
import {initWorkspaceRailSync, syncFaviconWithRail} from './chat/rail-sync.js';
import {
    activePrimaryPendingAssistantRow,
    bindPendingStreams,
    requestStopActiveChat,
    updateChatSendButtonState
} from './chat/streams.js';

let chatHtmxListenersBound = false;

function bindChatHtmxLifecycleListeners() {
    if (chatHtmxListenersBound) return;
    chatHtmxListenersBound = true;

    document.body.addEventListener('htmx:afterSwap', function (evt) {
        Promise.resolve().then(() => {
            bindPendingStreams();
            syncFaviconWithRail();
        });
    }, true);
    document.body.addEventListener('htmx:afterSettle', function (evt) {
        Promise.resolve().then(() => {
            bindPendingStreams();
            syncFaviconWithRail();
        });
    }, true);

    document.body.addEventListener('htmx:afterSwap', function (evt) {
        try {
            const target = (evt && evt.detail && evt.detail.target) || evt.target || document;
            const liveList = document.getElementById('chat-messages-list');
            const base = liveList ? liveList : (target && target.querySelector && target.querySelector('.chat-message-text') ? target : document);
            Promise.resolve().then(() => {
                renderAllChatMarkdown(base);
                formatAllChatSubtitles(base);
            });
        } catch (_) {
        }
    }, true);
    document.body.addEventListener('htmx:afterSettle', function (evt) {
        try {
            const target = (evt && evt.detail && evt.detail.target) || evt.target || document;
            const liveList = document.getElementById('chat-messages-list');
            const base = liveList ? liveList : (target && target.querySelector && target.querySelector('.chat-message-text') ? target : document);
            Promise.resolve().then(() => {
                renderAllChatMarkdown(base);
                formatAllChatSubtitles(base);
            });
        } catch (_) {
        }
    }, true);

    document.body.addEventListener('htmx:afterSwap', function () {
        Promise.resolve().then(() => {
            initChatComposer();
            updateChatSendButtonState();
        });
    }, true);
    document.body.addEventListener('htmx:afterSettle', function () {
        Promise.resolve().then(() => {
            initChatComposer();
            updateChatSendButtonState();
        });
    }, true);
}

configureCommandPicker({resizeChatTextarea, bindPendingStreams});
configureChatComposer({activePrimaryPendingAssistantRow, requestStopActiveChat, updateChatSendButtonState});

initChatComposer();
try {
    renderAllChatMarkdown();
    formatAllChatSubtitles();
} catch (_) {
}
initWorkspaceRailSync();
bindPendingStreams();
syncFaviconWithRail();
bindChatHtmxLifecycleListeners();
