import {
    focusChatInput,
    getCurrentOpenSubagentSessionId,
    getHtmxRequestPath
} from './shared.js';

const INITIAL_SCROLL_MAX_MS = 5000;
const INITIAL_SCROLL_STABLE_FRAMES = 3;

let lastMessageCount = -1;
let chatAutoScrollBound = false;
let wasNearBottomBeforeSwap = false;
let primaryChatScrollState = null;
let primaryChatScrollRestorePending = false;
let subagentScrollRestoreBound = false;
let sessionChangeScrollRestoreBound = false;
let initialChatScroll = null;
let initialChatScrollListenersBound = false;

function setHistoryScrollTop(history, target) {
    history.scrollTop = target;
}

export function scrollChatToBottom(after) {
    try {
        const history = document.getElementById('chat-history');
        const list = document.getElementById('chat-messages-list');
        if (!history || !list) return;
        requestAnimationFrame(() => {
            const target = history.scrollHeight - history.clientHeight;
            if (Number.isFinite(target)) setHistoryScrollTop(history, target);
            if (typeof after === 'function') after();
        });
    } catch (_) {
    }
}

function removeInitialImageListeners(state) {
    state.imageListeners.forEach((listener, image) => {
        image.removeEventListener('load', listener);
        image.removeEventListener('error', listener);
    });
    state.imageListeners.clear();
}

function finishInitialChatScroll() {
    const state = initialChatScroll;
    if (!state || state.finished) return;
    state.finished = true;
    if (state.rafId != null) cancelAnimationFrame(state.rafId);
    if (state.timeoutId != null) clearTimeout(state.timeoutId);
    if (state.resizeObserver) state.resizeObserver.disconnect();
    if (state.mutationObserver) state.mutationObserver.disconnect();
    removeInitialImageListeners(state);
    if (state.interactionCleanup) state.interactionCleanup();
}

function cancelInitialChatScroll() {
    if (!initialChatScroll || initialChatScroll.finished) return;
    initialChatScroll.cancelled = true;
    finishInitialChatScroll();
}

function queueInitialChatScroll(resetStability) {
    const state = initialChatScroll;
    if (!state || state.finished) return;
    if (resetStability) state.stableFrames = 0;
    if (state.rafId == null) {
        state.rafId = requestAnimationFrame(runInitialChatScroll);
    }
}

function observeInitialImages(state, list) {
    list.querySelectorAll('img').forEach(image => {
        if (state.imageListeners.has(image)) return;
        const listener = () => queueInitialChatScroll(true);
        state.imageListeners.set(image, listener);
        image.addEventListener('load', listener);
        image.addEventListener('error', listener);
    });
}

function connectInitialChatObservers(state, history, list) {
    if (state.history !== history) {
        state.history = history;
        if (state.resizeObserver) state.resizeObserver.observe(history);
    }
    if (state.list === list) return true;
    if (state.list || (state.initialList && state.initialList !== list)) {
        cancelInitialChatScroll();
        return false;
    }

    if (state.mutationObserver) state.mutationObserver.disconnect();
    removeInitialImageListeners(state);
    state.list = list;
    state.initialList = list;
    if (state.resizeObserver) state.resizeObserver.observe(list);
    if (state.mutationObserver) {
        state.mutationObserver.observe(list, {childList: true, subtree: true});
    }
    observeInitialImages(state, list);
    state.stableFrames = 0;
    return true;
}

function runInitialChatScroll() {
    const state = initialChatScroll;
    if (!state || state.finished) return;
    state.rafId = null;

    if (Date.now() >= state.deadline) {
        finishInitialChatScroll();
        return;
    }

    const history = document.getElementById('chat-history');
    const list = document.getElementById('chat-messages-list');
    if (!history || !list) {
        queueInitialChatScroll(false);
        return;
    }

    if (!connectInitialChatObservers(state, history, list)) return;
    const target = Math.max(0, history.scrollHeight - history.clientHeight);
    setHistoryScrollTop(history, target);

    const currentMax = Math.max(0, history.scrollHeight - history.clientHeight);
    if (Math.abs(currentMax - target) <= 1 && Math.abs(history.scrollTop - target) <= 1) {
        state.stableFrames += 1;
    } else {
        state.stableFrames = 0;
    }

    if (state.pageLoaded && state.stableFrames >= INITIAL_SCROLL_STABLE_FRAMES) {
        finishInitialChatScroll();
        return;
    }
    queueInitialChatScroll(false);
}

