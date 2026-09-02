import {getActiveChatSessionId} from './shared.js';
import {refreshWorkspaceRail} from './rail-sync.js';
import {isHistoryNearBottom, scrollChatToBottom} from './scroll.js';

function appendInfoMessage(notification) {
    if (!notification) return;
    const activeSessionId = getActiveChatSessionId();
    if (String(notification.sessionId || '') !== activeSessionId) return;
    const message = notification.message || notification;
    if (String(message.role) !== 'info') return;
    const list = document.getElementById('chat-messages-list');
    if (!list || [...list.querySelectorAll('li[data-id]')].some(row => row.dataset.id === String(message.id))) return;

    const nearBottom = isHistoryNearBottom();
    const row = document.createElement('li');
    row.dataset.id = message.id;
    row.dataset.role = 'info';
    row.dataset.startTs = message.ts;
    const text = document.createElement('span');
    text.className = 'chat-message-text';
    text.textContent = message.text;
    const label = document.createElement('span');
    label.className = 'chat-message-info-label';
    label.innerHTML = '<i class="bi bi-info-circle" aria-hidden="true"></i><span>Background update</span>';
    row.append(label, text);
    list.append(row);
    if (nearBottom) scrollChatToBottom();
    refreshWorkspaceRail();
}

export function initInfoMessageDelivery() {
    if (window.__infoMessageSource) return window.__infoMessageSource;
    const source = new EventSource('/ui/chat/info/stream');
    window.__infoMessageSource = source;
    source.addEventListener('info-message', event => {
        try { appendInfoMessage(JSON.parse(event.data)); } catch (error) { console.error('Invalid info message event', error); }
    });
    source.addEventListener('error', error => {
        console.error('Info message stream error', error);
        if (!error || !error.data) window.__connectionLossMonitor && window.__connectionLossMonitor.transportFailure();
    });
    return source;
}
