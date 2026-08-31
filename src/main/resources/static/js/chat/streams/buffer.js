import {getRawChatMarkdown, renderChatMarkdown} from '../markdown.js';
import {isInitialChatScrollActive} from '../scroll.js';

function createStreamBuffer(assistantId, getLiveChatRow, isStreamSessionActive) {
    const FLUSH_INTERVAL_MS = 40;

    let buffer = '';
    let gotDelta = false;
    let rafPending = false;
    let flushTimer = null;
    let lastFlushTime = 0;
    let streamHistoryEl = null;
    let shouldStickToBottom = false;

    function canAccessActiveHistory() {
        return isStreamSessionActive();
    }

    function currentRow() {
        return canAccessActiveHistory() ? getLiveChatRow(assistantId) : null;
    }

    function currentTextSpan() {
        const liveRow = currentRow();
        return liveRow ? liveRow.querySelector('.chat-message-text') : null;
    }

    function wasNearBottom() {
        if (!canAccessActiveHistory()) return false;
        try {
            const history = document.getElementById('chat-history');
            if (!history) return false;
            const max = history.scrollHeight - history.clientHeight;
            const cur = history.scrollTop;
            if (!Number.isFinite(max) || !Number.isFinite(cur)) return false;
            return (max - cur) <= 96;
        } catch (_) {
            return false;
        }
    }

    shouldStickToBottom = isInitialChatScrollActive() || wasNearBottom();

    function streamScrollListener() {
        shouldStickToBottom = wasNearBottom();
    }

    function bindHistoryScrollListener() {
        if (!canAccessActiveHistory()) return;
        try {
            streamHistoryEl = document.getElementById('chat-history');
            if (streamHistoryEl && streamHistoryEl.addEventListener) {
                streamHistoryEl.addEventListener('scroll', streamScrollListener, {passive: true});
            }
        } catch (_) {
            streamHistoryEl = null;
        }
    }

    function removeHistoryScrollListener() {
        try {
            if (streamHistoryEl && streamHistoryEl.removeEventListener) {
                streamHistoryEl.removeEventListener('scroll', streamScrollListener, {passive: true});
            }
        } catch (_) {
        }
    }

    function flushInner() {
        const textSpan = currentTextSpan();
        if (!textSpan) return;
        if (buffer.length === 0) {
            rafPending = false;
            if (flushTimer) {
                clearTimeout(flushTimer);
                flushTimer = null;
            }
            return;
        }
        try {
            const prevRaw = getRawChatMarkdown(textSpan);
            const newRaw = prevRaw + buffer;
            renderChatMarkdown(textSpan, newRaw);
        } catch (_) {
            try {
                textSpan.textContent = textSpan.textContent + buffer;
            } catch (_) {
            }
        }
        buffer = '';
        rafPending = false;
        lastFlushTime = Date.now();
        if (flushTimer) {
            clearTimeout(flushTimer);
            flushTimer = null;
        }
    }

    function flushBuffer() {
        if (!canAccessActiveHistory()) {
            buffer = '';
            rafPending = false;
            if (flushTimer) {
                clearTimeout(flushTimer);
                flushTimer = null;
            }
            return;
        }
        const stickBeforeFlush = isInitialChatScrollActive() || shouldStickToBottom || wasNearBottom();
        flushInner();
        if (stickBeforeFlush) {
            requestAnimationFrame(() => {
                if (!canAccessActiveHistory()) return;
                try {
                    const history = document.getElementById('chat-history');
                    if (history) history.scrollTop = history.scrollHeight - history.clientHeight;
                    shouldStickToBottom = true;
                } catch (_) {
                }
            });
        }
    }

    function scheduleFlush() {
        if (rafPending || flushTimer) return;
        const now = Date.now();
        if (lastFlushTime === 0) {
            flushBuffer();
            return;
        }
        const elapsed = now - lastFlushTime;
        if (elapsed >= FLUSH_INTERVAL_MS) {
            rafPending = true;
            requestAnimationFrame(flushBuffer);
            return;
        }
        flushTimer = setTimeout(() => {
            flushTimer = null;
            if (rafPending) return;
            rafPending = true;
            requestAnimationFrame(flushBuffer);
        }, FLUSH_INTERVAL_MS - elapsed);
    }

    function pushDelta(text) {
        buffer += text;
        gotDelta = true;
        scheduleFlush();
    }

    function clearBuffer() {
        buffer = '';
    }

    function hasDelta() {
        return gotDelta;
    }

    function setShouldStickToBottom(value) {
        shouldStickToBottom = Boolean(value);
    }

    function shouldStick() {
        return shouldStickToBottom;
    }

    return {
        bindHistoryScrollListener,
        clearBuffer,
        currentTextSpan,
        flushBuffer,
        hasDelta,
        pushDelta,
        removeHistoryScrollListener,
        scheduleFlush,
        setShouldStickToBottom,
        shouldStick,
        wasNearBottom
    };
}

export {
    createStreamBuffer
};
