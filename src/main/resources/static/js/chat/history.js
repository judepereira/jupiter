import {getHtmxRequestPath} from './shared.js';
import {setBrowserNavigationRestorePending} from './scroll.js';

const HISTORY_STATE_KEY = 'chatNavigation';
let pendingUserNavigation = null;
let pendingSubagentNavigation = null;
let pendingRestore = null;
let restoreQueue = Promise.resolve();
let restoreGeneration = 0;
let activeEntry = null;

function chatContainerState() {
    const container = document.getElementById('chat-container');
    if (!container || !container.dataset) return null;
    const primarySessionId = String(container.dataset.sessionId || '').trim();
    if (!primarySessionId) return null;
    const subagentSessionId = String(container.dataset.subagentSessionId || '').trim();
    return {primarySessionId, subagentSessionId: subagentSessionId || null};
}

function chatScrollTop() {
    const history = document.getElementById('chat-history');
    return history ? Math.max(0, Number(history.scrollTop) || 0) : 0;
}

function makeHistoryState(view, scrollTop, hasHistoryParent) {
    return {
        [HISTORY_STATE_KEY]: true,
        primarySessionId: view.primarySessionId,
        subagentSessionId: view.subagentSessionId,
        chatScrollTop: Math.max(0, Number(scrollTop) || 0),
        hasHistoryParent: !!hasHistoryParent
    };
}

function isChatHistoryState(state) {
    return !!state && state[HISTORY_STATE_KEY] === true && !!String(state.primarySessionId || '').trim();
}

function sameView(left, right) {
    return left && right &&
        String(left.primarySessionId || '') === String(right.primarySessionId || '') &&
        (left.subagentSessionId || null) === (right.subagentSessionId || null);
}

function saveCurrentScroll() {
    const view = chatContainerState();
    if (!view || !isChatHistoryState(history.state)) return;
    if (String(history.state.primarySessionId) !== view.primarySessionId ||
        (history.state.subagentSessionId || null) !== view.subagentSessionId) return;
    const state = {...history.state, chatScrollTop: chatScrollTop()};
    history.replaceState(state, '', window.location.href);
    activeEntry = state;
}

function rememberScrollOnScroll(evt) {
    if (!evt || evt.target !== document.getElementById('chat-history')) return;
    saveCurrentScroll();
}

function navigationElement(elt, selector) {
    return elt && elt.closest ? elt.closest(selector) : null;
}

