export function getHtmxRequestPath(evt) {
    try {
        const detail = evt && evt.detail;
        return String(
            (detail && detail.path) ||
            (detail && detail.requestConfig && detail.requestConfig.path) ||
            (detail && detail.pathInfo && (detail.pathInfo.requestPath || detail.pathInfo.finalRequestPath || detail.pathInfo.path)) ||
            (detail && detail.elt && typeof detail.elt.getAttribute === 'function' && (detail.elt.getAttribute('hx-get') || detail.elt.getAttribute('hx-post'))) ||
            (detail && detail.xhr && detail.xhr.responseURL) ||
            ''
        );
    } catch (_) {
        return '';
    }
}

export function getChatComposerForm() {
    return document.getElementById('chat-send-form');
}

export function getChatTextarea() {
    return document.getElementById('chat-input');
}

export function focusChatInput(preferredTextarea) {
    try {
        const textarea = preferredTextarea && preferredTextarea.isConnected ? preferredTextarea : getChatTextarea();
        if (!textarea) return;
        try {
            textarea.focus({preventScroll: true});
        } catch (_) {
            textarea.focus();
        }
    } catch (_) {
    }
}

export function getActiveChatSessionId() {
    try {
        const form = getChatComposerForm();
        const fromForm = form && form.dataset && form.dataset.sessionId != null ? String(form.dataset.sessionId).trim() : '';
        if (fromForm) return fromForm;
        const container = document.getElementById('chat-container');
        return container && container.dataset && container.dataset.sessionId != null ? String(container.dataset.sessionId).trim() : '';
    } catch (_) {
        return '';
    }
}

export function getCommandModalRoot() {
    return document.getElementById('modal-root');
}

export function escapeHtml(value) {
    return String(value == null ? '' : value)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}

export function processHtmxElement(element) {
    try {
        if (!element || (element.dataset && element.dataset.htmxProcessed === 'true')) return;
        if (window.htmx && typeof window.htmx.process === 'function') {
            window.htmx.process(element);
        }
        element.dataset.htmxProcessed = 'true';
    } catch (_) {
    }
}

export function syncFaviconWithRail() {
    try {
        const unreadDotPresent = !!document.querySelector('#workspace-session-rail .unread-dot');
        const favicon32 = document.getElementById('favicon-32x32');
        const favicon16 = document.getElementById('favicon-16x16');
        if (!favicon32 || !favicon16) return;
        const base = unreadDotPresent ? '/favicon-complete' : '/favicon';
        favicon32.setAttribute('href', base + '-32x32.png');
        favicon16.setAttribute('href', base + '-16x16.png');
    } catch (error) {
        console.error(error);
    }
}