function bindInitialChatScrollListeners() {
    if (initialChatScrollListenersBound) return;
    initialChatScrollListenersBound = true;

    initialChatScroll = {
        cancelled: false,
        deadline: Date.now() + INITIAL_SCROLL_MAX_MS,
        finished: false,
        history: null,
        imageListeners: new Map(),
        list: null,
        mutationObserver: typeof MutationObserver === 'function' ? new MutationObserver(() => queueInitialChatScroll(true)) : null,
        pageLoaded: document.readyState === 'complete',
        rafId: null,
        resizeObserver: typeof ResizeObserver === 'function' ? new ResizeObserver(() => queueInitialChatScroll(true)) : null,
        stableFrames: 0,
        timeoutId: null
    };

    const markUserInteraction = event => {
        const target = event && event.target;
        if (target && target.closest && target.closest('#chat-history')) cancelInitialChatScroll();
    };
    document.addEventListener('pointerdown', markUserInteraction, true);
    document.addEventListener('wheel', markUserInteraction, {capture: true, passive: true});
    document.addEventListener('touchstart', markUserInteraction, {capture: true, passive: true});
    document.addEventListener('keydown', markUserInteraction, true);
    initialChatScroll.interactionCleanup = () => {
        document.removeEventListener('pointerdown', markUserInteraction, true);
        document.removeEventListener('wheel', markUserInteraction, true);
        document.removeEventListener('touchstart', markUserInteraction, true);
        document.removeEventListener('keydown', markUserInteraction, true);
    };

    const startAfterDomReady = () => queueInitialChatScroll(true);
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', startAfterDomReady, {once: true});
    } else {
        startAfterDomReady();
    }

    const markPageLoaded = () => {
        if (!initialChatScroll || initialChatScroll.finished) return;
        initialChatScroll.pageLoaded = true;
        queueInitialChatScroll(true);
    };
    if (document.readyState !== 'complete') {
        window.addEventListener('load', markPageLoaded, {once: true});
    }

    initialChatScroll.timeoutId = setTimeout(finishInitialChatScroll, INITIAL_SCROLL_MAX_MS);
    queueInitialChatScroll(false);
}

export function isInitialChatScrollActive() {
    return Boolean(initialChatScroll && !initialChatScroll.finished && !initialChatScroll.cancelled);
}

export function checkAndMaybeScroll() {
    try {
        const list = document.getElementById('chat-messages-list');
        if (!list) {
            lastMessageCount = -1;
            return;
        }
        const count = list.children ? list.children.length : 0;
        if (lastMessageCount === -1) {
            lastMessageCount = count;
            scrollChatToBottom();
            return;
        }
        if (count > lastMessageCount) {
            lastMessageCount = count;
            scrollChatToBottom();
        } else {
            lastMessageCount = count;
        }
    } catch (_) {
    }
}

function isHistoryNearBottom() {
    try {
        const history = document.getElementById('chat-history');
        if (!history) return false;
        const max = history.scrollHeight - history.clientHeight;
        const cur = history.scrollTop;
        if (!Number.isFinite(max) || !Number.isFinite(cur)) return false;
        return (max - cur) <= 48;
    } catch (_) {
        return false;
    }
}

function capturePrimaryChatScrollState() {
    try {
        if (getCurrentOpenSubagentSessionId()) return;
        const history = document.getElementById('chat-history');
        if (!history) return;
        const max = Math.max(0, history.scrollHeight - history.clientHeight);
        const scrollTop = history.scrollTop;
        const bottomOffset = Math.max(0, max - scrollTop);
        primaryChatScrollState = {
            scrollTop: scrollTop,
            bottomOffset: bottomOffset,
            nearBottom: bottomOffset <= 48
        };
        primaryChatScrollRestorePending = false;
    } catch (_) {
    }
}

