import {focusChatInput} from './shared.js';
import {scheduleChatDraftSave} from './draft-autosave.js';
import {renderAllChatMarkdown} from './markdown.js';
import {checkAndMaybeScroll} from './scroll.js';

const commandPickerState = {
    open: false,
    query: '/',
    activeIndex: 0,
    catalog: [],
    textarea: null,
    modal: null,
    card: null,
    input: null,
    list: null,
    repositionHandler: null,
    visualViewport: null,
    positionFrame: null,
    fetchPromise: null
};

let resizeChatTextarea = () => {};
let bindPendingStreams = () => {};

export function configureCommandPicker(config) {
    if (!config) return;
    if (typeof config.resizeChatTextarea === 'function') {
        resizeChatTextarea = config.resizeChatTextarea;
    }
    if (typeof config.bindPendingStreams === 'function') {
        bindPendingStreams = config.bindPendingStreams;
    }
}

export function isCommandPickerOpen() {
    return commandPickerState.open;
}

function getCommandModalRoot() {
    return document.getElementById('modal-root');
}

export function closeCommandPicker() {
    if (!commandPickerState.open) return;
    const textarea = commandPickerState.textarea;
    const root = getCommandModalRoot();
    if (root) root.innerHTML = '';
    commandPickerState.open = false;
    commandPickerState.textarea = null;
    if (commandPickerState.repositionHandler) {
        window.removeEventListener('resize', commandPickerState.repositionHandler);
        window.removeEventListener('scroll', commandPickerState.repositionHandler, true);
        if (commandPickerState.visualViewport) {
            commandPickerState.visualViewport.removeEventListener('resize', commandPickerState.repositionHandler);
            commandPickerState.visualViewport.removeEventListener('scroll', commandPickerState.repositionHandler);
            commandPickerState.visualViewport = null;
        }
        commandPickerState.repositionHandler = null;
    }
    if (commandPickerState.positionFrame != null) {
        cancelAnimationFrame(commandPickerState.positionFrame);
        commandPickerState.positionFrame = null;
    }
    commandPickerState.modal = null;
    commandPickerState.card = null;
    commandPickerState.input = null;
    commandPickerState.list = null;
    focusChatInput(textarea);
}

function fetchCommandCatalog() {
    if (!commandPickerState.fetchPromise) {
        commandPickerState.fetchPromise = fetch('/ui/commands/catalog', {headers: {'HX-Request': 'true'}})
            .then(response => {
                if (!response.ok) throw new Error('Failed to load command catalog');
                return response.json();
            });
    }
    return commandPickerState.fetchPromise;
}

function commandMatchesQuery(command, query) {
    const normalized = String(query || '').trim().toLowerCase();
    if (!normalized || normalized === '/') return true;
    return ('/' + String(command && command.id ? command.id : '').toLowerCase()).startsWith(normalized);
}

function filteredCommands(query) {
    return (commandPickerState.catalog || []).filter(command => commandMatchesQuery(command, query));
}

function escapeHtml(value) {
    return String(value == null ? '' : value)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}

function requestCommandPickerPosition() {
    if (!commandPickerState.open) return;
    if (commandPickerState.positionFrame != null) return;
    commandPickerState.positionFrame = requestAnimationFrame(() => {
        commandPickerState.positionFrame = null;
        positionCommandPicker();
    });
}

function positionCommandPicker() {
    const modal = commandPickerState.modal;
    const card = commandPickerState.card;
    const textarea = commandPickerState.textarea;
    if (!modal || !card || !textarea) return;

    const rect = textarea.getBoundingClientRect();
    const viewportPadding = 16;
    const cardMaxHeight = 400;
    const maxCardWidth = Math.max(0, window.innerWidth - viewportPadding * 2);
    const cardWidth = Math.min(maxCardWidth, Math.min(640, Math.max(320, rect.width)));
    const left = Math.max(viewportPadding, Math.min(rect.left, window.innerWidth - cardWidth - viewportPadding));

    modal.style.left = left + 'px';
    modal.style.width = cardWidth + 'px';
    card.style.maxHeight = cardMaxHeight + 'px';

    const renderedHeight = Math.min(cardMaxHeight, Math.max(0, card.getBoundingClientRect().height || card.scrollHeight || 0));
    const top = Math.max(viewportPadding, rect.top - renderedHeight);
    modal.style.top = top + 'px';
}

