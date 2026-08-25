import {getActiveChatSessionId, getChatTextarea, getHtmxRequestPath} from './dom.js';

const state = {
    sessionId: '',
    pendingValue: '',
    lastSavedValue: '',
    inFlightValue: null,
    timerId: null,
    clearEpoch: 0
};

let draftFlushBound = false;

export function isDraftSaveRequestPath(path) {
    const value = String(path || '');
    return /\/ui\/sessions\/[^/?#]+\/draft(?:[/?#]|$)/.test(value);
}

export function shouldFlushDraftBeforeRequest(path) {
    const value = String(path || '');
    if (!value) return false;
    if (value.includes('/ui/chat/send')) return false;
    if (isDraftSaveRequestPath(value)) return false;
    return /\/ui\/(chat\/primary|chat\/subagent\/|projects|workspaces|sessions)\b/.test(value);
}

export function getDraftSaveUrl(sessionId) {
    const value = String(sessionId || getActiveChatSessionId() || '').trim();
    return value ? '/ui/sessions/' + encodeURIComponent(value) + '/draft' : '';
}

export function clearChatDraftAutosaveState(nextValue) {
    const value = String(nextValue != null ? nextValue : '');
    state.pendingValue = value;
    state.lastSavedValue = value;
    state.inFlightValue = null;
    state.clearEpoch += 1;
    if (state.timerId != null) {
        clearTimeout(state.timerId);
        state.timerId = null;
    }
}

export function invalidateChatDraftAutosaveState() {
    state.clearEpoch += 1;
    state.inFlightValue = null;
    if (state.timerId != null) {
        clearTimeout(state.timerId);
        state.timerId = null;
    }
}

export function syncChatDraftAutosaveStateFromTextarea(textarea, isFreshComposer) {
    if (!textarea) return;
    const sessionId = getActiveChatSessionId();
    const value = textarea.value || '';
    if (isFreshComposer || state.sessionId !== sessionId) {
        state.sessionId = sessionId;
        clearChatDraftAutosaveState(value);
        return;
    }
    state.pendingValue = value;
}

export function scheduleChatDraftSave(value) {
    const textarea = getChatTextarea();
    const sessionId = getActiveChatSessionId();
    if (!textarea || !sessionId) return;

    const draft = String(value != null ? value : textarea.value || '');
    const clearEpochAtSchedule = state.clearEpoch;
    state.sessionId = sessionId;
    state.pendingValue = draft;
    if (state.timerId != null) {
        clearTimeout(state.timerId);
    }
    state.timerId = window.setTimeout(() => {
        state.timerId = null;
        if (state.clearEpoch !== clearEpochAtSchedule) return;
        drainChatDraftSave({keepalive: false, useBeacon: false});
    }, 500);
}

export function sendChatDraftSave(value, options, deps) {
    const textarea = (deps && deps.getChatTextarea ? deps.getChatTextarea : getChatTextarea)();
    const sessionId = (deps && deps.getActiveChatSessionId ? deps.getActiveChatSessionId : getActiveChatSessionId)();
    if (!textarea || !sessionId) return Promise.resolve(false);

    const draft = String(value != null ? value : '');
    const useBeacon = !!(options && options.useBeacon);
    const keepalive = !!(options && options.keepalive);
    const clearEpochAtSend = state.clearEpoch;
    const requestBody = new URLSearchParams({draft: draft});
    const url = getDraftSaveUrl(sessionId);
    if (!url) return Promise.resolve(false);
    if (state.lastSavedValue === draft && state.inFlightValue == null) return Promise.resolve(false);
    if (state.inFlightValue === draft) return Promise.resolve(false);

    const commitSavedValue = () => {
        if (state.clearEpoch !== clearEpochAtSend) return;
        if (state.sessionId !== sessionId) return;
        if (state.inFlightValue !== draft) return;
        state.lastSavedValue = draft;
        state.inFlightValue = null;
        if (state.pendingValue !== draft) {
            scheduleChatDraftSave(state.pendingValue);
        }
    };

    state.inFlightValue = draft;
    if (useBeacon && navigator && typeof navigator.sendBeacon === 'function') {
        const queued = navigator.sendBeacon(url, requestBody);
        if (queued) {
            commitSavedValue();
            return Promise.resolve(true);
        }
    }

    return fetch(url, {
        method: 'POST',
        headers: {'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8'},
        body: requestBody,
        keepalive: keepalive,
        credentials: 'same-origin'
    }).then(response => {
        if (!response.ok) throw new Error('Failed to save chat draft');
        commitSavedValue();
        return true;
    }).catch(error => {
        console.error(error);
        if (state.inFlightValue === draft) {
            state.inFlightValue = null;
        }
        throw error;
    });
}

export function drainChatDraftSave(options) {
    const textarea = getChatTextarea();
    const sessionId = getActiveChatSessionId();
    if (!textarea || !sessionId) return Promise.resolve(false);

    const value = state.pendingValue != null ? String(state.pendingValue) : String(textarea.value || '');
    if (!options || !options.force) {
        if (value === state.lastSavedValue && state.inFlightValue == null) {
            return Promise.resolve(false);
        }
        if (state.inFlightValue != null) {
            return Promise.resolve(false);
        }
    }

    state.pendingValue = value;
    if (state.timerId != null) {
        clearTimeout(state.timerId);
        state.timerId = null;
    }
    return sendChatDraftSave(value, options || {});
}

export function flushChatDraftSave(options) {
    const textarea = getChatTextarea();
    const sessionId = getActiveChatSessionId();
    if (!textarea || !sessionId) return Promise.resolve(false);

    const value = textarea.value || '';
    state.sessionId = sessionId;
    state.pendingValue = value;
    if (state.timerId != null) {
        clearTimeout(state.timerId);
        state.timerId = null;
    }
    return sendChatDraftSave(value, {keepalive: true, useBeacon: true, ...(options || {})});
}

export function bindChatDraftFlushListeners() {
    if (draftFlushBound) return;
    draftFlushBound = true;

    document.body.addEventListener('htmx:beforeRequest', function (evt) {
        try {
            const path = getHtmxRequestPath(evt);
            if (String(path || '').includes('/ui/chat/send')) {
                invalidateChatDraftAutosaveState();
                return;
            }
            if (!shouldFlushDraftBeforeRequest(path)) return;
            flushChatDraftSave({keepalive: true, useBeacon: true});
        } catch (_) {
        }
    }, true);
}
