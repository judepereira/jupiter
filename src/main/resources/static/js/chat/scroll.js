import {focusChatInput, getHtmxRequestPath} from './dom.js';
import {bindChatDraftFlushListeners} from './autosave.js';

let lastMessageCount = -1;
let chatAutoScrollBound = false;
let wasNearBottomBeforeSwap = false;
let primaryChatScrollState = null;
let primaryChatScrollRestorePending = false;
let subagentScrollRestoreBound = false;
let sessionChangeScrollRestoreBound = false;

function scrollChatToBottom(after) {
    try {
        const history = document.getElementById('chat-history');
        const list = document.getElementById('chat-messages-list');
        if (!history || !list) return;
        requestAnimationFrame(() => {
            const target = history.scrollHeight - history.clientHeight;
            if (Number.isFinite(target)) history.scrollTop = target;
            if (typeof after === 'function') after();
        });
    } catch (_) {
    }
}

function checkAndMaybeScroll() {
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

function capturePrimaryChatScrollState(getCurrentOpenSubagentSessionId) {
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

function syncChatAfterSessionChange(initChatComposer) {
    try {
        const list = document.getElementById('chat-messages-list');
        const history = document.getElementById('chat-history');
        if (!list || !history) return;
        scrollChatToBottom(() => focusChatInput());
        lastMessageCount = list.children ? list.children.length : 0;
        wasNearBottomBeforeSwap = false;
        if (typeof initChatComposer === 'function') initChatComposer(window.__chatRuntime || {});
    } catch (_) {
    }
}

export function bindAutoScrollListeners(deps) {
    if (chatAutoScrollBound) return;
    chatAutoScrollBound = true;

    const runtime = deps || window.__chatRuntime || {};
    const getCurrentOpenSubagentSessionId = runtime && runtime.getCurrentOpenSubagentSessionId ? runtime.getCurrentOpenSubagentSessionId : () => '';
    const initChatComposer = runtime && runtime.initChatComposer;

    function bindSubagentScrollListeners() {
        if (subagentScrollRestoreBound) return;
        subagentScrollRestoreBound = true;

        document.addEventListener('click', function (evt) {
            try {
                const target = evt && evt.target;
                const subagentButton = target && target.closest ? target.closest('.tool-call-subagent-button') : null;
                if (subagentButton) {
                    capturePrimaryChatScrollState(getCurrentOpenSubagentSessionId);
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
                Promise.resolve().then(() => syncChatAfterSessionChange(initChatComposer));
            } catch (_) {
            }
        }, true);
    }

    function htmxBeforeSwapListener(evt) {
        try {
            const trg = (evt && evt.detail && evt.detail.target) || evt.target;
            if (!trg) return;
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

    document.body.addEventListener('htmx:beforeSwap', htmxBeforeSwapListener, true);
    document.body.addEventListener('htmx:afterSwap', htmxChatListener, true);
    document.body.addEventListener('htmx:afterSettle', htmxChatListener, true);
    bindSubagentScrollListeners();
    bindSessionChangeScrollListeners();
    bindChatDraftFlushListeners();
}

export function checkAndMaybeScrollPublic() {
    checkAndMaybeScroll();
}

export function scrollChatToBottomPublic(after) {
    scrollChatToBottom(after);
}
