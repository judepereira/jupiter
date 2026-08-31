import {getCurrentOpenSubagentSessionId} from '../shared.js';

export function getOpenSubagentPendingRow(childSessionId) {
    try {
        if (!childSessionId) return null;
        if (getCurrentOpenSubagentSessionId() !== String(childSessionId)) return null;
        const list = document.getElementById('chat-messages-list');
        if (!list) return null;
        return list.querySelector('li[data-pending="true"]');
    } catch (_) {
        return null;
    }
}

export function clearPendingChatRowState(row) {
    if (!row) return;
    row.classList.remove('pending');
    row.removeAttribute('data-pending');
    row.dataset.streamBound = '0';
}

export function toolCallInputText(payload) {
    try {
        if (!payload) return '';
        const preview = payload.inputPreview != null ? String(payload.inputPreview) : '';
        if (preview.trim()) return preview;

        const args = payload.args;
        if (args == null) return '';
        if (typeof args === 'string') return args;
        if (typeof args === 'object') {
            for (const key of ['input', 'task', 'message', 'prompt', 'text']) {
                if (args[key] != null && String(args[key]).trim()) return String(args[key]);
            }
            try {
                return JSON.stringify(args, null, 2);
            } catch (_) {
                return String(args);
            }
        }
        return String(args);
    } catch (_) {
        return '';
    }
}

export function taskToolCallBody(payload) {
    try {
        if (!payload) return '';
        if (payload.requestSummary != null && String(payload.requestSummary).trim()) {
            return String(payload.requestSummary);
        }
        if (payload.args && typeof payload.args === 'object' && payload.args.requestSummary != null && String(payload.args.requestSummary).trim()) {
            return String(payload.args.requestSummary);
        }
        const args = payload.args;
        if (args && typeof args === 'object') {
            if (args.taskBody != null && String(args.taskBody).trim()) {
                return String(args.taskBody);
            }
            if (args.task != null && String(args.task).trim()) {
                return String(args.task);
            }
        }
        if (payload.taskBody != null && String(payload.taskBody).trim()) {
            return String(payload.taskBody);
        }
        if (payload.task != null && String(payload.task).trim()) {
            return String(payload.task);
        }
        return '';
    } catch (_) {
        return '';
    }
}

export function toolCallStatusText(state, success) {
    if (state === 'running') return 'running';
    if (state === 'done') return 'done';
    if (state === 'error') return 'error';
    return success ? 'success' : 'failure';
}

export function toolCallOutputText(payload) {
    try {
        if (!payload) return '';
        const value = payload.outputPreview != null ? payload.outputPreview
            : (payload.finalText != null ? payload.finalText
                : (payload.textSummary != null ? payload.textSummary
                    : (payload.machineSummary != null ? payload.machineSummary
                        : (payload.text != null ? payload.text : (payload.errorText != null ? payload.errorText : '')))));
        if (value == null) return '';
        if (typeof value === 'string') return value;
        return JSON.stringify(value, null, 2);
    } catch (_) {
        return '';
    }
}
