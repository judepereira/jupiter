import {getHtmxRequestPath, syncFaviconWithRail} from './dom.js';
import {clearChatDraftAutosaveState, invalidateChatDraftAutosaveState, syncChatDraftAutosaveStateFromTextarea} from './autosave.js';
import {appendChatHtml, initChatComposer} from './composer.js';
import {bindAutoScrollListeners, checkAndMaybeScrollPublic, scrollChatToBottomPublic} from './scroll.js';
import {bindPendingStreamListenersOnce, bindPendingStreams, clearPendingChatRowState, clearPendingStream, getCurrentOpenSubagentSessionId, getLiveChatRow, getOpenSubagentPendingRow, activePrimaryPendingAssistantRow, requestStopActiveChat, replaceChatContainerFromHtml, updateChatSendButtonState, updateOpenSubagentTranscript, appendToolCallToChatRow} from './streams.js';
import {closeCommandPicker, commandPickerState, openCommandPicker} from './command-picker.js';
import {formatAllChatSubtitles, renderAllChatMarkdown, ensureChatMessageSubtitle, updateChatRowCompletion} from './markdown.js';
import {focusChatInput, getChatTextarea} from './dom.js';
import {resizeChatTextarea} from './textarea.js';

const chatRuntime = {
    commandPickerState,
    appendChatHtml: html => appendChatHtml(html, chatRuntime),
    initChatComposer: () => initChatComposer(chatRuntime),
    bindAutoScrollListeners: () => bindAutoScrollListeners(chatRuntime),
    bindPendingStreams: () => bindPendingStreams(chatRuntime),
    openCommandPicker: (textarea, query) => openCommandPicker(textarea, query, chatRuntime),
    closeCommandPicker: () => closeCommandPicker(chatRuntime),
    requestStopActiveChat: () => requestStopActiveChat(chatRuntime),
    replaceChatContainerFromHtml: html => replaceChatContainerFromHtml(html, chatRuntime),
    getCurrentOpenSubagentSessionId,
    getOpenSubagentPendingRow,
    activePrimaryPendingAssistantRow,
    updateChatSendButtonState,
    clearPendingStream,
    clearPendingChatRowState,
    getLiveChatRow,
    updateOpenSubagentTranscript,
    appendToolCallToChatRow,
    renderAllChatMarkdown,
    formatAllChatSubtitles,
    ensureChatMessageSubtitle,
    updateChatRowCompletion,
    syncFaviconWithRail,
    focusChatInput,
    getChatTextarea,
    syncChatDraftAutosaveStateFromTextarea,
    clearChatDraftAutosaveState,
    invalidateChatDraftAutosaveState,
    checkAndMaybeScroll: checkAndMaybeScrollPublic,
    scrollChatToBottom: scrollChatToBottomPublic,
    resizeChatTextarea
};

let chatSendCleanupBound = false;
let chatRuntimeInitialized = false;

function bindChatSendCleanupListener() {
    if (chatSendCleanupBound) return;
    chatSendCleanupBound = true;

    document.body.addEventListener('htmx:afterOnLoad', event => {
        try {
            const path = getHtmxRequestPath(event);
            if (!String(path || '').includes('/ui/chat/send')) return;

            const textarea = getChatTextarea();
            if (!textarea) return;
            textarea.value = '';
            textarea.dataset.chatSlashRestoredValue = '';
            resizeChatTextarea(textarea);
            clearChatDraftAutosaveState('');
        } catch (error) {
            console.error(error);
        }
    }, true);
}

function initChatRuntime() {
    if (chatRuntimeInitialized) return;
    chatRuntimeInitialized = true;

    window.__chatRuntime = chatRuntime;

    bindPendingStreamListenersOnce();
    bindChatSendCleanupListener();
    syncChatDraftAutosaveStateFromTextarea(getChatTextarea(), true);
    initChatComposer(chatRuntime);
    renderAllChatMarkdown();
    formatAllChatSubtitles();
    syncFaviconWithRail();
    updateChatSendButtonState();
}

if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', initChatRuntime, {once: true});
} else {
    initChatRuntime();
}

export {chatRuntime, initChatRuntime};
export default chatRuntime;