function restorePrimaryChatScrollState() {
    try {
        if (!primaryChatScrollRestorePending || !primaryChatScrollState) return;
        const history = document.getElementById('chat-history');
        if (!history) return;
        requestAnimationFrame(() => requestAnimationFrame(() => {
            try {
                const max = Math.max(0, history.scrollHeight - history.clientHeight);
                const target = primaryChatScrollState.nearBottom
                    ? max
                    : Math.min(Math.max(primaryChatScrollState.scrollTop, 0), max);
                history.scrollTop = target;
                primaryChatScrollRestorePending = false;
            } catch (_) {
            }
        }));
    } catch (_) {
    }
}

function isSessionActivationOrAddPath(path) {
    const value = String(path || '');
    return value.includes('/ui/sessions/add') || /\/ui\/sessions\/[^/?#]+\/activate(?:[/?#]|$)/.test(value);
}

function syncChatAfterSessionChange() {
    try {
        const list = document.getElementById('chat-messages-list');
        const history = document.getElementById('chat-history');
        if (!list || !history) return;
        scrollChatToBottom(() => focusChatInput());
        lastMessageCount = list.children ? list.children.length : 0;
        wasNearBottomBeforeSwap = false;
    } catch (_) {
    }
}

function bindSubagentScrollListeners() {
    if (subagentScrollRestoreBound) return;
    subagentScrollRestoreBound = true;

    document.addEventListener('click', function (evt) {
        try {
            const target = evt && evt.target;
            const subagentButton = target && target.closest ? target.closest('.tool-call-subagent-button') : null;
            if (subagentButton) {
                capturePrimaryChatScrollState();
                return;
            }
            const backButton = target && target.closest ? target.closest('.subagent-back-button') : null;
            if (backButton && primaryChatScrollState) {
                primaryChatScrollRestorePending = true;
            }
        } catch (_) {
        }
    }, true);

    document.body.addEventListener('htmx:afterSettle', function (evt) {
        try {
            const detail = evt && evt.detail;
            const target = detail && detail.target;
            const path = getHtmxRequestPath(evt);
            if (!primaryChatScrollRestorePending || !primaryChatScrollState) return;
            if (!path.includes('/ui/chat/primary')) return;
            if (!target || target.id !== 'chat-container') return;
            restorePrimaryChatScrollState();
        } catch (_) {
        }
    }, true);
}

function bindSessionChangeScrollListeners() {
    if (sessionChangeScrollRestoreBound) return;
    sessionChangeScrollRestoreBound = true;

    document.body.addEventListener('htmx:afterSettle', function (evt) {
        try {
            const path = getHtmxRequestPath(evt);
            if (!isSessionActivationOrAddPath(path)) return;
            Promise.resolve().then(syncChatAfterSessionChange);
        } catch (_) {
        }
    }, true);
}

function htmxBeforeSwapListener(evt) {
    try {
        const trg = (evt && evt.detail && evt.detail.target) || evt.target;
        if (!trg) return;
        if (trg.id === 'chat-container') cancelInitialChatScroll();
        if (trg.id === 'chat-history' || trg.id === 'chat-messages-list' ||
            (trg.closest && (trg.closest('#chat-history') || trg.closest('#chat-messages-list') || trg.closest('#chat-send-form') || trg.closest('#chat-input')))) {
            wasNearBottomBeforeSwap = isHistoryNearBottom();
        }
    } catch (_) {
    }
}

function htmxChatListener(evt) {
    try {
        const trg = (evt && evt.detail && evt.detail.target) || evt.target;
        if (!trg) return;

        if (trg.id === 'chat-history' || trg.id === 'chat-messages-list' ||
            (trg.closest && (trg.closest('#chat-history') || trg.closest('#chat-messages-list') || trg.closest('#chat-send-form') || trg.closest('#chat-input')))) {
            Promise.resolve().then(checkAndMaybeScroll);
            if (wasNearBottomBeforeSwap) {
                Promise.resolve().then(() => {
                    scrollChatToBottom();
                    wasNearBottomBeforeSwap = false;
                });
            }
        }
    } catch (_) {
    }
}

export function bindAutoScrollListeners() {
    if (chatAutoScrollBound) return;
    chatAutoScrollBound = true;
    document.body.addEventListener('htmx:beforeSwap', htmxBeforeSwapListener, true);
    document.body.addEventListener('htmx:afterSwap', htmxChatListener, true);
    document.body.addEventListener('htmx:afterSettle', htmxChatListener, true);
    bindSubagentScrollListeners();
    bindSessionChangeScrollListeners();
    bindInitialChatScrollListeners();
}