function renderCommandPickerList() {
    const list = commandPickerState.list;
    const input = commandPickerState.input;
    if (!list || !input) return;

    const query = input.value || '/';
    commandPickerState.query = query;
    const commands = filteredCommands(query);
    if (commandPickerState.activeIndex >= commands.length) {
        commandPickerState.activeIndex = commands.length ? commands.length - 1 : 0;
    }

    list.innerHTML = commands.map((command, index) => {
        const id = String(command.id || '');
        const name = String(command.name || id);
        const description = String(command.description || '');
        const type = String(command.type || command.kind || '').toLowerCase();
        const active = index === commandPickerState.activeIndex ? ' is-active' : '';
        return '<li class="command-modal-item' + active + '" data-command-id="' + escapeHtml(id) + '" data-command-type="' + escapeHtml(type) + '">' +
            '<div class="command-modal-item-command">/' + escapeHtml(id) + '</div>' +
            '<div class="command-modal-item-meta">' + escapeHtml(name) + (description ? ' — ' + escapeHtml(description) : '') + '</div>' +
            '<div class="command-modal-item-kind">' + escapeHtml(type) + '</div>' +
            '</li>';
    }).join('');

    const activeItem = list.querySelector('.command-modal-item.is-active');
    if (activeItem && activeItem.scrollIntoView) {
        activeItem.scrollIntoView({block: 'nearest'});
    }

    requestCommandPickerPosition();
}

function setCommandPickerActiveIndex(nextIndex) {
    const commands = filteredCommands(commandPickerState.input ? commandPickerState.input.value : '/');
    if (!commands.length) {
        commandPickerState.activeIndex = 0;
        renderCommandPickerList();
        return;
    }
    const normalized = (nextIndex + commands.length) % commands.length;
    commandPickerState.activeIndex = normalized;
    renderCommandPickerList();
}

function insertCommandText(textarea, text) {
    textarea.value = String(text || '');
    resizeChatTextarea(textarea);
    scheduleChatDraftSave(textarea.value);
    textarea.focus({preventScroll: true});
    textarea.setSelectionRange(textarea.value.length, textarea.value.length);
}

function appendChatHtml(html) {
    const list = document.getElementById('chat-messages-list');
    if (!list || !html) return;
    list.insertAdjacentHTML('beforeend', html);
    try {
        renderAllChatMarkdown(list);
    } catch (_) {
    }
    try {
        bindPendingStreams();
    } catch (_) {
    }
    checkAndMaybeScroll();
}

function executeCommand(command) {
    if (!command) return;
    const textarea = commandPickerState.textarea || document.getElementById('chat-input');
    if (String(command.type || command.kind || '').toLowerCase() === 'prompt') {
        if (textarea) {
            insertCommandText(textarea, command.body || '');
        }
        closeCommandPicker();
        return;
    }

    if (textarea) {
        textarea.value = '';
        textarea.dataset.chatSlashRestoredValue = '';
        resizeChatTextarea(textarea);
        scheduleChatDraftSave('');
    }
    closeCommandPicker();
    fetch('/ui/commands/' + encodeURIComponent(command.id) + '/execute', {
        method: 'POST',
        headers: {'HX-Request': 'true'}
    })
        .then(response => {
            if (!response.ok) throw new Error('Command execution failed');
            return response.text();
        })
        .then(html => appendChatHtml(html))
        .catch(error => console.error(error));
}

function executeCommandAtIndex(index) {
    const commands = filteredCommands(commandPickerState.input ? commandPickerState.input.value : '/');
    const command = commands[index] || commands[0];
    if (!command) return;
    commandPickerState.activeIndex = Math.max(0, commands.indexOf(command));
    renderCommandPickerList();
    executeCommand(command);
}

