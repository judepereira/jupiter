import {getCurrentOpenSubagentSessionId} from '../shared.js';
import {initChatComposer} from '../composer.js';
import {formatAllChatSubtitles, renderAllChatMarkdown} from '../markdown.js';
import {isStopRequestInFlight, setStopRequestInFlight} from './state.js';

let bindPendingStreamsHook = () => {};

function configureChatStreamControls(config) {
    if (!config) return;
    if (typeof config.bindPendingStreams === 'function') {
        bindPendingStreamsHook = config.bindPendingStreams;
    }
}

function activePrimaryPendingAssistantRow() {
    try {
        if (getCurrentOpenSubagentSessionId()) return null;
        const list = document.getElementById('chat-messages-list');
        if (!list) return null;
        return list.querySelector('li[data-role="assistant"][data-pending="true"][data-stream-url]');
    } catch (_) {
        return null;
    }
}

function updateChatSendButtonState() {
    try {
        const form = document.getElementById('chat-send-form');
        const button = document.getElementById('chat-send-btn');
        if (!form || !button) return;
        const activeRow = activePrimaryPendingAssistantRow();
        const running = Boolean(activeRow);
        form.dataset.chatRunning = running ? 'true' : 'false';
        button.classList.toggle('btn-outline-danger', running);
        button.classList.toggle('btn-outline-light', isStopRequestInFlight());
        if (isStopRequestInFlight()) {
            button.textContent = 'Stopping...';
            button.setAttribute('aria-label', 'Stopping current response');
            button.setAttribute('aria-busy', 'true');
        } else if (running) {
            button.textContent = 'Stop';
            button.setAttribute('aria-label', 'Stop current response');
            button.removeAttribute('aria-busy');
        } else {
            button.textContent = 'Send';
            button.setAttribute('aria-label', 'Send message');
            button.removeAttribute('aria-busy');
        }
    } catch (_) {
    }
}

function replaceChatContainerFromHtml(html) {
    try {
        if (!html) return;
        const template = document.createElement('template');
        template.innerHTML = html.trim();
        const incoming = template.content.querySelector('#chat-container');
        const current = document.getElementById('chat-container');
        if (incoming && current) {
            current.outerHTML = incoming.outerHTML;
            Promise.resolve().then(() => {
                initChatComposer();
                bindPendingStreamsHook();
                renderAllChatMarkdown();
                formatAllChatSubtitles();
                updateChatSendButtonState();
            });
        }
    } catch (_) {
    }
}

function requestStopActiveChat() {
    try {
        if (isStopRequestInFlight()) return;
        const row = activePrimaryPendingAssistantRow();
        if (!row || !row.dataset || !row.dataset.id) return;
        setStopRequestInFlight(true);
        updateChatSendButtonState();
        const body = new URLSearchParams();
        body.set('assistantId', row.dataset.id);
        fetch('/ui/chat/stop', {
            method: 'POST',
            headers: {'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8', 'HX-Request': 'true'},
            body: body.toString()
        }).then(response => {
            if (!response.ok) throw new Error('Stop request failed');
            return response.text();
        }).then(replaceChatContainerFromHtml)
            .catch(error => console.error(error))
            .finally(() => {
                setStopRequestInFlight(false);
                updateChatSendButtonState();
            });
    } catch (_) {
        setStopRequestInFlight(false);
        updateChatSendButtonState();
    }
}

function getLiveChatRow(assistantId) {
    try {
        if (!assistantId) return null;
        const list = document.getElementById('chat-messages-list');
        if (!list) return null;
        const candidates = Array.from(list.querySelectorAll('li[data-id="' + assistantId + '"]'));
        const visibleCandidates = candidates.filter(row => row && row.getClientRects && row.getClientRects().length > 0);
        const visiblePendingCandidates = visibleCandidates.filter(row => row.dataset.pending === 'true');
        if (visiblePendingCandidates.length > 0) return visiblePendingCandidates[0];
        if (visibleCandidates.length > 0) return visibleCandidates[0];
        const pendingCandidates = candidates.filter(row => row.dataset.pending === 'true');
        return pendingCandidates[0] || candidates[0] || null;
    } catch (_) {
        return null;
    }
}

export {
    activePrimaryPendingAssistantRow,
    configureChatStreamControls,
    getLiveChatRow,
    requestStopActiveChat,
    replaceChatContainerFromHtml,
    updateChatSendButtonState
};
