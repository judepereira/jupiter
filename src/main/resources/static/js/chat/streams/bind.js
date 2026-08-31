import {getActiveChatSessionId} from '../shared.js';
import {getRawChatMarkdown, renderChatMarkdown, updateChatRowCompletion} from '../markdown.js';
import {applyToolCallHtmlPatches} from './tool-call-html.js';
import {refreshWorkspaceRail} from '../rail-sync.js';
import {getLiveChatRow, updateChatSendButtonState} from './control.js';
import {createStreamBuffer} from './buffer.js';
import {handleContextCompaction, parseStreamPayload} from './events.js';
import {clearPendingStreamState, getPendingStreamSource, registerPendingStream, setStopRequestInFlight} from './state.js';

function bindPendingStreams() {
    try {
        const list = document.getElementById('chat-messages-list');
        if (!list) return;
        const rows = list.querySelectorAll('li[data-pending="true"]');
        rows.forEach(row => {
            const assistantId = row.dataset.id != null ? String(row.dataset.id) : '';
            if (!assistantId) return;
            if (row.dataset.streamBound === '1') return;
            const url = row.dataset.streamUrl;
            if (!url) return;
            const existingSource = getPendingStreamSource(assistantId);
            if (existingSource) {
                try {
                    existingSource.close();
                } catch (_) {
                }
            }
            row.dataset.streamBound = '1';

            const streamSessionId = getActiveChatSessionId();
            const isStreamSessionActive = () => getActiveChatSessionId() === streamSessionId;
            const buffer = createStreamBuffer(assistantId, getLiveChatRow, isStreamSessionActive);
            const currentRow = () => isStreamSessionActive() ? getLiveChatRow(assistantId) : null;
            const currentTextSpan = () => buffer.currentTextSpan();

            const es = new EventSource(url);
            registerPendingStream(assistantId, es);
            updateChatSendButtonState();

            es.addEventListener('delta', e => {
                try {
                    const payload = parseStreamPayload(e);
                    if (payload && payload.text != null) {
                        buffer.pushDelta(payload.text);
                    }
                } catch (_) {
                }
            });

            es.addEventListener('tool_call_html', e => {
                try {
                    const stick = buffer.shouldStick() || buffer.wasNearBottom();
                    applyToolCallHtmlPatches(e);
                    if (stick) {
                        requestAnimationFrame(() => {
                            const history = document.getElementById('chat-history');
                            if (history) history.scrollTop = history.scrollHeight - history.clientHeight;
                            buffer.setShouldStickToBottom(true);
                        });
                    }
                } catch (error) {
                    console.error('Failed to apply tool-call HTML patch', error);
                    const liveRow = currentRow();
                    if (liveRow) liveRow.title = error instanceof Error ? error.message : String(error);
                    window.__connectionLossMonitor && window.__connectionLossMonitor.transportFailure();
                }
            });

            // Legacy tool-call events remain subscribed for protocol compatibility.
            es.addEventListener('tool_call_started', () => {});
            es.addEventListener('tool_call_progress', () => {});

            es.addEventListener('status', e => {
                try {
                    const payload = parseStreamPayload(e);
                    const st = (payload && payload.status != null) ? payload.status : (e.data || '');
                    const liveRow = currentRow();
                    if (st && liveRow) liveRow.title = st;
                } catch (_) {
                }
            });

            es.addEventListener('context_compaction', e => {
                try {
                    const payload = parseStreamPayload(e) || {};
                    handleContextCompaction(list, payload, () => buffer.shouldStick(), () => buffer.wasNearBottom(), isStreamSessionActive);
                } catch (_) {
                }
            });

            es.addEventListener('done', e => {
                try {
                    const payload = parseStreamPayload(e);
                    buffer.flushBuffer();

                    const textSpan = currentTextSpan();
                    if (payload && payload.text != null && textSpan) {
                        try {
                            const currentRaw = getRawChatMarkdown(textSpan);
                            if (String(payload.text) !== String(currentRaw)) {
                                renderChatMarkdown(textSpan, payload.text);
                            }
                            buffer.clearBuffer();
                        } catch (_) {
                            if (textSpan.textContent !== payload.text) {
                                try {
                                    textSpan.textContent = payload.text;
                                } catch (_) {
                                }
                            }
                            buffer.clearBuffer();
                        }
                    } else if (!buffer.hasDelta()) {
                        const data = e.data || '';
                        if (data && textSpan) {
                            try {
                                const prevRaw = getRawChatMarkdown(textSpan);
                                renderChatMarkdown(textSpan, prevRaw + data);
                            } catch (_) {
                                try {
                                    textSpan.textContent = textSpan.textContent + data;
                                } catch (_) {
                                }
                            }
                        }
                        buffer.flushBuffer();
                    }

                    const liveRow = currentRow();
                    if (liveRow) {
                        liveRow.classList.remove('pending');
                        liveRow.removeAttribute('data-pending');
                        liveRow.dataset.streamBound = '0';
                        if (payload && payload.completedTs != null) {
                            updateChatRowCompletion(liveRow, payload.completedTs);
                        }
                        updateChatSendButtonState();
                    }
                } catch (_) {
                }
                try {
                    es.close();
                } catch (_) {
                }
                setStopRequestInFlight(false);
                clearPendingStreamState(assistantId, es);
                updateChatSendButtonState();
                refreshWorkspaceRail();
                buffer.removeHistoryScrollListener();
            });

            es.addEventListener('stopped', e => {
                try {
                    const payload = parseStreamPayload(e);
                    buffer.flushBuffer();
                    const textSpan = currentTextSpan();
                    if (textSpan && payload && payload.message != null) {
                        renderChatMarkdown(textSpan, payload.message);
                    }
                    const liveRow = currentRow();
                    if (liveRow) {
                        liveRow.classList.remove('pending');
                        liveRow.removeAttribute('data-pending');
                        liveRow.dataset.streamBound = '0';
                        if (payload && payload.completedTs != null) {
                            updateChatRowCompletion(liveRow, payload.completedTs);
                        }
                        updateChatSendButtonState();
                    }
                } catch (_) {
                }
                try {
                    es.close();
                } catch (_) {
                }
                setStopRequestInFlight(false);
                clearPendingStreamState(assistantId, es);
                updateChatSendButtonState();
                refreshWorkspaceRail();
                buffer.removeHistoryScrollListener();
            });

            es.addEventListener('error', e => {
                try {
                    const rawData = e && typeof e.data === 'string' ? e.data.trim() : '';
                    const payload = parseStreamPayload(e);
                    const data = (payload && payload.message) ? payload.message : (rawData || 'Stream error');
                    if (rawData) {
                        const textSpan = currentTextSpan();
                        if (textSpan) {
                            try {
                                const prevRaw = getRawChatMarkdown(textSpan);
                                renderChatMarkdown(textSpan, prevRaw + '\n[Error: ' + data + ']');
                            } catch (_) {
                                try {
                                    textSpan.textContent = textSpan.textContent + '\n[Error: ' + data + ']';
                                } catch (_) {
                                }
                            }
                        }
                    } else if (!(e && e.error)) {
                        window.__connectionLossMonitor && window.__connectionLossMonitor.transportFailure();
                    }
                    const liveRow = currentRow();
                    if (liveRow) {
                        liveRow.classList.remove('pending');
                        liveRow.removeAttribute('data-pending');
                        liveRow.dataset.streamBound = '0';
                        if (payload && payload.completedTs != null) {
                            updateChatRowCompletion(liveRow, payload.completedTs);
                        }
                        updateChatSendButtonState();
                    }
                } catch (_) {
                }
                try {
                    es.close();
                } catch (_) {
                }
                setStopRequestInFlight(false);
                clearPendingStreamState(assistantId, es);
                updateChatSendButtonState();
                refreshWorkspaceRail();
                buffer.removeHistoryScrollListener();
            });

            // Legacy event retained as an ignored compatibility event.
            es.addEventListener('tool_call', () => {});

            es.addEventListener('close', () => {
                try {
                    buffer.flushBuffer();
                } catch (_) {
                }
                try {
                    es.close();
                } catch (_) {
                }
                try {
                    buffer.removeHistoryScrollListener();
                } catch (_) {
                }
                setStopRequestInFlight(false);
                clearPendingStreamState(assistantId, es);
                updateChatSendButtonState();
            });

            buffer.bindHistoryScrollListener();
        });
    } catch (_) {
    }
}

export {
    bindPendingStreams
};
