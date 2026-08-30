import {processHtmxElement} from '../shared.js';
import {renderChatMarkdown} from '../markdown.js';
import {appendToolCallToChatRow, taskToolCallBody, toolCallInputText, toolCallOutputText, updateOpenSubagentTranscript} from '../tool-calls.js';

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

function handleToolCallStarted(liveRow, payload) {
    appendToolCallToChatRow(liveRow, payload, processHtmxElement, {
        state: 'running',
        statusText: 'running',
        success: false,
        inputText: toolCallInputText(payload),
        outputText: toolCallOutputText(payload),
        taskBody: taskToolCallBody(payload)
    });
}

function handleToolCallProgress(liveRow, event, payload) {
    if (event.eventName === 'subagent_started') {
        appendToolCallToChatRow(liveRow, {
            toolCallId: event.toolCallId,
            toolName: event.toolName,
            inputPreview: toolCallInputText(payload) || payload.task || '',
            subagentSessionId: payload.childSessionId,
            subagentAgentName: payload.subagentAgentName,
            taskBody: taskToolCallBody(payload)
        }, processHtmxElement, {
            state: 'running',
            statusText: 'running',
            success: false,
            outputText: payload.task != null ? String(payload.task) : '',
            subagentSessionId: payload.childSessionId,
            subagentAgentName: payload.subagentAgentName,
            taskBody: taskToolCallBody(payload)
        });
        updateOpenSubagentTranscript(payload, 'started');
        return;
    }

    if (event.eventName === 'subagent_delta') {
        const entry = appendToolCallToChatRow(liveRow, {
            toolCallId: event.toolCallId,
            toolName: event.toolName,
            inputPreview: toolCallInputText(payload) || payload.task || '',
            taskBody: taskToolCallBody(payload)
        }, processHtmxElement, {state: 'running', statusText: 'running', success: false, taskBody: taskToolCallBody(payload)});
        if (entry && entry.outputPre && payload.delta != null) {
            entry.outputPre.textContent = (entry.outputPre.textContent || '') + String(payload.delta);
        }
        updateOpenSubagentTranscript(payload, 'delta');
        return;
    }

    if (event.eventName === 'subagent_tool_call') {
        const parentEntry = appendToolCallToChatRow(liveRow, {
            toolCallId: event.toolCallId,
            toolName: event.toolName,
            inputPreview: toolCallInputText(payload) || payload.task || '',
            taskBody: taskToolCallBody(payload)
        }, processHtmxElement, {state: 'running', statusText: 'running', success: false, taskBody: taskToolCallBody(payload)});
        if (parentEntry && parentEntry.nestedCalls) {
            appendToolCallToChatRow(parentEntry.nestedCalls, payload, processHtmxElement, {
                state: payload.success ? 'done' : 'error',
                statusText: payload.success ? 'success' : 'failure',
                success: Boolean(payload.success)
            });
        }
        updateOpenSubagentTranscript(payload, 'tool_call');
        return;
    }

    if (event.eventName === 'subagent_done') {
        appendToolCallToChatRow(liveRow, {
            toolCallId: event.toolCallId,
            toolName: event.toolName,
            inputPreview: toolCallInputText(payload) || payload.task || '',
            subagentSessionId: payload.childSessionId,
            subagentAgentName: payload.subagentAgentName,
            taskBody: taskToolCallBody(payload)
        }, processHtmxElement, {
            state: 'done',
            statusText: 'done',
            success: true,
            outputText: payload.finalText != null ? String(payload.finalText) : '',
            subagentSessionId: payload.childSessionId,
            subagentAgentName: payload.subagentAgentName,
            taskBody: taskToolCallBody(payload)
        });
        updateOpenSubagentTranscript(payload, 'done');
        return;
    }

    if (event.eventName === 'subagent_error') {
        appendToolCallToChatRow(liveRow, {
            toolCallId: event.toolCallId,
            toolName: event.toolName,
            inputPreview: toolCallInputText(payload) || payload.task || '',
            subagentSessionId: payload.childSessionId,
            subagentAgentName: payload.subagentAgentName,
            taskBody: taskToolCallBody(payload)
        }, processHtmxElement, {
            state: 'error',
            statusText: 'error',
            success: false,
            outputText: payload.errorText != null ? String(payload.errorText) : '',
            subagentSessionId: payload.childSessionId,
            subagentAgentName: payload.subagentAgentName,
            taskBody: taskToolCallBody(payload)
        });
        updateOpenSubagentTranscript(payload, 'error');
    }
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
            try {
                const history = document.getElementById('chat-history');
                if (history) history.scrollTop = history.scrollHeight - history.clientHeight;
            } catch (_) {
            }
        });
    }
}

export {
    handleContextCompaction,
    handleToolCallProgress,
    handleToolCallStarted,
    parseStreamPayload
};