export function openCommandPicker(textarea, query) {
    const root = getCommandModalRoot();
    if (!root) return;
    const value = query || textarea.value || '/';
    commandPickerState.open = true;
    commandPickerState.textarea = textarea;
    commandPickerState.query = value;
    commandPickerState.activeIndex = 0;
    textarea.value = value;
    resizeChatTextarea(textarea);
    root.innerHTML = '' +
        '<div id="command-modal" class="command-modal">' +
        '<div class="command-modal-card" role="dialog" aria-modal="true" aria-labelledby="command-modal-title">' +
        '<div class="command-modal-header">' +
        '<h4 id="command-modal-title">Commands</h4>' +
        '<button type="button" class="btn-close" aria-label="Close" data-command-modal-close="1"></button>' +
        '</div>' +
        '<div class="command-modal-body">' +
        '<input class="command-modal-input" type="text" value="' + escapeHtml(value) + '" autocomplete="off" spellcheck="false" aria-label="Command filter">' +
        '<ul class="command-modal-list"></ul>' +
        '</div>' +
        '</div>' +
        '</div>';
    commandPickerState.modal = root.querySelector('#command-modal');
    commandPickerState.card = root.querySelector('.command-modal-card');
    commandPickerState.input = root.querySelector('.command-modal-input');
    commandPickerState.list = root.querySelector('.command-modal-list');
    if (!commandPickerState.input || !commandPickerState.list || !commandPickerState.modal || !commandPickerState.card) return;
    positionCommandPicker();
    scheduleChatDraftSave(value);
    commandPickerState.input.value = value;
    commandPickerState.input.focus({preventScroll: true});
    commandPickerState.input.setSelectionRange(value.length, value.length);

    const closeHandler = event => {
        if (event.target && event.target.dataset && event.target.dataset.commandModalClose === '1') {
            closeCommandPicker();
        }
    };

    const reposition = () => {
        if (!commandPickerState.open) return;
        requestCommandPickerPosition();
    };

    root.addEventListener('click', closeHandler, {once: true});
    window.addEventListener('resize', reposition);
    window.addEventListener('scroll', reposition, true);
    commandPickerState.visualViewport = window.visualViewport;
    if (commandPickerState.visualViewport) {
        commandPickerState.visualViewport.addEventListener('resize', reposition);
        commandPickerState.visualViewport.addEventListener('scroll', reposition);
    }
    commandPickerState.repositionHandler = reposition;
    commandPickerState.input.addEventListener('input', renderCommandPickerList);
    commandPickerState.input.addEventListener('keydown', event => {
        if (event.isComposing) return;
        if (event.key === 'Escape') {
            event.preventDefault();
            closeCommandPicker();
            return;
        }
        if (event.key === 'ArrowDown') {
            event.preventDefault();
            setCommandPickerActiveIndex(commandPickerState.activeIndex + 1);
            return;
        }
        if (event.key === 'ArrowUp') {
            event.preventDefault();
            setCommandPickerActiveIndex(commandPickerState.activeIndex - 1);
            return;
        }
        if (event.key === 'Enter') {
            event.preventDefault();
            executeCommandAtIndex(commandPickerState.activeIndex);
        }
    });

    commandPickerState.list.addEventListener('click', event => {
        const item = event.target && event.target.closest ? event.target.closest('.command-modal-item') : null;
        if (!item) return;
        const index = Array.prototype.indexOf.call(commandPickerState.list.children, item);
        if (index < 0) return;
        executeCommandAtIndex(index);
    });

    Array.from(root.querySelectorAll('[data-command-modal-close="1"]')).forEach(el => {
        el.addEventListener('click', closeCommandPicker, {once: true});
    });

    fetchCommandCatalog()
        .then(catalog => {
            commandPickerState.catalog = Array.isArray(catalog) ? catalog : [];
            renderCommandPickerList();
        })
        .catch(error => console.error(error));
}
