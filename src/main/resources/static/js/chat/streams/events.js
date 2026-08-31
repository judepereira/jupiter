import {renderChatMarkdown} from '../markdown.js';

function parseStreamPayload(e) {
    const raw = (e && e.data) ? e.data : '';
    if (!raw) return {text: ''};
    try {
        const parsed = JSON.parse(raw);
        if (parsed && typeof parsed === 'object') return parsed;
    } catch (_) {
    }
    return {text: raw};
}

function handleContextCompaction(list, payload, shouldStickToBottom, wasNearBottom) {
    const id = payload && payload.id != null ? String(payload.id) : '';
    const text = payload && payload.text != null ? String(payload.text) : '';
    if (!id || !text) return;

    Array.from(list.querySelectorAll('li[data-id]')).forEach(item => {
        if (item.dataset.id === id) item.remove();
    });

    const compactedRow = document.createElement('li');
    compactedRow.dataset.id = id;
    compactedRow.dataset.system = 'true';

    const strong = document.createElement('strong');
    strong.textContent = 'system';

    const textSpan = document.createElement('span');
    textSpan.className = 'chat-message-text';

    compactedRow.appendChild(strong);
    compactedRow.appendChild(document.createTextNode(': '));
    compactedRow.appendChild(textSpan);

    const pendingRow = list.querySelector('li[data-pending="true"]');
    if (pendingRow && pendingRow.parentNode) {
        pendingRow.parentNode.insertBefore(compactedRow, pendingRow);
    } else {
        list.appendChild(compactedRow);
    }

    renderChatMarkdown(textSpan, text);

    if (shouldStickToBottom() || wasNearBottom()) {
        requestAnimationFrame(() => {
            const history = document.getElementById('chat-history');
            if (history) history.scrollTop = history.scrollHeight - history.clientHeight;
        });
    }
}

export {
    handleContextCompaction,
    parseStreamPayload
};
