import {getActiveChatSessionId, getChatTextarea} from './shared.js';

export const chatDraftAutosaveState = {
    sessionId: '',
    pendingValue: '',
    lastSavedValue: '',
    inFlightValue: null,
    timerId: null,
    clearEpoch: 0
};

function getDraftSaveUrl(sessionId) {
    const value = String(sessionId || getActiveChatSessionId() || '').trim();
    return value ? '/ui/sessions/' + encodeURIComponent(value) + '/draft' : '';
}

export function clearChatDraftAutosaveState(nextValue) {
    const value = String(nextValue != null ? nextValue : '');
    chatDraftAutosaveState.pendingValue = value;
    chatDraftAutosaveState.lastSavedValue = value;
    chatDraftAutosaveState.inFlightValue = null;
    chatDraftAutosaveState.clearEpoch += 1;
    if (chatDraftAutosaveState.timerId != null) {
        clearTimeout(chatDraftAutosaveState.timerId);
        chatDraftAutosaveState.timerId = null;
    }
}

export function invalidateChatDraftAutosaveState() {
    chatDraftAutosaveState.clearEpoch += 1;
    chatDraftAutosaveState.inFlightValue = null;
    if (chatDraftAutosaveState.timerId != null) {
        clearTimeout(chatDraftAutosaveState.timerId);
        chatDraftAutosaveState.timerId = null;
    }
}

export function syncChatDraftAutosaveStateFromTextarea(textarea, isFreshComposer) {
    if (!textarea) return;
    const sessionId = getActiveChatSessionId();
    const value = textarea.value || '';
    if (isFreshComposer || chatDraftAutosaveState.sessionId !== sessionId) {
        chatDraftAutosaveState.sessionId = sessionId;
        clearChatDraftAutosaveState(value);
        return;
    }
    chatDraftAutosaveState.pendingValue = value;
}

export function sendChatDraftSave(value, options) {
    const textarea = getChatTextarea();
    const sessionId = getActiveChatSessionId();
    if (!textarea || !sessionId) return Promise.resolve(false);

    const draft = String(value != null ? value : '');
    const useBeacon = !!(options && options.useBeacon);
    const keepalive = !!(options && options.keepalive);
    const clearEpochAtSend = chatDraftAutosaveState.clearEpoch;
    const requestBody = new URLSearchParams({draft: draft});
    const url = getDraftSaveUrl(sessionId);
    if (!url) return Promise.resolve(false);
    if (chatDraftAutosaveState.lastSavedValue === draft && chatDraftAutosaveState.inFlightValue == null) return Promise.resolve(false);
    if (chatDraftAutosaveState.inFlightValue === draft) return Promise.resolve(false);

    const commitSavedValue = () => {
        if (chatDraftAutosaveState.clearEpoch !== clearEpochAtSend) return;
        if (chatDraftAutosaveState.sessionId !== sessionId) return;
        if (chatDraftAutosaveState.inFlightValue !== draft) return;
        chatDraftAutosaveState.lastSavedValue = draft;
        chatDraftAutosaveState.inFlightValue = null;
        if (chatDraftAutosaveState.pendingValue !== draft) {
            scheduleChatDraftSave(chatDraftAutosaveState.pendingValue);
        }
    };

    chatDraftAutosaveState.inFlightValue = draft;
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
        if (chatDraftAutosaveState.inFlightValue === draft) {
            chatDraftAutosaveState.inFlightValue = null;
        }
        throw error;
    });
}

export function drainChatDraftSave(options) {
    const textarea = getChatTextarea();
    const sessionId = getActiveChatSessionId();
    if (!textarea || !sessionId) return Promise.resolve(false);

    const value = chatDraftAutosaveState.pendingValue != null ? String(chatDraftAutosaveState.pendingValue) : String(textarea.value || '');
    if (!options || !options.force) {
        if (value === chatDraftAutosaveState.lastSavedValue && chatDraftAutosaveState.inFlightValue == null) {
            return Promise.resolve(false);
        }
        if (chatDraftAutosaveState.inFlightValue != null) {
            return Promise.resolve(false);
        }
    }

    chatDraftAutosaveState.pendingValue = value;
    if (chatDraftAutosaveState.timerId != null) {
        clearTimeout(chatDraftAutosaveState.timerId);
        chatDraftAutosaveState.timerId = null;
    }
    return sendChatDraftSave(value, options || {});
}

export function scheduleChatDraftSave(value) {
    const textarea = getChatTextarea();
    const sessionId = getActiveChatSessionId();
    if (!textarea || !sessionId) return;

    const draft = String(value != null ? value : textarea.value || '');
    const clearEpochAtSchedule = chatDraftAutosaveState.clearEpoch;
    chatDraftAutosaveState.sessionId = sessionId;
    chatDraftAutosaveState.pendingValue = draft;
    if (chatDraftAutosaveState.timerId != null) {
        clearTimeout(chatDraftAutosaveState.timerId);
    }
    chatDraftAutosaveState.timerId = window.setTimeout(() => {
        chatDraftAutosaveState.timerId = null;
        if (chatDraftAutosaveState.clearEpoch !== clearEpochAtSchedule) return;
        drainChatDraftSave({keepalive: false, useBeacon: false});
    }, 500);
}

export function flushChatDraftSave(options) {
    const textarea = getChatTextarea();
    const sessionId = getActiveChatSessionId();
    if (!textarea || !sessionId) return Promise.resolve(false);

    const value = textarea.value || '';
    chatDraftAutosaveState.sessionId = sessionId;
    chatDraftAutosaveState.pendingValue = value;
    if (chatDraftAutosaveState.timerId != null) {
        clearTimeout(chatDraftAutosaveState.timerId);
        chatDraftAutosaveState.timerId = null;
    }
    return sendChatDraftSave(value, {keepalive: true, useBeacon: true, ...(options || {})});
}
