import {getRawChatMarkdown, renderChatMarkdown} from '../markdown.js';
import {getOpenSubagentPendingRow, clearPendingChatRowState} from './payload.js';
import {appendToolCallToChatRow} from './render.js';

export function updateOpenSubagentTranscript(payload, kind) {
    try {
        const childSessionId = payload && payload.childSessionId != null ? String(payload.childSessionId) : '';
        const row = getOpenSubagentPendingRow(childSessionId);
        if (!row) return;

        const textSpan = row.querySelector('.chat-message-text');
        if (!textSpan) return;

        if (kind === 'delta') {
            if (payload && payload.delta != null) {
                const prevRaw = getRawChatMarkdown(textSpan);
                renderChatMarkdown(textSpan, prevRaw + String(payload.delta));
            }
            return;
        }

        if (kind === 'started') {
            if (payload && payload.task != null) {
                renderChatMarkdown(textSpan, String(payload.task));
            }
            return;
        }

        if (kind === 'done') {
            if (payload && payload.finalText != null) {
                renderChatMarkdown(textSpan, String(payload.finalText));
            } else if (payload && payload.text != null) {
                renderChatMarkdown(textSpan, String(payload.text));
            }
            clearPendingChatRowState(row);
            return;
        }

        if (kind === 'error') {
            const errorText = payload && payload.errorText != null ? String(payload.errorText) : 'Subagent error';
            const prevRaw = getRawChatMarkdown(textSpan);
            renderChatMarkdown(textSpan, prevRaw + '\n[Error: ' + errorText + ']');
            clearPendingChatRowState(row);
            return;
        }

        if (kind === 'tool_call') {
            appendToolCallToChatRow(row, payload);
        }
    } catch (_) {
    }
}
