import {getChatComposerForm, getChatTextarea} from './dom.js';
import {scheduleChatDraftSave} from './autosave.js';
import {isMobileChatViewport, insertChatTextareaNewline, resizeChatTextarea} from './textarea.js';

export function getChatSelectOption(select) {
    if (!select) return null;
    return select.options ? select.options[select.selectedIndex] : null;
}

export function syncChatDefaults(form) {
    const agentSelect = form && form.querySelector('#chat-agent-select');
    const modelSelect = form && form.querySelector('#chat-model-select');
    const thinkingSelect = form && form.querySelector('#chat-thinking-select');
    if (!agentSelect || !modelSelect || !thinkingSelect) return;

    const agentOption = getChatSelectOption(agentSelect);
    if (!agentOption || !agentOption.dataset) return;

    modelSelect.value = agentOption.dataset.defaultModel;
    thinkingSelect.value = agentOption.dataset.defaultThinking;
}

export function bindChatControlListeners(form) {
    if (!form || form.dataset.chatControlsBound === '1') return;
    form.dataset.chatControlsBound = '1';

    const agentSelect = form.querySelector('#chat-agent-select');
    if (!agentSelect) return;

    agentSelect.addEventListener('change', () => syncChatDefaults(form));
}

export function bindChatSubmitStopListener(form, activePendingAssistantRowFn, requestStopActiveChat) {
    if (!form || form.dataset.chatStopBound === '1') return;
    form.dataset.chatStopBound = '1';
    form.addEventListener('submit', event => {
        if (!activePendingAssistantRowFn || !activePendingAssistantRowFn()) return;
        event.preventDefault();
        event.stopPropagation();
        requestStopActiveChat();
    }, true);
}

export function appendChatHtml(html, deps) {
    const runtime = deps || window.__chatRuntime || {};
    const list = document.getElementById('chat-messages-list');
    if (!list || !html) return;
    list.insertAdjacentHTML('beforeend', html);
    try {
        runtime.renderAllChatMarkdown && runtime.renderAllChatMarkdown(list);
    } catch (_) {
    }
    try {
        runtime.bindPendingStreams && runtime.bindPendingStreams();
    } catch (_) {
    }
    runtime.checkAndMaybeScroll && runtime.checkAndMaybeScroll();
}

export function initChatComposer(deps) {
    try {
        const runtime = deps || window.__chatRuntime || {};
        const form = getChatComposerForm();
        const textarea = getChatTextarea();
        if (!form || !textarea) return;

        const isFreshComposer = textarea.dataset.chatBound !== '1';
        runtime.syncChatDraftAutosaveStateFromTextarea && runtime.syncChatDraftAutosaveStateFromTextarea(textarea, isFreshComposer);

        const commandPickerState = runtime.commandPickerState;
        const openCommandPicker = runtime.openCommandPicker;
        const activePendingAssistantRow = runtime.activePendingAssistantRow;
        const requestStopActiveChat = runtime.requestStopActiveChat;

        if (isFreshComposer) {
            textarea.dataset.chatBound = '1';
            textarea.dataset.chatSlashRestoredValue = textarea.value && textarea.value.startsWith('/') ? textarea.value : '';

            function onKeyDown(e) {
                if (commandPickerState && commandPickerState.open) {
                    return;
                }
                const isEnter = e.key === 'Enter' || e.keyCode === 13;
                if (!isEnter) return;
                if (e.isComposing) return;

                if (e.altKey || isMobileChatViewport()) {
                    e.preventDefault();
                    insertChatTextareaNewline(textarea);
                    return;
                }

                e.preventDefault();
                if (activePendingAssistantRow && activePendingAssistantRow()) {
                    return;
                }
                if (typeof form.requestSubmit === 'function') {
                    form.requestSubmit();
                } else {
                    form.submit();
                }
            }

            textarea.addEventListener('input', () => {
                resizeChatTextarea(textarea);
                scheduleChatDraftSave(textarea.value);
            });
            textarea.addEventListener('keydown', onKeyDown);
            textarea.addEventListener('beforeinput', event => {
                if (commandPickerState && commandPickerState.open) return;
                if (textarea.selectionStart !== 0 || textarea.selectionEnd !== 0 || textarea.value) return;
                if (event.inputType !== 'insertText' || event.data !== '/') return;
                event.preventDefault();
                openCommandPicker && openCommandPicker(textarea, '/');
            });
            textarea.addEventListener('input', () => {
                if (commandPickerState && commandPickerState.open) return;
                if (!textarea.value.startsWith('/')) {
                    textarea.dataset.chatSlashRestoredValue = '';
                    return;
                }
                if (textarea.dataset.chatSlashRestoredValue === textarea.value) return;
                openCommandPicker && openCommandPicker(textarea, textarea.value);
            });
            if (!textarea.dataset.chatStopListenerBound) {
                textarea.dataset.chatStopListenerBound = '1';
                form.addEventListener('submit', event => {
                    if (!activePendingAssistantRow || !activePendingAssistantRow()) return;
                    event.preventDefault();
                    event.stopPropagation();
                    requestStopActiveChat && requestStopActiveChat();
                }, true);
            }
        }

        requestAnimationFrame(() => resizeChatTextarea(textarea));
        bindChatControlListeners(form);
        runtime.bindAutoScrollListeners && runtime.bindAutoScrollListeners();
        runtime.updateChatSendButtonState && runtime.updateChatSendButtonState();
    } catch (_) {
    }
}
