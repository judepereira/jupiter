export function getDirectToolCallChild(parent, className) {
    try {
        return Array.from(parent && parent.children ? parent.children : []).find(child => child && child.classList && child.classList.contains(className)) || null;
    } catch (_) {
        return null;
    }
}

export function getDirectToolCallChildren(parent, className) {
    try {
        return Array.from(parent && parent.children ? parent.children : []).filter(child => child && child.classList && child.classList.contains(className));
    } catch (_) {
        return [];
    }
}

export function getToolCallGroups(container) {
    return getDirectToolCallChildren(container, 'tool-call');
}

export function getToolCallGroupCalls(group) {
    try {
        const detail = getDirectToolCallChild(group, 'tool-call-detail');
        const callsContainer = getDirectToolCallChild(detail, 'tool-call-calls');
        return callsContainer ? Array.from(callsContainer.children).filter(child => child && child.classList && child.classList.contains('tool-call-call')) : [];
    } catch (_) {
        return [];
    }
}

export function getToolCallContainer(target) {
    try {
        if (!target) return null;
        if (target.classList && target.classList.contains('tool-calls')) return target;
        let container = getDirectToolCallChild(target, 'tool-calls');
        if (!container) {
            container = document.createElement('div');
            container.className = 'tool-calls';
            const text = target.querySelector && target.querySelector('.chat-message-text');
            const subtitle = target.querySelector && target.querySelector('.chat-message-subtitle');
            if (text && text.parentNode === target) {
                target.insertBefore(container, text);
            } else if (subtitle && subtitle.parentNode === target) {
                target.insertBefore(container, subtitle);
            } else {
                target.appendChild(container);
            }
        }
        return container;
    } catch (_) {
        return null;
    }
}

export function hasVisibleImageFigure(entry) {
    return Boolean(entry && entry.querySelector && entry.querySelector('.tool-call-image-preview'));
}

export function clearToolCallImages(entry) {
    try {
        if (!entry || !entry.querySelectorAll) return;
        Array.from(entry.querySelectorAll('.tool-call-image-preview')).forEach(node => node.remove());
    } catch (_) {
    }
}

export function bindDynamicSubagentButton(button) {
    try {
        if (!button || button.dataset.dynamicSubagentButtonBound === 'true') return;
        button.addEventListener('click', (e) => {
            try {
                e.stopPropagation();
                const url = button.getAttribute('hx-get');
                if (!url || !window.htmx || typeof window.htmx.ajax !== 'function') return;
                window.htmx.ajax('GET', url, {
                    target: button.getAttribute('hx-target') || '#chat-container',
                    swap: button.getAttribute('hx-swap') || 'outerHTML'
                });
            } catch (_) {
            }
        });
        button.dataset.dynamicSubagentButtonBound = 'true';
    } catch (_) {
    }
}