function isExplicitNavigationElement(elt, path) {
    if (path.includes('/ui/chat/subagent/')) return !!navigationElement(elt, '.tool-call-subagent-button');
    if (path.includes('/ui/chat/fork/')) return !!navigationElement(elt, '.chat-message-fork-button');
    if (/\/ui\/projects\/[^/?#]+\/activate(?:[/?#]|$)/.test(path)) return !!navigationElement(elt, '.project-tab');
    if (path.includes('/ui/projects/add')) return !!navigationElement(elt, '.project-form:not(.workspace-form)');
    if (/\/ui\/workspaces\/[^/?#]+\/activate(?:[/?#]|$)/.test(path)) return !!navigationElement(elt, '.workspace-item');
    if (path.includes('/ui/workspaces/add')) return !!navigationElement(elt, '.workspace-form');
    if (/\/ui\/sessions\/[^/?#]+\/activate(?:[/?#]|$)/.test(path)) return !!navigationElement(elt, '.session-item');
    if (path.includes('/ui/sessions/add')) return !!navigationElement(elt, '[data-session-create-form]');
    return false;
}

function isTrackedNavigationPath(path) {
    return path.includes('/ui/chat/subagent/') || path.includes('/ui/chat/fork/') ||
        path.includes('/ui/projects/add') || /\/ui\/projects\/[^/?#]+\/activate(?:[/?#]|$)/.test(path) ||
        path.includes('/ui/workspaces/add') || /\/ui\/workspaces\/[^/?#]+\/activate(?:[/?#]|$)/.test(path) ||
        path.includes('/ui/sessions/add') || /\/ui\/sessions\/[^/?#]+\/activate(?:[/?#]|$)/.test(path);
}

function sameRequest(record, detail) {
    if (!record || !detail) return false;
    if (record.xhr && detail.xhr && record.xhr === detail.xhr) return true;
    const paths = [
        getHtmxRequestPath({detail}),
        detail.xhr && detail.xhr.responseURL,
        detail.requestConfig && detail.requestConfig.path,
        detail.elt && detail.elt.getAttribute && (detail.elt.getAttribute('hx-get') || detail.elt.getAttribute('hx-post'))
    ].filter(Boolean).map(String);
    return paths.some(path => path === record.path || path.startsWith(record.path + '?') || path.includes(record.path));
}

function pushCurrentChatView(parentView) {
    const view = chatContainerState();
    if (!view) return;
    let current = isChatHistoryState(history.state) ? history.state : activeEntry;
    if (!current && parentView && !sameView(parentView, view)) {
        current = makeHistoryState(parentView, chatScrollTop(), false);
        history.replaceState(current, '', window.location.href);
        activeEntry = current;
    }
    if (sameView(current, view)) return;
    const state = makeHistoryState(view, chatScrollTop(), true);
    history.pushState(state, '', window.location.href);
    activeEntry = state;
}

function completeUserNavigation(evt) {
    const detail = evt && evt.detail;
    if (!pendingUserNavigation || !detail || detail.successful !== true || !sameRequest(pendingUserNavigation, detail)) return;
    pendingUserNavigation = null;
    pushCurrentChatView();
}

function scheduleSubagentHistoryPush(navigation) {
    if (pendingSubagentNavigation !== navigation || navigation.pushScheduled) return;
    navigation.pushScheduled = true;
    requestAnimationFrame(() => {
        if (pendingSubagentNavigation !== navigation) return;
        pendingSubagentNavigation = null;
        pendingUserNavigation = null;
        pushCurrentChatView(navigation.parentView);
    });
}

function successfulResponse(detail) {
    const status = Number(detail && detail.xhr && detail.xhr.status);
    return status >= 200 && status < 400;
}

function completeSubagentResponse(navigation, detail) {
    if (pendingSubagentNavigation !== navigation) return;
    navigation.xhr = (detail && detail.xhr) || navigation.xhr;
    if (!successfulResponse(detail)) {
        pendingSubagentNavigation = null;
        return;
    }
    navigation.successful = true;
}

function restoreChatScroll(state) {
    const historyElement = document.getElementById('chat-history');
    if (!historyElement) return;
    const requested = Math.max(0, Number(state.chatScrollTop) || 0);
    const max = Math.max(0, historyElement.scrollHeight - historyElement.clientHeight);
    historyElement.scrollTop = Math.min(requested, max);
}

function restoreAfterSettle(state) {
    requestAnimationFrame(() => requestAnimationFrame(() => {
        if (!pendingRestore || pendingRestore.state !== state) return;
        restoreChatScroll(state);
        activeEntry = state;
        pendingRestore = null;
        setBrowserNavigationRestorePending(false);
    }));
}

function restorePath(state) {
    let path = '/ui/chat/restore/' + encodeURIComponent(state.primarySessionId);
    if (state.subagentSessionId) path += '?childSessionId=' + encodeURIComponent(state.subagentSessionId);
    return path;
}

function restoreHistoryEntry(restore) {
    if (pendingRestore !== restore || restore.generation !== restoreGeneration) return Promise.resolve();
    if (!window.htmx || typeof window.htmx.ajax !== 'function') throw new Error('HTMX is required to restore chat history');
    const request = window.htmx.ajax('GET', restore.path, {target: '#shell', swap: 'none', headers: {'HX-Request': 'true'}});
    if (!request || typeof request.then !== 'function') throw new Error('HTMX restore request did not return a promise');
    return request
        .then(() => {
            if (pendingRestore === restore) restoreAfterSettle(restore.state);
        })
        .catch(error => {
            if (pendingRestore === restore) {
                pendingRestore = null;
                setBrowserNavigationRestorePending(false);
            }
            throw error;
        });
}

function handlePopState(evt) {
    const state = evt && evt.state;
    if (!isChatHistoryState(state)) return;
    saveCurrentScroll();
    const restore = {state, path: restorePath(state), generation: ++restoreGeneration};
    pendingRestore = restore;
    setBrowserNavigationRestorePending(true);
    restoreQueue = restoreQueue
        .then(() => restoreHistoryEntry(restore))
        .catch(error => console.error(error));
}

function handleBackButton(evt) {
    const button = navigationElement(evt && evt.target, '.subagent-back-button');
    if (!button) return;
    const state = history.state;
    if (!isChatHistoryState(state) || !state.subagentSessionId || !state.hasHistoryParent) return;
    evt.preventDefault();
    evt.stopImmediatePropagation();
    saveCurrentScroll();
    history.back();
}

function bindHistoryListeners() {
    document.addEventListener('click', handleBackButton, true);
    document.addEventListener('scroll', rememberScrollOnScroll, true);
    document.addEventListener('click', evt => {
        const button = navigationElement(evt && evt.target, '.tool-call-subagent-button');
        const path = button && button.getAttribute('hx-get');
        if (!path || !path.includes('/ui/chat/subagent/')) return;
        saveCurrentScroll();
        pendingSubagentNavigation = {path, xhr: null, parentView: chatContainerState(), successful: false};
    }, true);

    document.body.addEventListener('htmx:beforeRequest', evt => {
        const detail = evt && evt.detail;
        const path = getHtmxRequestPath(evt);
        if (!detail || !isTrackedNavigationPath(path) || !isExplicitNavigationElement(detail.elt, path)) return;
        saveCurrentScroll();
        if (path.includes('/ui/chat/subagent/')) {
            if (!pendingSubagentNavigation) {
                pendingSubagentNavigation = {path, xhr: detail.xhr, parentView: chatContainerState(), successful: false};
            } else {
                pendingSubagentNavigation.xhr = detail.xhr;
            }
            return;
        }
        pendingUserNavigation = {path, elt: detail.elt, xhr: detail.xhr, successful: false};
    }, true);

    document.addEventListener('htmx:beforeOnLoad', evt => {
        const detail = evt && evt.detail;
        if (!pendingSubagentNavigation || !sameRequest(pendingSubagentNavigation, detail)) return;
        const navigation = pendingSubagentNavigation;
        completeSubagentResponse(navigation, detail);
        scheduleSubagentHistoryPush(navigation);
    }, true);

    document.body.addEventListener('htmx:afterRequest', evt => {
        const detail = evt && evt.detail;
        if (pendingSubagentNavigation && sameRequest(pendingSubagentNavigation, detail)) return;
        if (!pendingUserNavigation || !sameRequest(pendingUserNavigation, detail)) return;
        pendingUserNavigation.xhr = detail.xhr || pendingUserNavigation.xhr;
        pendingUserNavigation.successful = detail.successful === true;
        if (!pendingUserNavigation.successful) pendingUserNavigation = null;
    }, true);

    document.body.addEventListener('htmx:afterSettle', evt => {
        if (pendingRestore && getHtmxRequestPath(evt).includes('/ui/chat/restore/')) return;
        if (!pendingUserNavigation || pendingUserNavigation.path.includes('/ui/chat/subagent/')) return;
        completeUserNavigation(evt);
    }, true);
}

export function initChatHistoryNavigation() {
    if (window.__chatHistoryNavigationInitialized) return;
    window.__chatHistoryNavigationInitialized = true;
    if ('scrollRestoration' in history) history.scrollRestoration = 'manual';
    const view = chatContainerState();
    if (view) {
        activeEntry = makeHistoryState(view, chatScrollTop(), false);
        history.replaceState(activeEntry, '', window.location.href);
    }
    bindHistoryListeners();
    window.addEventListener('popstate', handlePopState);
}
