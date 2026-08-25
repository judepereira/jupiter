        // Keep auto-scroll state in this closure so we can detect when the
        // message count increases and only then scroll the history container.
        // Initialized to -1 so the very first render will trigger a single
        // scroll-to-bottom and then adopt the observed count.
        let lastMessageCount = -1;
        let chatAutoScrollBound = false;
        // Track whether the user was near the bottom before an upcoming swap
        // so we can keep them pinned when pending->final replacements preserve
        // message count but change heights. Cleared after a swap settles.
        let wasNearBottomBeforeSwap = false;
        let primaryChatScrollState = null;
        let primaryChatScrollRestorePending = false;
        let subagentScrollRestoreBound = false;
        let sessionChangeScrollRestoreBound = false;
        let chatDraftFlushBound = false;
        // Ensure we add the textarea clear listener only once across re-inits
        let htmxAfterOnLoadBound = false;
        const chatDraftAutosaveState = {
            sessionId: '',
            pendingValue: '',
            lastSavedValue: '',
            inFlightValue: null,
            timerId: null,
            clearEpoch: 0
        };

        function getHtmxRequestPath(evt) {
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

        function syncFaviconWithRail() {
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

        function getChatComposerForm() {
            return document.getElementById('chat-send-form');
        }

        function getChatTextarea() {
            return document.getElementById('chat-input');
        }

        function getActiveChatSessionId() {
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

        function isDraftSaveRequestPath(path) {
            const value = String(path || '');
            return /\/ui\/sessions\/[^/?#]+\/draft(?:[/?#]|$)/.test(value);
        }

        function shouldFlushDraftBeforeRequest(path) {
            const value = String(path || '');
            if (!value) return false;
            if (value.includes('/ui/chat/send')) return false;
            if (isDraftSaveRequestPath(value)) return false;
            return /\/ui\/(chat\/primary|chat\/subagent\/|projects|workspaces|sessions)\b/.test(value);
        }

        function getDraftSaveUrl(sessionId) {
            const value = String(sessionId || getActiveChatSessionId() || '').trim();
            return value ? '/ui/sessions/' + encodeURIComponent(value) + '/draft' : '';
        }

        function clearChatDraftAutosaveState(nextValue) {
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

        function invalidateChatDraftAutosaveState() {
            chatDraftAutosaveState.clearEpoch += 1;
            chatDraftAutosaveState.inFlightValue = null;
            if (chatDraftAutosaveState.timerId != null) {
                clearTimeout(chatDraftAutosaveState.timerId);
                chatDraftAutosaveState.timerId = null;
            }
        }

        function syncChatDraftAutosaveStateFromTextarea(textarea, isFreshComposer) {
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

        function sendChatDraftSave(value, options) {
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

        function drainChatDraftSave(options) {
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

        function scheduleChatDraftSave(value) {
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

        function flushChatDraftSave(options) {
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

        function scrollChatToBottom(after) {
            try {
                const history = document.getElementById('chat-history');
                const list = document.getElementById('chat-messages-list');
                if (!history || !list) return;
                // Use rAF to ensure layout is settled before manipulating scroll
                requestAnimationFrame(() => {
                    // Defensive: only set when it actually would move
                    const target = history.scrollHeight - history.clientHeight;
                    if (Number.isFinite(target)) history.scrollTop = target;
                    if (typeof after === 'function') after();
                });
            } catch (_) { /* defensive */
            }
        }

        function checkAndMaybeScroll() {
            try {
                const list = document.getElementById('chat-messages-list');
                if (!list) {
                    // If list is absent, reset sentinel so future renders can
                    // trigger the initial scroll.
                    lastMessageCount = -1;
                    return;
                }
                const count = list.children ? list.children.length : 0;
                // On first observed render, always scroll once.
                if (lastMessageCount === -1) {
                    lastMessageCount = count;
                    scrollChatToBottom();
                    return;
                }
                if (count > lastMessageCount) {
                    lastMessageCount = count;
                    scrollChatToBottom();
                } else {
                    // Update tracked count even when messages are removed or
                    // unchanged so future increases are measured correctly.
                    lastMessageCount = count;
                }
            } catch (_) { /* defensive */
            }
        }

        function bindAutoScrollListeners() {
            if (chatAutoScrollBound) return;
            chatAutoScrollBound = true;
            // Integrate with HTMX lifecycle. Use a small async window so the
            // swapped DOM is attached before we measure. We intentionally only
            // trigger scroll when the message count increases (see check fn).
            // Only react to HTMX swaps that actually touch the chat fragment.
            // This avoids scheduling scroll work for unrelated swaps (eg: sidebars,
            // lists) which could otherwise cause an initial or unexpected
            // scroll-to-bottom.

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

            function focusChatInput() {
                try {
                    const textarea = document.getElementById('chat-input');
                    if (!textarea) return;
                    try {
                        textarea.focus({preventScroll: true});
                    } catch (_) {
                        textarea.focus();
                    }
                } catch (_) {
                }
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

            function bindChatDraftFlushListeners() {
                if (chatDraftFlushBound) return;
                chatDraftFlushBound = true;

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

            // Before-swap listener records whether the user was near bottom.
            function htmxBeforeSwapListener(evt) {
                try {
                    const trg = (evt && evt.detail && evt.detail.target) || evt.target;
                    if (!trg) return;
                    if (trg.id === 'chat-history' || trg.id === 'chat-messages-list' ||
                        (trg.closest && (trg.closest('#chat-history') || trg.closest('#chat-messages-list') || trg.closest('#chat-send-form') || trg.closest('#chat-input')))) {
                        wasNearBottomBeforeSwap = isHistoryNearBottom();
                    }
                } catch (_) { /* defensive */
                }
            }

            function htmxChatListener(evt) {
                try {
                    // Prefer HTMX-provided detail.target but fall back to the
                    // event target — this improves compatibility with different
                    // dispatching paths while still avoiding global reactions.
                    const trg = (evt && evt.detail && evt.detail.target) || evt.target;
                    if (!trg) return;

                    // If the swap directly targeted the chat history/list or is
                    // contained within it, run the check.
                    if (trg.id === 'chat-history' || trg.id === 'chat-messages-list' ||
                        (trg.closest && (trg.closest('#chat-history') || trg.closest('#chat-messages-list') || trg.closest('#chat-send-form') || trg.closest('#chat-input')))) {
                        // Run count-based auto-scroll logic as before
                        Promise.resolve().then(checkAndMaybeScroll);
                        // If the user was near-bottom before the swap, ensure
                        // we re-pin them to the bottom after the swap as well
                        // (covers pending->final replacements that keep message
                        // count unchanged but change element heights).
                        if (wasNearBottomBeforeSwap) {
                            Promise.resolve().then(() => {
                                // Double-guard: only scroll if still near-bottom or
                                // the flag was set; this avoids forcing scroll when
                                // the user had scrolled up after the beforeSwap.
                                scrollChatToBottom();
                                wasNearBottomBeforeSwap = false;
                            });
                        }
                    }
                } catch (_) { /* defensive */
                }
            }

            // Listen before swap to capture scroll position, then react
            // after swap/settle to restore if needed.
            document.body.addEventListener('htmx:beforeSwap', htmxBeforeSwapListener, true);
            document.body.addEventListener('htmx:afterSwap', htmxChatListener, true);
            document.body.addEventListener('htmx:afterSettle', htmxChatListener, true);
            bindSubagentScrollListeners();
            bindSessionChangeScrollListeners();
        }

        function getChatSelectOption(select) {
            if (!select) return null;
            return select.options ? select.options[select.selectedIndex] : null;
        }

        function syncChatDefaults(form) {
            const agentSelect = form && form.querySelector('#chat-agent-select');
            const modelSelect = form && form.querySelector('#chat-model-select');
            const thinkingSelect = form && form.querySelector('#chat-thinking-select');
            if (!agentSelect || !modelSelect || !thinkingSelect) return;

            const agentOption = getChatSelectOption(agentSelect);
            if (!agentOption || !agentOption.dataset) return;

            modelSelect.value = agentOption.dataset.defaultModel;
            thinkingSelect.value = agentOption.dataset.defaultThinking;
        }

        function bindChatControlListeners(form) {
            if (!form || form.dataset.chatControlsBound === '1') return;
            form.dataset.chatControlsBound = '1';

            const agentSelect = form.querySelector('#chat-agent-select');
            if (!agentSelect) return;

            agentSelect.addEventListener('change', () => syncChatDefaults(form));
        }

        function bindChatSubmitStopListener(form) {
            if (!form || form.dataset.chatStopBound === '1') return;
            form.dataset.chatStopBound = '1';
            form.addEventListener('submit', event => {
                if (!activePrimaryPendingAssistantRow()) return;
                event.preventDefault();
                event.stopPropagation();
                requestStopActiveChat();
            }, true);
        }

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
            positionFrame: null,
            fetchPromise: null
        };

        function getCommandModalRoot() {
            return document.getElementById('modal-root');
        }

        function closeCommandPicker() {
            const root = getCommandModalRoot();
            if (root) root.innerHTML = '';
            commandPickerState.open = false;
            commandPickerState.textarea = null;
            if (commandPickerState.repositionHandler) {
                window.removeEventListener('resize', commandPickerState.repositionHandler);
                window.removeEventListener('scroll', commandPickerState.repositionHandler, true);
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

        function openCommandPicker(textarea, query) {
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

            root.querySelectorAll('[data-command-modal-close="1"]').forEach(el => {
                el.addEventListener('click', closeCommandPicker, {once: true});
            });

            fetchCommandCatalog()
                .then(catalog => {
                    commandPickerState.catalog = Array.isArray(catalog) ? catalog : [];
                    renderCommandPickerList();
                })
                .catch(error => console.error(error));
        }

        const chatMobileViewportQuery = window.matchMedia('(max-width: 600px)');

        function isMobileChatViewport() {
            return chatMobileViewportQuery.matches;
        }

        function insertChatTextareaNewline(textarea) {
            if (!textarea) return;
            const start = typeof textarea.selectionStart === 'number' ? textarea.selectionStart : textarea.value.length;
            const end = typeof textarea.selectionEnd === 'number' ? textarea.selectionEnd : textarea.value.length;
            if (typeof textarea.setRangeText === 'function') {
                textarea.setRangeText('\n', start, end, 'end');
            } else {
                const value = textarea.value || '';
                textarea.value = value.slice(0, start) + '\n' + value.slice(end);
                const cursor = start + 1;
                if (typeof textarea.setSelectionRange === 'function') {
                    textarea.setSelectionRange(cursor, cursor);
                }
            }
            textarea.dispatchEvent(new Event('input', {bubbles: true}));
        }

        function resizeChatTextarea(textarea) {
            if (!textarea) return;
            textarea.style.height = 'auto';
            const sh = textarea.scrollHeight;
            textarea.style.height = sh + 'px';

            const cs = getComputedStyle(textarea);
            const maxH = cs.maxHeight;
            if (maxH && maxH !== 'none') {
                const maxVal = parseFloat(maxH);
                if (!isNaN(maxVal) && sh > maxVal) {
                    textarea.style.overflowY = 'auto';
                } else {
                    textarea.style.overflowY = 'hidden';
                }
            } else {
                textarea.style.overflowY = '';
            }
        }

        function initChatComposer() {
            try {
                const form = document.getElementById('chat-send-form');
                const textarea = document.getElementById('chat-input');
                if (!form || !textarea) return;

                const isFreshComposer = textarea.dataset.chatBound !== '1';
                syncChatDraftAutosaveStateFromTextarea(textarea, isFreshComposer);

                // Avoid double-binding when initializer is rerun for HTMX swaps.
                if (isFreshComposer) {
                    textarea.dataset.chatBound = '1';
                    textarea.dataset.chatSlashRestoredValue = textarea.value && textarea.value.startsWith('/') ? textarea.value : '';

                    function onKeyDown(e) {
                        if (commandPickerState.open) {
                            return;
                        }
                        const isEnter = e.key === 'Enter' || e.keyCode === 13;
                        if (!isEnter) return;
                        if (e.isComposing) return;

                        if (e.altKey || isMobileChatViewport()) {
                            e.preventDefault();
                            insertChatTextareaNewline(textarea);
                            return;
                        }

                        e.preventDefault();
                        if (activePrimaryPendingAssistantRow()) {
                            return;
                        }
                        if (typeof form.requestSubmit === 'function') {
                            form.requestSubmit();
                        } else {
                            form.submit();
                        }
                    }

                    textarea.addEventListener('input', () => {
                        resizeChatTextarea(textarea);
                        scheduleChatDraftSave(textarea.value);
                    });
                    textarea.addEventListener('keydown', onKeyDown);
                    textarea.addEventListener('beforeinput', event => {
                        if (commandPickerState.open) return;
                        if (textarea.selectionStart !== 0 || textarea.selectionEnd !== 0 || textarea.value) return;
                        if (event.inputType !== 'insertText' || event.data !== '/') return;
                        event.preventDefault();
                        openCommandPicker(textarea, '/');
                    });
                    textarea.addEventListener('input', () => {
                        if (commandPickerState.open) return;
                        if (!textarea.value.startsWith('/')) {
                            textarea.dataset.chatSlashRestoredValue = '';
                            return;
                        }
                        if (textarea.dataset.chatSlashRestoredValue === textarea.value) return;
                        openCommandPicker(textarea, textarea.value);
                    });
                    if (!htmxAfterOnLoadBound) {
                        htmxAfterOnLoadBound = true;
                        document.body.addEventListener('htmx:afterOnLoad', function (evt) {
                            try {
                                const detail = evt && evt.detail;
                                if (!detail || !detail.xhr) return;
                                const path = (detail.path) || (detail.xhr && detail.xhr.responseURL) || '';
                                if (!path) return;
                                if (!path.includes('/ui/chat/send')) return;

                                const textarea = document.getElementById('chat-input');
                                if (!textarea) return;
                                textarea.value = '';
                                textarea.dataset.chatSlashRestoredValue = '';
                                resizeChatTextarea(textarea);
                                clearChatDraftAutosaveState('');
                            } catch (_) {
                            }
                        }, true);
                    }
                }

                requestAnimationFrame(() => resizeChatTextarea(textarea));
                bindChatControlListeners(form);
                bindChatSubmitStopListener(form);
                bindAutoScrollListeners();
                bindChatDraftFlushListeners();
                checkAndMaybeScroll();
                updateChatSendButtonState();
            } catch (_) {
            }
        }

        // Run once on load to bind any existing chat fragment
        initChatComposer();

        // Helper functions: render chat message text as sanitized markdown while
        // preserving a raw-source copy on the element to support streaming updates.
        // Inserted before streaming binding so stream code can use them.
        function getRawChatMarkdown(el) {
            try {
                if (!el) return '';
                return (el.dataset && el.dataset.rawMarkdown != null && el.dataset.rawMarkdown !== '') ? el.dataset.rawMarkdown : (el.textContent || '');
            } catch (_) {
                return '';
            }
        }

        function renderChatMarkdown(el, rawText) {
            try {
                if (!el) return;
                // store raw source for future diffs/appends
                if (!el.dataset) el.dataset = {};
                el.dataset.rawMarkdown = rawText != null ? String(rawText) : '';

                // If marked + DOMPurify exist, parse then sanitize
                if (window.marked && window.DOMPurify) {
                    try {
                        var html = null;
                        if (typeof window.marked.parse === 'function') {
                            html = window.marked.parse(el.dataset.rawMarkdown, {breaks: true});
                        } else if (typeof window.marked === 'function') {
                            // older marked may be callable
                            html = window.marked(el.dataset.rawMarkdown, {breaks: true});
                        } else if (window.marked && typeof window.marked.parse === 'undefined') {
                            // guarded fallback
                            html = String(el.dataset.rawMarkdown);
                        }
                        // sanitize; DOMPurify.sanitize is expected
                        if (html != null && window.DOMPurify && typeof window.DOMPurify.sanitize === 'function') {
                            el.innerHTML = window.DOMPurify.sanitize(html);
                            try {
                                if (el.classList) el.classList.add('markdown-rendered');
                                if (el.dataset) el.dataset.markdownRendered = 'true';
                            } catch (_) {
                            }
                            return;
                        }
                    } catch (_) { /* fall through to safe text fallback */
                    }
                }

                // Fallback: set textContent to raw markdown so it remains escaped
                try {
                    // remove any rendered marker — this is plain/escaped text
                    if (el.classList) el.classList.remove('markdown-rendered');
                    if (el.dataset) delete el.dataset.markdownRendered;
                } catch (_) {
                }
                el.textContent = el.dataset.rawMarkdown;
            } catch (_) { /* defensive */
            }
        }

        function renderAllChatMarkdown(root) {
            try {
                const base = root || document;
                const msgs = base.querySelectorAll && base.querySelectorAll('.chat-message-text');
                if (!msgs) return;
                msgs.forEach(el => {
                    try {
                        const raw = getRawChatMarkdown(el);
                        renderChatMarkdown(el, raw);
                    } catch (_) {
                    }
                });
            } catch (_) {
            }
        }

        function formatChatDuration(startTs, completedTs) {
            const start = Number(startTs);
            const end = Number(completedTs);
            if (!Number.isFinite(start) || !Number.isFinite(end)) return '';

            let totalSeconds = Math.max(0, Math.floor((end - start) / 1000));
            const hours = Math.floor(totalSeconds / 3600);
            totalSeconds -= hours * 3600;
            const minutes = Math.floor(totalSeconds / 60);
            const seconds = totalSeconds - (minutes * 60);

            if (hours > 0) {
                return minutes > 0 ? hours + 'h ' + minutes + 'm' : hours + 'h';
            }
            if (minutes > 0) {
                return seconds > 0 ? minutes + 'm ' + seconds + 's' : minutes + 'm';
            }
            return seconds + 's';
        }

        const chatCompletionTimeFormatter = new Intl.DateTimeFormat(undefined, {timeStyle: 'short'});
        const chatCompletionDateFormatter = new Intl.DateTimeFormat(undefined, {weekday: 'long', day: 'numeric', month: 'long'});

        function formatChatCompletedTs(completedTs) {
            const completed = new Date(Number(completedTs));
            if (Number.isNaN(completed.getTime())) return '';

            const now = new Date();
            const sameDay = completed.getFullYear() === now.getFullYear() && completed.getMonth() === now.getMonth() && completed.getDate() === now.getDate();
            if (sameDay) {
                return chatCompletionTimeFormatter.format(completed);
            }

            const parts = chatCompletionDateFormatter.formatToParts(completed);
            const weekday = parts.find(part => part.type === 'weekday');
            const day = parts.find(part => part.type === 'day');
            const month = parts.find(part => part.type === 'month');
            const dateText = [weekday && weekday.value, day && day.value, month && month.value].filter(Boolean).join(' ');
            return dateText + ', ' + chatCompletionTimeFormatter.format(completed);
        }

        function formatChatSubtitle(subtitle) {
            try {
                if (!subtitle || !subtitle.dataset) return;
                const metadataParts = [
                    subtitle.dataset.agentLabel,
                    subtitle.dataset.modelLabel || subtitle.dataset.modelId,
                    subtitle.dataset.thinkingLevel
                ].filter(part => part != null && String(part).trim());
                const metadataText = metadataParts.join(' · ');
                const duration = formatChatDuration(subtitle.dataset.startTs, subtitle.dataset.completedTs);
                const completedTs = formatChatCompletedTs(subtitle.dataset.completedTs);
                const completionText = duration && completedTs ? duration + ' · ' + completedTs : (duration || completedTs);
                const textNode = subtitle.querySelector('.chat-message-subtitle-text') || subtitle;
                textNode.textContent = metadataText && completionText ? metadataText + ' · ' + completionText : (metadataText || completionText);
            } catch (_) {
            }
        }

        function updateChatRowCompletion(row, completedTs) {
            try {
                if (!row || !completedTs) return;
                row.dataset.completedTs = String(completedTs);
                ensureChatMessageSubtitle(row, completedTs);
            } catch (_) {
            }
        }

        function formatAllChatSubtitles(root) {
            try {
                const base = root || document;
                const subtitles = base.querySelectorAll && base.querySelectorAll('.chat-message-subtitle');
                if (!subtitles) return;
                subtitles.forEach(formatChatSubtitle);
            } catch (_) {
            }
        }

        function processHtmxElement(element) {
            try {
                if (!element || (element.dataset && element.dataset.htmxProcessed === 'true')) return;
                if (window.htmx && typeof window.htmx.process === 'function') {
                    window.htmx.process(element);
                }
                element.dataset.htmxProcessed = 'true';
            } catch (_) {
            }
        }

        function ensureChatMessageForkButton(row, subtitle) {
            try {
                if (!row || !subtitle || !row.dataset || row.dataset.role !== 'assistant') return;
                if (getCurrentOpenSubagentSessionId()) return;

                const assistantPublicId = row.dataset.id != null ? String(row.dataset.id).trim() : '';
                if (!assistantPublicId) return;

                let button = subtitle.querySelector('.chat-message-fork-button');
                if (!button) {
                    button = document.createElement('button');
                    button.type = 'button';
                    button.className = 'chat-message-fork-button btn btn-link';
                    button.textContent = 'Fork';
                    subtitle.appendChild(button);
                }
                button.setAttribute('hx-post', '/ui/chat/fork/' + encodeURIComponent(assistantPublicId));
                button.setAttribute('hx-target', '#shell');
                button.setAttribute('hx-swap', 'none');
                processHtmxElement(button);
            } catch (_) {
            }
        }

        function ensureChatMessageSubtitle(row, completedTs) {
            try {
                if (!row || !row.dataset || row.dataset.role !== 'assistant') return;
                const completed = completedTs != null ? String(completedTs) : '';
                if (!completed) return;

                row.dataset.completedTs = completed;
                let subtitle = row.querySelector('.chat-message-subtitle');
                if (!subtitle) {
                    subtitle = document.createElement('div');
                    subtitle.className = 'chat-message-subtitle';
                    const text = document.createElement('span');
                    text.className = 'chat-message-subtitle-text';
                    subtitle.appendChild(text);
                    const before = row.querySelector('.tool-calls');
                    row.insertBefore(subtitle, before);
                }
                subtitle.dataset.startTs = subtitle.dataset.startTs || row.dataset.startTs || '';
                subtitle.dataset.completedTs = completed;
                subtitle.dataset.agentLabel = subtitle.dataset.agentLabel || row.dataset.agentLabel || '';
                subtitle.dataset.agentId = subtitle.dataset.agentId || row.dataset.agentId || '';
                subtitle.dataset.modelId = subtitle.dataset.modelId || row.dataset.modelId || '';
                subtitle.dataset.modelLabel = subtitle.dataset.modelLabel || row.dataset.modelLabel || '';
                subtitle.dataset.thinkingLevel = subtitle.dataset.thinkingLevel || row.dataset.thinkingLevel || '';
                ensureChatMessageForkButton(row, subtitle);
                formatChatSubtitle(subtitle);
            } catch (_) {
            }
        }

        // Initial render of any server-rendered messages into markdown and subtitles.
        try {
            renderAllChatMarkdown();
            formatAllChatSubtitles();
        } catch (_) {
        }

        function getCurrentOpenSubagentSessionId() {
            try {
                const container = document.getElementById('chat-container');
                const sessionId = container && container.dataset && container.dataset.subagentSessionId != null ? String(container.dataset.subagentSessionId).trim() : '';
                return sessionId || '';
            } catch (_) {
                return '';
            }
        }

        function getOpenSubagentPendingRow(childSessionId) {
            try {
                if (!childSessionId) return null;
                if (getCurrentOpenSubagentSessionId() !== String(childSessionId)) return null;
                const list = document.getElementById('chat-messages-list');
                if (!list) return null;
                return list.querySelector('li[data-pending="true"]');
            } catch (_) {
                return null;
            }
        }

        const activePendingStreams = new Map();
        let stopRequestInFlight = false;

        function activePrimaryPendingAssistantRow() {
            try {
                if (getCurrentOpenSubagentSessionId()) return null;
                const list = document.getElementById('chat-messages-list');
                if (!list) return null;
                return list.querySelector('li[data-role="assistant"][data-pending="true"][data-stream-url]');
            } catch (_) {
                return null;
            }
        }

        function updateChatSendButtonState() {
            try {
                const form = document.getElementById('chat-send-form');
                const button = document.getElementById('chat-send-btn');
                if (!form || !button) return;
                const activeRow = activePrimaryPendingAssistantRow();
                const running = Boolean(activeRow);
                form.dataset.chatRunning = running ? 'true' : 'false';
                button.classList.toggle('btn-outline-danger', running);
                button.classList.toggle('btn-outline-light', stopRequestInFlight);
                if (stopRequestInFlight) {
                    button.textContent = 'Stopping...';
                    button.setAttribute('aria-label', 'Stopping current response');
                    button.setAttribute('aria-busy', 'true');
                } else if (running) {
                    button.textContent = 'Stop';
                    button.setAttribute('aria-label', 'Stop current response');
                    button.removeAttribute('aria-busy');
                } else {
                    button.textContent = 'Send';
                    button.setAttribute('aria-label', 'Send message');
                    button.removeAttribute('aria-busy');
                }
            } catch (_) {
            }
        }

        function replaceChatContainerFromHtml(html) {
            try {
                if (!html) return;
                const template = document.createElement('template');
                template.innerHTML = html.trim();
                const incoming = template.content.querySelector('#chat-container');
                const current = document.getElementById('chat-container');
                if (incoming && current) {
                    current.outerHTML = incoming.outerHTML;
                    Promise.resolve().then(() => {
                        initChatComposer();
                        bindPendingStreams();
                        renderAllChatMarkdown();
                        formatAllChatSubtitles();
                        updateChatSendButtonState();
                    });
                }
            } catch (_) {
            }
        }

        function requestStopActiveChat() {
            try {
                if (stopRequestInFlight) return;
                const row = activePrimaryPendingAssistantRow();
                if (!row || !row.dataset || !row.dataset.id) return;
                stopRequestInFlight = true;
                updateChatSendButtonState();
                const body = new URLSearchParams();
                body.set('assistantId', row.dataset.id);
                fetch('/ui/chat/stop', {
                    method: 'POST',
                    headers: {'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8', 'HX-Request': 'true'},
                    body: body.toString()
                }).then(response => {
                    if (!response.ok) throw new Error('Stop request failed');
                    return response.text();
                }).then(replaceChatContainerFromHtml)
                    .catch(error => console.error(error))
                    .finally(() => {
                        stopRequestInFlight = false;
                        updateChatSendButtonState();
                    });
            } catch (_) {
                stopRequestInFlight = false;
                updateChatSendButtonState();
            }
        }

        function clearPendingStream(assistantId, source) {
            if (activePendingStreams.get(assistantId) === source) {
                activePendingStreams.delete(assistantId);
            }
            stopRequestInFlight = false;
            updateChatSendButtonState();
        }

        function refreshWorkspaceRail() {
            const rail = document.getElementById('workspace-session-rail');
            if (!rail) return;
            fetch('/ui/workspaces/rail', {headers: {'HX-Request': 'true'}})
                .then(response => {
                    if (!response.ok) throw new Error('Workspace rail refresh failed');
                    return response.text();
                })
                .then(html => {
                    rail.outerHTML = html;
                    if (window.htmx) window.htmx.process(document.getElementById('workspace-session-rail'));
                    syncFaviconWithRail();
                })
                .catch(error => {
                    console.error(error);
                });
        }

        if (!window.__workspaceRailRefreshSource) {
            let workspaceRailRefreshTimer = null;

            const scheduleWorkspaceRailRefresh = () => {
                if (workspaceRailRefreshTimer) return;
                workspaceRailRefreshTimer = window.setTimeout(() => {
                    workspaceRailRefreshTimer = null;
                    refreshWorkspaceRail();
                }, 50);
            };

            const workspaceRailRefreshSource = new EventSource('/ui/workspaces/rail/stream');
            window.__workspaceRailRefreshSource = workspaceRailRefreshSource;
            workspaceRailRefreshSource.addEventListener('workspace-rail-refresh', scheduleWorkspaceRailRefresh);
            workspaceRailRefreshSource.addEventListener('error', error => {
                console.error('Workspace rail stream error', error);
                const hasBackendMessage = error && typeof error.data === 'string' && error.data.trim();
                if (!hasBackendMessage) {
                    window.__connectionLossMonitor && window.__connectionLossMonitor.transportFailure();
                }
            });
        }

        function getLiveChatRow(assistantId) {
            try {
                if (!assistantId) return null;
                const list = document.getElementById('chat-messages-list');
                if (!list) return null;
                const candidates = Array.from(list.querySelectorAll('li[data-id="' + assistantId + '"]'));
                const visibleCandidates = candidates.filter(row => row && row.getClientRects && row.getClientRects().length > 0);
                const visiblePendingCandidates = visibleCandidates.filter(row => row.dataset.pending === 'true');
                if (visiblePendingCandidates.length > 0) return visiblePendingCandidates[0];
                if (visibleCandidates.length > 0) return visibleCandidates[0];
                const pendingCandidates = candidates.filter(row => row.dataset.pending === 'true');
                return pendingCandidates[0] || candidates[0] || null;
            } catch (_) {
                return null;
            }
        }

        function clearPendingChatRowState(row) {
            if (!row) return;
            row.classList.remove('pending');
            row.removeAttribute('data-pending');
            row.dataset.streamBound = '0';
        }

        function toolCallKey(payload) {
            const toolName = payload && payload.toolName != null ? String(payload.toolName) : '';
            return [toolName, toolCallInputText(payload)].join('\u001f');
        }
        function toolCallInputText(payload) {
            try {
                if (!payload) return '';
                const preview = payload.inputPreview != null ? String(payload.inputPreview) : '';
                if (preview.trim()) return preview;

                const args = payload.args;
                if (args == null) return '';
                if (typeof args === 'string') return args;
                if (typeof args === 'object') {
                    for (const key of ['input', 'task', 'message', 'prompt', 'text']) {
                        if (args[key] != null && String(args[key]).trim()) return String(args[key]);
                    }
                    try {
                        return JSON.stringify(args, null, 2);
                    } catch (_) {
                        return String(args);
                    }
                }
                return String(args);
            } catch (_) {
                return '';
            }
        }

        function toolCallStatusText(state, success) {
            if (state === 'running') return 'running';
            if (state === 'done') return 'done';
            if (state === 'error') return 'error';
            return success ? 'success' : 'failure';
        }

        function toolCallOutputText(payload) {
            try {
                if (!payload) return '';
                const value = payload.outputPreview != null ? payload.outputPreview
                    : (payload.finalText != null ? payload.finalText
                        : (payload.textSummary != null ? payload.textSummary
                            : (payload.machineSummary != null ? payload.machineSummary
                                : (payload.text != null ? payload.text : (payload.errorText != null ? payload.errorText : '')))));
                if (value == null) return '';
                if (typeof value === 'string') return value;
                return JSON.stringify(value, null, 2);
            } catch (_) {
                return '';
            }
        }

        function getDirectToolCallChild(parent, className) {
            try {
                return Array.from(parent && parent.children ? parent.children : []).find(child => child && child.classList && child.classList.contains(className)) || null;
            } catch (_) {
                return null;
            }
        }

        function getDirectToolCallChildren(parent, className) {
            try {
                return Array.from(parent && parent.children ? parent.children : []).filter(child => child && child.classList && child.classList.contains(className));
            } catch (_) {
                return [];
            }
        }

        function getToolCallGroups(container) {
            return getDirectToolCallChildren(container, 'tool-call');
        }

        function getToolCallGroupCalls(group) {
            try {
                const detail = getDirectToolCallChild(group, 'tool-call-detail');
                const callsContainer = getDirectToolCallChild(detail, 'tool-call-calls');
                return callsContainer ? Array.from(callsContainer.children).filter(child => child && child.classList && child.classList.contains('tool-call-call')) : [];
            } catch (_) {
                return [];
            }
        }

        function isSpecialStandaloneToolCall(toolName) {
            const kind = toolCallGroupKind(normalizeToolCallName(toolName));
            return kind === 'task' || kind === 'image';
        }

        function getToolCallContainer(target) {
            try {
                if (!target) return null;
                if (target.classList && target.classList.contains('tool-calls')) return target;
                let container = getDirectToolCallChild(target, 'tool-calls');
                if (!container) {
                    container = document.createElement('div');
                    container.className = 'tool-calls';
                    target.appendChild(container);
                }
                return container;
            } catch (_) {
                return null;
            }
        }

        function buildToolCallBundleRefs(bundle) {
            try {
                if (!bundle) return null;

                let summary = getDirectToolCallChild(bundle, 'tool-call-summary');
                if (!summary) {
                    summary = document.createElement('summary');
                    summary.className = 'tool-call-summary';
                    bundle.appendChild(summary);
                }

                let nameSpan = summary.querySelector('.tool-call-name');
                if (!nameSpan) {
                    nameSpan = document.createElement('span');
                    nameSpan.className = 'tool-call-name';
                    summary.appendChild(nameSpan);
                }

                let detail = getDirectToolCallChild(bundle, 'tool-call-detail');
                if (!detail) {
                    detail = document.createElement('div');
                    detail.className = 'tool-call-detail';
                    bundle.appendChild(detail);
                }

                let callsContainer = getDirectToolCallChild(detail, 'tool-call-calls');
                if (!callsContainer) {
                    callsContainer = document.createElement('div');
                    callsContainer.className = 'tool-call-calls';
                    detail.appendChild(callsContainer);
                }

                return {bundle, summary, nameSpan, detail, callsContainer};
            } catch (_) {
                return null;
            }
        }

        function getToolCallBundle(container) {
            try {
                const groups = getToolCallGroups(container);
                const lastGroup = groups.length > 0 ? groups[groups.length - 1] : null;
                return lastGroup && lastGroup.classList && lastGroup.classList.contains('tool-call-bundle') ? lastGroup : null;
            } catch (_) {
                return null;
            }
        }

        function getOrCreateToolCallBundleCallsContainer(container) {
            try {
                const bundle = getToolCallBundle(container) || createToolCallBundle(container);
                if (!bundle) return null;
                return bundle.callsContainer || getDirectToolCallChild(getDirectToolCallChild(bundle.bundle || bundle, 'tool-call-detail'), 'tool-call-calls');
            } catch (_) {
                return null;
            }
        }

        function createToolCallBundle(container) {
            try {
                if (!container) return null;

                const bundle = document.createElement('details');
                bundle.className = 'tool-call tool-call-bundle';
                bundle.dataset.toolCallKind = 'bundle';
                container.appendChild(bundle);

                const refs = buildToolCallBundleRefs(bundle);
                if (!refs) return null;
                refs.nameSpan.textContent = 'Used';
                return refs;
            } catch (_) {
                return null;
            }
        }

        function refreshToolCallBundleSummary(bundleRefs) {
            try {
                if (!bundleRefs || !bundleRefs.bundle) return;

                const groups = bundleRefs.callsContainer ? Array.from(bundleRefs.callsContainer.children).filter(child => child && child.classList && child.classList.contains('tool-call')) : [];
                const counts = new Map();
                const order = [];
                let allSuccess = true;

                for (const group of groups) {
                    const calls = getToolCallGroupCalls(group);
                    for (const call of calls) {
                        const toolName = normalizeToolCallName(call.dataset ? call.dataset.toolCallToolName : '');
                        if (!toolName) continue;
                        if (!counts.has(toolName)) order.push(toolName);
                        counts.set(toolName, (counts.get(toolName) || 0) + 1);
                        if (call.dataset.toolCallSuccess !== 'true') {
                            allSuccess = false;
                        }
                    }
                    if (group.dataset.toolCallSuccess !== 'true') {
                        allSuccess = false;
                    }
                }

                const label = order.map(name => counts.get(name) > 1 ? name + ' (' + counts.get(name) + ')' : name).join(', ');
                bundleRefs.bundle.dataset.toolCallKind = 'bundle';
                bundleRefs.bundle.dataset.toolCallState = allSuccess ? 'done' : 'error';
                bundleRefs.bundle.dataset.toolCallSuccess = allSuccess ? 'true' : 'false';
                bundleRefs.bundle.dataset.toolCallSummaryLabel = label ? 'Used: ' + label : 'Used';
                if (bundleRefs.nameSpan) {
                    bundleRefs.nameSpan.textContent = bundleRefs.bundle.dataset.toolCallSummaryLabel;
                }
            } catch (_) {
            }
        }

        const EXPLORATORY_TOOL_CALLS = new Set(['list_files', 'read_file', 'search_code']);
        const IMAGE_TOOL_CALLS = new Set(['display_image']);

        function normalizeToolCallName(name) {
            return String(name == null ? '' : name).trim() || 'tool';
        }

        function toolCallGroupKind(toolName) {
            if (toolName === 'task') return 'task';
            if (EXPLORATORY_TOOL_CALLS.has(toolName)) return 'exploratory';
            if (IMAGE_TOOL_CALLS.has(toolName)) return 'image';
            return 'other';
        }

        function parseToolCallList(value) {
            if (!value) return [];
            try {
                const parsed = JSON.parse(value);
                if (!Array.isArray(parsed)) return [];
                return parsed.map(item => String(item).trim()).filter(Boolean);
            } catch (_) {
                return [String(value).trim()].filter(Boolean);
            }
        }

        function readToolCallValues(entry, datasetKey, legacyKey) {
            const values = parseToolCallList(entry && entry.dataset ? entry.dataset[datasetKey] : '');
            const legacyValue = entry && entry.dataset ? String(entry.dataset[legacyKey] || '').trim() : '';
            if (legacyValue && !values.includes(legacyValue)) values.push(legacyValue);
            return values;
        }

        function writeToolCallValues(entry, datasetKey, values) {
            entry.dataset[datasetKey] = JSON.stringify(Array.from(new Set(values.filter(Boolean))));
        }

        function rememberToolCallIdentity(entry, toolCallId, key) {
            try {
                if (!entry) return;
                if (toolCallId) {
                    const toolCallIds = readToolCallValues(entry, 'toolCallIds', 'toolCallId');
                    if (!toolCallIds.includes(toolCallId)) toolCallIds.push(toolCallId);
                    writeToolCallValues(entry, 'toolCallIds', toolCallIds);
                    entry.dataset.toolCallId = toolCallId;
                }
                if (key) {
                    const toolCallKeys = readToolCallValues(entry, 'toolCallKeys', 'toolCallKey');
                    if (!toolCallKeys.includes(key)) toolCallKeys.push(key);
                    writeToolCallValues(entry, 'toolCallKeys', toolCallKeys);
                    entry.dataset.toolCallKey = key;
                }
            } catch (_) {
            }
        }

        function toolCallGroupSummaryText(calls) {
            try {
                if (!calls || calls.length === 0) return '';

                const segments = [];
                let currentName = normalizeToolCallName(calls[0] && calls[0].dataset ? calls[0].dataset.toolCallToolName : '');
                let currentCount = 1;

                for (let i = 1; i < calls.length; i++) {
                    const nextName = normalizeToolCallName(calls[i] && calls[i].dataset ? calls[i].dataset.toolCallToolName : '');
                    if (nextName === currentName) {
                        currentCount++;
                        continue;
                    }

                    segments.push(currentCount > 1 ? currentName + ' (' + currentCount + ')' : currentName);
                    currentName = nextName;
                    currentCount = 1;
                }

                segments.push(currentCount > 1 ? currentName + ' (' + currentCount + ')' : currentName);
                return segments.join(', ');
            } catch (_) {
                return '';
            }
        }

        function canAppendToolCallEntry(entry, payload) {
            const existingName = normalizeToolCallName(entry && entry.dataset ? entry.dataset.toolCallToolName : '');
            const nextName = normalizeToolCallName(payload && payload.toolName);
            if (!existingName || !nextName) return false;

            const existingKind = entry && entry.dataset && entry.dataset.toolCallGroupKind ? entry.dataset.toolCallGroupKind : toolCallGroupKind(existingName);
            const nextKind = toolCallGroupKind(nextName);

            if (existingKind === 'task' || existingKind === 'image' || nextKind === 'task' || nextKind === 'image') return false;
            if (existingKind === 'exploratory' && nextKind === 'exploratory') return true;
            return existingName === nextName;
        }

        function updateToolCallGroupKind(details, toolName) {
            if (!details) return;
            const kind = toolCallGroupKind(normalizeToolCallName(toolName));
            details.dataset.toolCallGroupKind = kind;
        }

        function entryHasToolCallId(entry, toolCallId) {
            if (!entry || !toolCallId) return false;
            return readToolCallValues(entry, 'toolCallIds', 'toolCallId').includes(toolCallId);
        }

        function entryHasToolCallKey(entry, key) {
            if (!entry || !key) return false;
            return readToolCallValues(entry, 'toolCallKeys', 'toolCallKey').includes(key);
        }

        function findToolCallEntry(container, payload) {
            try {
                const toolCallId = payload && payload.toolCallId != null ? String(payload.toolCallId).trim() : '';
                const key = toolCallKey(payload);
                const searchScopes = [];

                const addScope = (scope) => {
                    if (scope && !searchScopes.includes(scope)) searchScopes.push(scope);
                };

                addScope(container);
                try {
                    addScope(toolCallRegistryScope(container));
                    addScope(container && container.closest ? container.closest('li[data-id]') : null);
                    addScope(document.getElementById('chat-messages-list'));
                } catch (_) {
                }

                for (const scope of searchScopes) {
                    const registry = toolCallRegistry(scope);
                    if (registry) {
                        if (toolCallId && registry.byId.has(toolCallId)) return registry.byId.get(toolCallId);
                        if (key && registry.byKey.has(key)) return registry.byKey.get(key);
                    }
                    const entries = scope && scope.querySelectorAll ? Array.from(scope.querySelectorAll('.tool-call-call')) : [];
                    for (const entry of entries) {
                        if (toolCallId && entryHasToolCallId(entry, toolCallId)) return entry;
                        if (key && entryHasToolCallKey(entry, key)) return entry;
                    }
                }

                return null;
            } catch (_) {
                return null;
            }
        }

        function hasVisibleImageFigure(entry) {
            return Boolean(entry && entry.querySelector && entry.querySelector('.tool-call-image-preview'));
        }

        function clearToolCallImages(entry) {
            try {
                if (!entry || !entry.querySelectorAll) return;
                Array.from(entry.querySelectorAll('.tool-call-image-preview')).forEach(node => node.remove());
            } catch (_) {
            }
        }

        function toolCallRegistryScope(target) {
            try {
                return target && target.closest ? target.closest('li[data-id]') : null;
            } catch (_) {
                return null;
            }
        }

        function toolCallRegistry(scope) {
            if (!scope) return null;
            if (!scope.__toolCallRegistry) {
                try {
                    Object.defineProperty(scope, '__toolCallRegistry', {
                        value: {byId: new Map(), byKey: new Map()},
                        configurable: true,
                        enumerable: false,
                        writable: false
                    });
                } catch (_) {
                    scope.__toolCallRegistry = {byId: new Map(), byKey: new Map()};
                }
            }
            return scope.__toolCallRegistry;
        }

        function registerToolCallEntry(scope, entry, toolCallId, key) {
            try {
                const registry = toolCallRegistry(scope);
                if (!registry || !entry) return;
                if (toolCallId) registry.byId.set(toolCallId, entry);
                if (key) registry.byKey.set(key, entry);
            } catch (_) {
            }
        }

        function buildToolCallGroupRefs(group) {
            try {
                if (!group) return null;

                let summary = getDirectToolCallChild(group, 'tool-call-summary');
                if (!summary) {
                    summary = document.createElement('summary');
                    summary.className = 'tool-call-summary';
                    group.appendChild(summary);
                }

                let nameSpan = summary.querySelector('.tool-call-name');
                if (!nameSpan) {
                    nameSpan = document.createElement('span');
                    nameSpan.className = 'tool-call-name';
                    summary.appendChild(nameSpan);
                }

                let statusSpan = summary.querySelector('.tool-call-status');
                if (!statusSpan) {
                    statusSpan = document.createElement('span');
                    statusSpan.className = 'tool-call-status';
                    summary.appendChild(statusSpan);
                }
                let detail = getDirectToolCallChild(group, 'tool-call-detail');
                if (!detail) {
                    detail = document.createElement('div');
                    detail.className = 'tool-call-detail';
                    group.appendChild(detail);
                }

                let callsContainer = getDirectToolCallChild(detail, 'tool-call-calls');
                if (!callsContainer) {
                    callsContainer = document.createElement('div');
                    callsContainer.className = 'tool-call-calls';
                    detail.appendChild(callsContainer);
                }

                return {group, summary, nameSpan, statusSpan, detail, callsContainer};
            } catch (_) {
                return null;
            }
        }

        function buildToolCallCallRefs(call, processHtmxElementFn) {
            try {
                if (!call) return null;

                let subagent = getDirectToolCallChild(call, 'tool-call-subagent') || null;
                let button = subagent ? subagent.querySelector('.tool-call-subagent-button') : null;

                let inputSection = Array.from(call.children || []).find(child => child && child.classList && child.classList.contains('tool-call-section') && child.dataset.toolCallField === 'input') || null;
                if (!inputSection) {
                    inputSection = document.createElement('section');
                    inputSection.className = 'tool-call-section';
                    inputSection.dataset.toolCallField = 'input';
                    call.appendChild(inputSection);
                }

                let inputLabel = inputSection.querySelector('.tool-call-label');
                if (!inputLabel) {
                    inputLabel = document.createElement('div');
                    inputLabel.className = 'tool-call-label';
                    inputSection.appendChild(inputLabel);
                }
                inputLabel.textContent = 'Input';

                let inputPre = inputSection.querySelector('.tool-call-pre');
                if (!inputPre) {
                    inputPre = document.createElement('pre');
                    inputPre.className = 'tool-call-pre';
                    inputSection.appendChild(inputPre);
                }

                let outputSection = Array.from(call.children || []).find(child => child && child.classList && child.classList.contains('tool-call-section') && child.dataset.toolCallField === 'output') || null;
                if (!outputSection) {
                    outputSection = document.createElement('section');
                    outputSection.className = 'tool-call-section';
                    outputSection.dataset.toolCallField = 'output';
                    call.appendChild(outputSection);
                }

                let outputLabel = outputSection.querySelector('.tool-call-label');
                if (!outputLabel) {
                    outputLabel = document.createElement('div');
                    outputLabel.className = 'tool-call-label';
                    outputSection.appendChild(outputLabel);
                }
                outputLabel.textContent = 'Output';

                let outputPre = outputSection.querySelector('.tool-call-pre');
                let imageFigure = outputSection.querySelector('.tool-call-image-preview') || call.querySelector('.tool-call-image-preview');
                if (!outputPre && !imageFigure) {
                    outputPre = document.createElement('pre');
                    outputPre.className = 'tool-call-pre';
                    outputSection.appendChild(outputPre);
                }

                let nestedCalls = getDirectToolCallChild(call, 'tool-calls');
                if (!nestedCalls) {
                    nestedCalls = document.createElement('div');
                    nestedCalls.className = 'tool-calls';
                    call.appendChild(nestedCalls);
                }

                if (processHtmxElementFn) processHtmxElementFn(button);

                return {details: call, detail: call, subagent, button, inputPre, outputSection, outputPre, imageFigure, nestedCalls};
            } catch (_) {
                return null;
            }
        }

        function refreshToolCallGroupSummary(groupRefs) {
            try {
                if (!groupRefs || !groupRefs.group) return;

                const group = groupRefs.group;
                const calls = groupRefs.callsContainer ? Array.from(groupRefs.callsContainer.children).filter(child => child && child.classList && child.classList.contains('tool-call-call')) : [];
                const toolName = group.dataset.toolCallToolName || (calls[0] && calls[0].dataset && calls[0].dataset.toolCallToolName) || 'tool';
                const count = calls.length;
                const running = calls.some(call => call.dataset.toolCallState === 'running');
                const success = count > 0 && calls.every(call => call.dataset.toolCallSuccess === 'true');
                const state = running ? 'running' : (success ? 'done' : 'error');
                const statusText = running ? 'running' : (success ? 'success' : 'failure');
                const label = toolCallGroupSummaryText(calls) || (count > 1 ? toolName + ' (' + count + ')' : toolName);

                group.dataset.toolCallToolName = toolName;
                group.dataset.toolCallCount = String(count);
                group.dataset.toolCallState = state;
                group.dataset.toolCallSuccess = success ? 'true' : 'false';
                group.dataset.toolCallSummaryLabel = label;

                if (groupRefs.nameSpan) {
                    groupRefs.nameSpan.textContent = label;
                }

                if (groupRefs.statusSpan) {
                    groupRefs.statusSpan.className = 'tool-call-status';
                    if (state === 'running') {
                        groupRefs.statusSpan.textContent = statusText;
                    } else {
                        if (success) groupRefs.statusSpan.classList.add('tool-call-status-success');
                        else groupRefs.statusSpan.classList.add('tool-call-status-failure');
                        groupRefs.statusSpan.textContent = statusText;
                    }
                }
            } catch (_) {
            }
        }

        function createToolCallGroup(container, toolName) {
            try {
                if (!container) return null;

                const group = document.createElement('details');
                group.className = 'tool-call';
                group.dataset.toolCallToolName = toolName;
                container.appendChild(group);

                const refs = buildToolCallGroupRefs(group);
                if (!refs) return null;

                refs.nameSpan.textContent = toolName;
                refs.statusSpan.textContent = 'running';
                return refs;
            } catch (_) {
                return null;
            }
        }

        function canCoalesceToolCallGroup(lastGroup, toolName) {
            try {
                const lastToolName = normalizeToolCallName(lastGroup && lastGroup.dataset ? lastGroup.dataset.toolCallToolName : '');
                const nextToolName = normalizeToolCallName(toolName);
                if (!lastToolName || !nextToolName) return false;

                const lastKind = toolCallGroupKind(lastToolName);
                const nextKind = toolCallGroupKind(nextToolName);
                if (lastKind === 'task' || lastKind === 'image' || nextKind === 'task' || nextKind === 'image') return false;
                if (lastKind === 'exploratory' && nextKind === 'exploratory') return true;
                return lastToolName === nextToolName;
            } catch (_) {
                return false;
            }
        }

        function refreshAnyToolCallBundle(container) {
            try {
                if (!container) return;
                const bundle = (container.closest && container.closest('details.tool-call-bundle')) || getToolCallBundle(container);
                if (!bundle) return;
                refreshToolCallBundleSummary(buildToolCallBundleRefs(bundle));
            } catch (_) {
            }
        }

        function refreshParentToolCallBundle(group) {
            try {
                if (!group || !group.closest) return;
                const bundle = group.closest('details.tool-call-bundle');
                if (!bundle) return;
                refreshToolCallBundleSummary(buildToolCallBundleRefs(bundle));
            } catch (_) {
            }
        }

        function createToolCallCall(groupRefs, payload, processHtmxElementFn) {
            try {
                if (!groupRefs || !groupRefs.callsContainer) return null;

                const call = document.createElement('div');
                call.className = 'tool-call-call';
                groupRefs.callsContainer.appendChild(call);

                const refs = buildToolCallCallRefs(call, processHtmxElementFn);
                if (!refs) return null;

                const toolCallId = payload && payload.toolCallId != null ? String(payload.toolCallId).trim() : '';
                const toolName = payload && payload.toolName != null ? String(payload.toolName).trim() : 'tool';
                const key = toolCallKey(payload);

                rememberToolCallIdentity(call, toolCallId, key);
                call.dataset.toolCallGroupKind = toolCallGroupKind(toolName);
                registerToolCallEntry(toolCallRegistryScope(groupRefs.group || call), call, toolCallId, key);
                rememberToolCallIdentity(refs.details, toolCallId, key);
                refs.details.dataset.toolCallToolName = toolName;
                refs.details.dataset.toolCallState = 'running';
                refs.details.dataset.toolCallSuccess = 'false';

                refreshToolCallGroupSummary(groupRefs);
                refreshParentToolCallBundle(groupRefs.group);
                return {group: groupRefs.group, summary: groupRefs.summary, nameSpan: groupRefs.nameSpan, statusSpan: groupRefs.statusSpan, detail: refs.detail, details: refs.details, subagent: refs.subagent, button: refs.button, inputPre: refs.inputPre, outputSection: refs.outputSection, outputPre: refs.outputPre, imageFigure: refs.imageFigure, nestedCalls: refs.nestedCalls};
            } catch (_) {
                return null;
            }
        }

        function buildToolCallEntry(entry, processHtmxElementFn) {
            try {
                if (!entry || !entry.classList || !entry.classList.contains('tool-call-call')) return null;
                const group = entry.closest('details.tool-call');
                const groupRefs = buildToolCallGroupRefs(group);
                const callRefs = buildToolCallCallRefs(entry, processHtmxElementFn);
                if (!groupRefs || !callRefs) return null;
                return {group: groupRefs.group, summary: groupRefs.summary, nameSpan: groupRefs.nameSpan, statusSpan: groupRefs.statusSpan, detail: callRefs.detail, details: callRefs.details, subagent: callRefs.subagent, button: callRefs.button, inputPre: callRefs.inputPre, outputSection: callRefs.outputSection, outputPre: callRefs.outputPre, imageFigure: callRefs.imageFigure, nestedCalls: callRefs.nestedCalls};
            } catch (_) {
                return null;
            }
        }

        function ensureToolCallEntry(target, payload, processHtmxElementFn) {
            try {
                const container = getToolCallContainer(target);
                if (!container) return null;

                const toolCallId = payload && payload.toolCallId != null ? String(payload.toolCallId).trim() : '';
                const toolName = payload && payload.toolName != null ? String(payload.toolName).trim() : 'tool';

                const existingEntry = findToolCallEntry(container, payload);
                const existing = existingEntry ? buildToolCallEntry(existingEntry, processHtmxElementFn) : null;
                if (existing) {
                    if (payload && payload.imageUrl) {
                        clearToolCallImages(existingEntry);
                    }
                    refreshToolCallGroupSummary({group: existing.group, summary: existing.summary, nameSpan: existing.nameSpan, statusSpan: existing.statusSpan, callsContainer: existing.group ? getDirectToolCallChild(getDirectToolCallChild(existing.group, 'tool-call-detail'), 'tool-call-calls') : null});
                    refreshParentToolCallBundle(existing.group || existingEntry);
                    return existing;
                }

                const bundle = getToolCallBundle(container);
                const bundleRefs = bundle ? buildToolCallBundleRefs(bundle) : null;
                const groupContainer = isSpecialStandaloneToolCall(toolName)
                    ? container
                    : (bundleRefs ? bundleRefs.callsContainer : getOrCreateToolCallBundleCallsContainer(container));
                if (!groupContainer) return null;

                const groups = getToolCallGroups(groupContainer);
                let groupRefs = null;
                if (groups.length > 0) {
                    const lastGroup = groups[groups.length - 1];
                    if (canCoalesceToolCallGroup(lastGroup, toolName) && !lastGroup.classList.contains('tool-call-bundle')) {
                        groupRefs = buildToolCallGroupRefs(lastGroup);
                    }
                }

                if (!groupRefs) {
                    groupRefs = createToolCallGroup(groupContainer, toolName);
                }

                if (!groupRefs) return null;
                const entry = createToolCallCall(groupRefs, payload, processHtmxElementFn);
                refreshAnyToolCallBundle(container);
                return entry;
            } catch (_) {
                return null;
            }
        }

        function updateToolCallEntry(entry, payload, options, processHtmxElementFn) {
            try {
                if (!entry || !entry.details) return;

                const details = entry.details;
                const group = entry.group || details.closest('details.tool-call');
                const toolName = options && options.toolName != null ? String(options.toolName) : (payload && payload.toolName != null ? String(payload.toolName) : 'tool');
                const inputText = options && Object.prototype.hasOwnProperty.call(options, 'inputText') ? String(options.inputText ?? '') : toolCallInputText(payload);
                const outputText = options && Object.prototype.hasOwnProperty.call(options, 'outputText') ? String(options.outputText ?? '') : toolCallOutputText(payload);
                const state = options && options.state != null ? String(options.state) : (payload && payload.success != null ? (payload.success ? 'done' : 'error') : 'running');
                const statusText = options && options.statusText != null ? String(options.statusText) : toolCallStatusText(state, payload && payload.success);
                const success = options && options.success != null ? Boolean(options.success) : Boolean(payload && payload.success);

                details.dataset.toolCallToolName = toolName;
                details.dataset.toolCallState = state;
                details.dataset.toolCallSuccess = success ? 'true' : 'false';
                updateToolCallGroupKind(group, toolName);
                rememberToolCallIdentity(details, payload && payload.toolCallId != null ? String(payload.toolCallId).trim() : '', toolCallKey(payload));

                entry.nameSpan.textContent = toolName;
                entry.statusSpan.className = 'tool-call-status';
                if (state === 'done' || success) entry.statusSpan.classList.add('tool-call-status-success');
                if (state === 'error' || (payload && payload.success === false)) entry.statusSpan.classList.add('tool-call-status-failure');
                entry.statusSpan.textContent = statusText;

                if (Object.prototype.hasOwnProperty.call(options || {}, 'inputText')) {
                    entry.inputPre.textContent = inputText;
                } else if (entry.inputPre && !entry.inputPre.textContent && inputText) {
                    entry.inputPre.textContent = inputText;
                }

                const imageUrl = options && options.imageUrl != null ? String(options.imageUrl) : (payload && payload.imageUrl != null ? String(payload.imageUrl) : '');
                const imageAlt = options && options.imageAlt != null ? String(options.imageAlt) : (payload && payload.imageAlt != null ? String(payload.imageAlt) : '');
                const imagePath = options && options.imagePath != null ? String(options.imagePath) : (payload && payload.imagePath != null ? String(payload.imagePath) : '');
                const imageMediaType = options && options.imageMediaType != null ? String(options.imageMediaType) : (payload && payload.imageMediaType != null ? String(payload.imageMediaType) : '');
                const isImage = Boolean(imageUrl);

                if (isImage && entry.outputPre) {
                    entry.outputPre.remove();
                    entry.outputPre = null;
                } else if (entry.outputPre) {
                    if (Object.prototype.hasOwnProperty.call(options || {}, 'outputText')) {
                        entry.outputPre.textContent = outputText;
                    } else if (outputText && !entry.outputPre.textContent) {
                        entry.outputPre.textContent = outputText;
                    }
                }
                if (!isImage && entry.imageFigure) {
                    entry.imageFigure.remove();
                    entry.imageFigure = null;
                }

                if (isImage) {
                    if (!entry.imageFigure) {
                        entry.imageFigure = document.createElement('figure');
                        entry.imageFigure.className = 'tool-call-image-preview';
                        entry.detail.appendChild(entry.imageFigure);
                    }
                    let img = entry.imageFigure.querySelector('img');
                    if (!img) {
                        img = document.createElement('img');
                        entry.imageFigure.appendChild(img);
                    }
                    img.src = imageUrl;
                    img.alt = imageAlt;
                    let caption = entry.imageFigure.querySelector('figcaption');
                    if (!caption) {
                        caption = document.createElement('figcaption');
                        entry.imageFigure.appendChild(caption);
                    }
                    let captionText = caption.querySelector('.tool-call-image-caption');
                    if (!captionText) {
                        captionText = document.createElement('span');
                        captionText.className = 'tool-call-image-caption';
                        caption.appendChild(captionText);
                    }
                    captionText.textContent = imageAlt;
                    Array.from(caption.querySelectorAll('br, small')).forEach(node => node.remove());
                    if (imagePath) {
                        const small = document.createElement('small');
                        small.textContent = imagePath;
                        caption.appendChild(document.createElement('br'));
                        caption.appendChild(small);
                    }
                    details.open = true;
                    if (entry.group) entry.group.open = true;
                    entry.imageFigure.dataset.imageMediaType = imageMediaType || '';
                } else if (entry.imageFigure) {
                    entry.imageFigure.remove();
                    entry.imageFigure = null;
                }

                if (options && options.appendOutputText != null && entry.outputPre) {
                    entry.outputPre.textContent = (entry.outputPre.textContent || '') + String(options.appendOutputText);
                }

                const subagentSessionId = options && options.subagentSessionId != null ? String(options.subagentSessionId) : (payload && payload.subagentSessionId != null ? String(payload.subagentSessionId) : '');
                if (subagentSessionId) {
                    const name = options && options.subagentAgentName != null ? String(options.subagentAgentName) : (payload && payload.subagentAgentName != null ? String(payload.subagentAgentName) : (payload && payload.name != null ? String(payload.name) : subagentSessionId));
                    if (!entry.subagent) {
                        entry.subagent = document.createElement('div');
                        entry.subagent.className = 'tool-call-subagent';
                        entry.detail.insertBefore(entry.subagent, getDirectToolCallChild(entry.detail, 'tool-call-section') || getDirectToolCallChild(entry.detail, 'tool-calls') || null);
                    }
                    if (!entry.button) {
                        entry.button = document.createElement('button');
                        entry.button.type = 'button';
                        entry.button.className = 'btn btn-outline-primary btn-sm d-inline-flex align-items-center gap-1 tool-call-subagent-button';
                        entry.subagent.appendChild(entry.button);
                    }
                    entry.subagent.dataset.childSessionId = subagentSessionId;
                    entry.button.setAttribute('hx-get', '/ui/chat/subagent/' + encodeURIComponent(subagentSessionId));
                    entry.button.setAttribute('hx-target', '#chat-container');
                    entry.button.setAttribute('hx-swap', 'outerHTML');
                    entry.button.replaceChildren(document.createTextNode('Open subagent: '), (() => {
                        const strong = document.createElement('strong');
                        strong.textContent = name;
                        return strong;
                    })());
                    if (processHtmxElementFn) processHtmxElementFn(entry.button);
                }

                if (group) {
                    refreshToolCallGroupSummary({group: group, summary: entry.summary, nameSpan: entry.nameSpan, statusSpan: entry.statusSpan, callsContainer: getDirectToolCallChild(getDirectToolCallChild(group, 'tool-call-detail'), 'tool-call-calls')});
                    refreshParentToolCallBundle(group);
                }
            } catch (_) {
            }
        }

        function appendToolCallToChatRow(target, payload, processHtmxElementFn, options) {
            try {
                const entry = ensureToolCallEntry(target, payload, processHtmxElementFn);
                if (!entry) return null;
                updateToolCallEntry(entry, payload, options || {}, processHtmxElementFn);
                return entry;
            } catch (_) {
                return null;
            }
        }

        function updateOpenSubagentTranscript(payload, kind) {
            try {
                const childSessionId = payload && payload.childSessionId != null ? String(payload.childSessionId) : '';
                const row = getOpenSubagentPendingRow(childSessionId);
                if (!row) return;

                const textSpan = row.querySelector('.chat-message-text');
                if (!textSpan) return;

                if (kind === 'delta') {
                    if (payload && payload.delta != null) {
                        const prevRaw = getRawChatMarkdown(textSpan);
                        renderChatMarkdown(textSpan, prevRaw + String(payload.delta));
                    }
                    return;
                }

                if (kind === 'started') {
                    if (payload && payload.task != null) {
                        renderChatMarkdown(textSpan, String(payload.task));
                    }
                    return;
                }

                if (kind === 'done') {
                    if (payload && payload.finalText != null) {
                        renderChatMarkdown(textSpan, String(payload.finalText));
                    } else if (payload && payload.text != null) {
                        renderChatMarkdown(textSpan, String(payload.text));
                    }
                    clearPendingChatRowState(row);
                    return;
                }

                if (kind === 'error') {
                    const errorText = payload && payload.errorText != null ? String(payload.errorText) : 'Subagent error';
                    const prevRaw = getRawChatMarkdown(textSpan);
                    renderChatMarkdown(textSpan, prevRaw + '\n[Error: ' + errorText + ']');
                    clearPendingChatRowState(row);
                    return;
                }

                if (kind === 'tool_call') {
                    appendToolCallToChatRow(row, payload);
                }
            } catch (_) {
            }
        }

        // Streaming SSE binding: open EventSource for pending assistant rows.
        // We run after HTMX swaps/settles so newly swapped pending rows can start streaming.
        function bindPendingStreams() {
            try {
                const list = document.getElementById('chat-messages-list');
                if (!list) return;
                const rows = list.querySelectorAll('li[data-pending="true"]');
                rows.forEach(row => {
                    const assistantId = row.dataset.id != null ? String(row.dataset.id) : '';
                    if (!assistantId) return;
                    if (row.dataset.streamBound === '1') return; // already bound
                    const url = row.dataset.streamUrl;
                    if (!url) return;
                    const existingSource = activePendingStreams.get(assistantId);
                    if (existingSource) {
                        try {
                            existingSource.close();
                        } catch (_) {
                        }
                    }
                    row.dataset.streamBound = '1';

                    // Buffer incoming deltas and batch DOM writes
                    const FLUSH_INTERVAL_MS = 40; // throttle cadence for visible progressive updates
                    let buffer = '';
                    let gotDelta = false;
                    let rafPending = false;
                    let flushTimer = null;
                    let lastFlushTime = 0;
                    const currentRow = () => getLiveChatRow(assistantId);

                    function currentTextSpan() {
                        const liveRow = currentRow();
                        return liveRow ? liveRow.querySelector('.chat-message-text') : null;
                    }

                    function flushBuffer() {
                        const textSpan = currentTextSpan();
                        if (!textSpan) return;
                        // If nothing to flush, clear any pending timers/state and return
                        if (buffer.length === 0) {
                            rafPending = false;
                            if (flushTimer) {
                                clearTimeout(flushTimer);
                                flushTimer = null;
                            }
                            return;
                        }
                        // Append buffered text to the raw markdown source then render
                        try {
                            const prevRaw = getRawChatMarkdown(textSpan);
                            const newRaw = prevRaw + buffer;
                            renderChatMarkdown(textSpan, newRaw);
                        } catch (_) {
                            // fallback to textContent append if something goes wrong
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

                    // Bounded flush cadence: first delta shows immediately, subsequent
                    // updates are throttled to at most ~FLUSH_INTERVAL_MS using setTimeout
                    // plus rAF for layout.
                    function scheduleFlush() {
                        // If a flush is already scheduled via timer or rAF, nothing to do
                        if (rafPending || flushTimer) return;

                        const now = Date.now();

                        // If we've never flushed before, show first content immediately
                        if (lastFlushTime === 0) {
                            // perform same-task flush so first characters appear quickly
                            flushBuffer();
                            return;
                        }

                        const elapsed = now - lastFlushTime;
                        if (elapsed >= FLUSH_INTERVAL_MS) {
                            // Enough time has passed — schedule a rAF flush
                            rafPending = true;
                            requestAnimationFrame(flushBuffer);
                            return;
                        }

                        // Otherwise schedule a timer to fire after remaining interval,
                        // then use rAF to perform the DOM write for better layout timing.
                        flushTimer = setTimeout(() => {
                            flushTimer = null;
                            if (rafPending) return;
                            rafPending = true;
                            requestAnimationFrame(flushBuffer);
                        }, FLUSH_INTERVAL_MS - elapsed);
                    }

                    // Track if user is near bottom so we only auto-scroll when appropriate
                    // Use a larger threshold for streaming so small incoming deltas don't
                    // immediately unstick the view while the user is effectively at the end.
                    const STREAM_BOTTOM_THRESHOLD_PX = 96;

                    function wasNearBottom() {
                        try {
                            const history = document.getElementById('chat-history');
                            if (!history) return false;
                            const max = history.scrollHeight - history.clientHeight;
                            const cur = history.scrollTop;
                            if (!Number.isFinite(max) || !Number.isFinite(cur)) return false;
                            return (max - cur) <= STREAM_BOTTOM_THRESHOLD_PX;
                        } catch (_) {
                            return false;
                        }
                    }

                    // Live, mutable flag indicating whether the stream should stick to bottom.
                    // Initialized from current position and updated by a stream-local scroll listener.
                    let shouldStickToBottom = wasNearBottom();
                    let streamHistoryEl = null;

                    function streamScrollListener() {
                        shouldStickToBottom = wasNearBottom();
                    }

                    const es = new EventSource(url);
                    activePendingStreams.set(assistantId, es);
                    updateChatSendButtonState();

                    // Helper to parse SSE payloads that may be JSON {text:...} or legacy raw strings
                    function parseStreamPayload(e) {
                        const raw = (e && e.data) ? e.data : '';
                        if (!raw) return {text: ''};
                        // Try parse JSON first
                        try {
                            const parsed = JSON.parse(raw);
                            if (parsed && typeof parsed === 'object') return parsed;
                        } catch (_) { /* not JSON */
                        }
                        // fallback: legacy plain string
                        return {text: raw};
                    }

                    function processHtmxElement(element) {
                        if (!element || (element.dataset && element.dataset.htmxProcessed === 'true')) return;
                        try {
                            if (window.htmx && typeof window.htmx.process === 'function') {
                                window.htmx.process(element);
                            }
                        } catch (_) {
                        }
                        try {
                            element.dataset.htmxProcessed = 'true';
                        } catch (_) {
                        }
                    }

                    es.addEventListener('delta', (e) => {
                        try {
                            const payload = parseStreamPayload(e);
                            if (payload && payload.text != null) {
                                // append exactly as provided, including spaces/newlines
                                buffer += payload.text;
                                gotDelta = true;
                                scheduleFlush();
                            }
                        } catch (_) {
                        }
                    });

                    es.addEventListener('tool_call_started', (e) => {
                        try {
                            const payload = parseStreamPayload(e) || {};
                            const liveRow = currentRow();
                            if (!liveRow) return;
                            appendToolCallToChatRow(liveRow, payload, processHtmxElement, {
                                state: 'running',
                                statusText: 'running',
                                success: false,
                                inputText: toolCallInputText(payload),
                                outputText: toolCallOutputText(payload)
                            });
                        } catch (_) {
                        }
                    });

                    es.addEventListener('tool_call_progress', (e) => {
                        try {
                            const event = parseStreamPayload(e) || {};
                            const payload = event.payload || {};
                            const liveRow = currentRow();
                            if (!liveRow) return;

                            if (event.eventName === 'subagent_started') {
                                appendToolCallToChatRow(liveRow, {
                                    toolCallId: event.toolCallId,
                                    toolName: event.toolName,
                                    inputPreview: toolCallInputText(payload) || payload.task || '',
                                    subagentSessionId: payload.childSessionId,
                                    subagentAgentName: payload.subagentAgentName
                                }, processHtmxElement, {
                                    state: 'running',
                                    statusText: 'running',
                                    success: false,
                                    outputText: payload.task != null ? String(payload.task) : '',
                                    subagentSessionId: payload.childSessionId,
                                    subagentAgentName: payload.subagentAgentName
                                });
                                updateOpenSubagentTranscript(payload, 'started');
                                return;
                            }

                            if (event.eventName === 'subagent_delta') {
                                const entry = appendToolCallToChatRow(liveRow, {
                                    toolCallId: event.toolCallId,
                                    toolName: event.toolName,
                                    inputPreview: toolCallInputText(payload) || payload.task || ''
                                }, processHtmxElement, {state: 'running', statusText: 'running', success: false});
                                if (entry && entry.outputPre && payload.delta != null) {
                                    entry.outputPre.textContent = (entry.outputPre.textContent || '') + String(payload.delta);
                                }
                                updateOpenSubagentTranscript(payload, 'delta');
                                return;
                            }

                            if (event.eventName === 'subagent_tool_call') {
                                const parentEntry = appendToolCallToChatRow(liveRow, {
                                    toolCallId: event.toolCallId,
                                    toolName: event.toolName,
                                    inputPreview: toolCallInputText(payload) || payload.task || ''
                                }, processHtmxElement, {state: 'running', statusText: 'running', success: false});
                                if (parentEntry && parentEntry.nestedCalls) {
                                    appendToolCallToChatRow(parentEntry.nestedCalls, payload, processHtmxElement, {
                                        state: payload.success ? 'done' : 'error',
                                        statusText: payload.success ? 'success' : 'failure',
                                        success: Boolean(payload.success)
                                    });
                                }
                                updateOpenSubagentTranscript(payload, 'tool_call');
                                return;
                            }

                            if (event.eventName === 'subagent_done') {
                                appendToolCallToChatRow(liveRow, {
                                    toolCallId: event.toolCallId,
                                    toolName: event.toolName,
                                    inputPreview: toolCallInputText(payload) || payload.task || '',
                                    subagentSessionId: payload.childSessionId,
                                    subagentAgentName: payload.subagentAgentName
                                }, processHtmxElement, {
                                    state: 'done',
                                    statusText: 'done',
                                    success: true,
                                    outputText: payload.finalText != null ? String(payload.finalText) : '',
                                    subagentSessionId: payload.childSessionId,
                                    subagentAgentName: payload.subagentAgentName
                                });
                                updateOpenSubagentTranscript(payload, 'done');
                                return;
                            }

                            if (event.eventName === 'subagent_error') {
                                appendToolCallToChatRow(liveRow, {
                                    toolCallId: event.toolCallId,
                                    toolName: event.toolName,
                                    inputPreview: toolCallInputText(payload) || payload.task || '',
                                    subagentSessionId: payload.childSessionId,
                                    subagentAgentName: payload.subagentAgentName
                                }, processHtmxElement, {
                                    state: 'error',
                                    statusText: 'error',
                                    success: false,
                                    outputText: payload.errorText != null ? String(payload.errorText) : '',
                                    subagentSessionId: payload.childSessionId,
                                    subagentAgentName: payload.subagentAgentName
                                });
                                updateOpenSubagentTranscript(payload, 'error');
                            }
                        } catch (_) {
                        }
                    });

                    es.addEventListener('status', (e) => {
                        try {
                            const payload = parseStreamPayload(e);
                            const st = (payload && payload.status != null) ? payload.status : (e.data || '');
                            const liveRow = currentRow();
                            if (st && liveRow) liveRow.title = st;
                        } catch (_) {
                        }
                    });

                    es.addEventListener('context_compaction', (e) => {
                        try {
                            const payload = parseStreamPayload(e) || {};
                            const id = payload && payload.id != null ? String(payload.id) : '';
                            const text = payload && payload.text != null ? String(payload.text) : '';
                            if (!id || !text) return;

                            Array.from(list.querySelectorAll('li[data-id]')).forEach(item => {
                                if (item.dataset.id === id) item.remove();
                            });

                            const compactedRow = document.createElement('li');
                            compactedRow.dataset.id = id;
                            compactedRow.dataset.system = 'true';

                            const strong = document.createElement('strong');
                            strong.textContent = 'system';

                            const textSpan = document.createElement('span');
                            textSpan.className = 'chat-message-text';

                            compactedRow.appendChild(strong);
                            compactedRow.appendChild(document.createTextNode(': '));
                            compactedRow.appendChild(textSpan);

                            const pendingRow = list.querySelector('li[data-pending="true"]');
                            if (pendingRow && pendingRow.parentNode) {
                                pendingRow.parentNode.insertBefore(compactedRow, pendingRow);
                            } else {
                                list.appendChild(compactedRow);
                            }

                            renderChatMarkdown(textSpan, text);
                            lastMessageCount = list.children.length;

                            if (shouldStickToBottom || wasNearBottom()) {
                                requestAnimationFrame(() => {
                                    try {
                                        const history = document.getElementById('chat-history');
                                        if (history) history.scrollTop = history.scrollHeight - history.clientHeight;
                                    } catch (_) {
                                    }
                                });
                            }
                        } catch (_) {
                        }
                    });

                    es.addEventListener('done', (e) => {
                        try {
                            const payload = parseStreamPayload(e);
                            // Ensure any pending buffered text is flushed first so
                            // streamed content is visible before final replacement.
                            if (flushTimer) {
                                clearTimeout(flushTimer);
                                flushTimer = null;
                            }
                            // If a rAF flush is pending, perform a synchronous flush to
                            // avoid waiting for paint so the finalization sees latest text.
                            if (rafPending) {
                                // clear the flag then flush synchronously
                                rafPending = false;
                                flushBuffer();
                            } else {
                                // ensure any buffered text is applied
                                flushBuffer();
                            }

                            // If payload contains authoritative final text, use it to
                            // correct any missed/duplicated chunks. Only replace if
                            // it actually differs to avoid unnecessary reflows.
                            const textSpan = currentTextSpan();
                            if (payload && payload.text != null && textSpan) {
                                try {
                                    const currentRaw = getRawChatMarkdown(textSpan);
                                    if (String(payload.text) !== String(currentRaw)) {
                                        renderChatMarkdown(textSpan, payload.text);
                                    }
                                    buffer = '';
                                } catch (_) {
                                    // fallback to direct assignment
                                    if (textSpan.textContent !== payload.text) {
                                        try {
                                            textSpan.textContent = payload.text;
                                        } catch (_) {
                                        }
                                    }
                                    buffer = '';
                                }
                            } else if (!gotDelta) {
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
                                flushBuffer();
                            }

                            if (payload && Array.isArray(payload.toolCalls) && payload.toolCalls.length > 0) {
                                const doneRow = currentRow();
                                if (doneRow) {
                                    payload.toolCalls.forEach(toolCall => {
                                        const toolCallId = toolCall && toolCall.toolCallId != null ? String(toolCall.toolCallId).trim() : '';
                                        if (toolCallId) {
                                            const existing = document.getElementById('chat-messages-list')?.querySelector('.tool-call-call[data-tool-call-id="' + toolCallId.replace(/"/g, '\\"') + '"]');
                                            if (existing) return;
                                        }
                                        appendToolCallToChatRow(doneRow, toolCall, processHtmxElement, {
                                            state: toolCall.success ? 'done' : 'error',
                                            statusText: toolCall.success ? 'success' : 'failure',
                                            success: Boolean(toolCall.success),
                                            outputText: toolCallOutputText(toolCall)
                                        });
                                    });
                                }
                            }

                            // mark non-pending
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
                        clearPendingStream(assistantId, es);
                        refreshWorkspaceRail();
                        // remove stream-local listener when stream completes
                        removeStreamScrollListener();
                    });

                    es.addEventListener('stopped', (e) => {
                        try {
                            const payload = parseStreamPayload(e);
                            if (flushTimer) {
                                clearTimeout(flushTimer);
                                flushTimer = null;
                            }
                            flushBuffer();
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
                        clearPendingStream(assistantId, es);
                        refreshWorkspaceRail();
                        removeStreamScrollListener();
                    });

                    es.addEventListener('error', (e) => {
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
                            } else {
                                if (!(e && e.error)) {
                                    window.__connectionLossMonitor && window.__connectionLossMonitor.transportFailure();
                                }
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
                        clearPendingStream(assistantId, es);
                        refreshWorkspaceRail();
                        // remove stream-local listener on error as well
                        removeStreamScrollListener();
                    });

                    // Tool call events: render compact tool-call details under the pending row
                    es.addEventListener('tool_call', (e) => {
                        try {
                            const payload = parseStreamPayload(e) || {};
                            const liveRow = currentRow();
                            if (!liveRow) return;
                            appendToolCallToChatRow(liveRow, payload, processHtmxElement, {
                                state: payload.success ? 'done' : 'error',
                                statusText: payload.success ? 'success' : 'failure',
                                success: Boolean(payload.success),
                                outputText: toolCallOutputText(payload)
                            });

                            // Preserve streaming scroll behavior: if this stream was sticking
                            // to bottom, ensure we remain pinned after appending.
                            const stick = shouldStickToBottom || wasNearBottom();
                            if (stick) {
                                requestAnimationFrame(() => {
                                    try {
                                        const history = document.getElementById('chat-history');
                                        if (history) history.scrollTop = history.scrollHeight - history.clientHeight;
                                        // re-affirm stick state
                                        shouldStickToBottom = true;
                                    } catch (_) {
                                    }
                                });
                            }
                        } catch (_) {
                        }
                    });

                    // Ensure we flush any remaining buffer when the connection closes
                    es.addEventListener('close', () => {
                        try {
                            flushBuffer();
                        } catch (_) {
                        }
                        try {
                            es.close();
                        } catch (_) {
                        }
                        // remove stream-local listener on close as well
                        try {
                            removeStreamScrollListener();
                        } catch (_) {
                        }
                        clearPendingStream(assistantId, es);
                    });

                    // Install a stream-local scroll listener so we can track live user intent
                    // (they may scroll after the stream starts). Bind when stream is active
                    // and remove on done/error/close to avoid leaks.
                    try {
                        streamHistoryEl = document.getElementById('chat-history');
                        if (streamHistoryEl && streamHistoryEl.addEventListener) {
                            streamHistoryEl.addEventListener('scroll', streamScrollListener, {passive: true});
                        }
                    } catch (_) {
                        streamHistoryEl = null;
                    }

                    // Wrap flush to respect live shouldStickToBottom state.
                    const originalFlush = flushBuffer;
                    flushBuffer = function () {
                        // Before DOM append, capture whether we should stick. The
                        // current content height may change after append, so also
                        // consider immediate wasNearBottom() as a fallback.
                        const stickBeforeFlush = shouldStickToBottom || wasNearBottom();
                        originalFlush();
                        if (stickBeforeFlush) {
                            // schedule scroll after paint, then ensure the flag
                            // remains set so subsequent flushes stay sticky.
                            requestAnimationFrame(() => {
                                try {
                                    const history = document.getElementById('chat-history');
                                    if (history) history.scrollTop = history.scrollHeight - history.clientHeight;
                                    // re-affirm stick state after performing the scroll
                                    shouldStickToBottom = true;
                                } catch (_) {
                                }
                            });
                        }
                    };

                    // Teardown helper to remove listener and avoid leaks
                    function removeStreamScrollListener() {
                        try {
                            if (streamHistoryEl && streamHistoryEl.removeEventListener) {
                                streamHistoryEl.removeEventListener('scroll', streamScrollListener, {passive: true});
                            }
                        } catch (_) {
                        }
                    }
                });
            } catch (_) {
            }
        }

        // Run binding after HTMX swaps/settles and on initial load
        bindPendingStreams();
        syncFaviconWithRail();
        document.body.addEventListener('htmx:afterSwap', function (evt) {
            Promise.resolve().then(() => {
                bindPendingStreams();
                syncFaviconWithRail();
            });
        }, true);
        document.body.addEventListener('htmx:afterSettle', function (evt) {
            Promise.resolve().then(() => {
                bindPendingStreams();
                syncFaviconWithRail();
            });
        }, true);

        // Re-render markdown after HTMX swaps so server-rendered escaped text
        // becomes parsed markdown. Use the swap target when available to limit
        // the work and avoid unnecessary re-renders.
        document.body.addEventListener('htmx:afterSwap', function (evt) {
            try {
                const target = (evt && evt.detail && evt.detail.target) || evt.target || document;
                // Prefer the live chat list if present (handles beforeend appends)
                const liveList = document.getElementById('chat-messages-list');
                const base = liveList ? liveList : (target && target.querySelector && target.querySelector('.chat-message-text') ? target : document);
                Promise.resolve().then(() => {
                    renderAllChatMarkdown(base);
                    formatAllChatSubtitles(base);
                });
            } catch (_) {
            }
        }, true);
        document.body.addEventListener('htmx:afterSettle', function (evt) {
            try {
                const target = (evt && evt.detail && evt.detail.target) || evt.target || document;
                const liveList = document.getElementById('chat-messages-list');
                const base = liveList ? liveList : (target && target.querySelector && target.querySelector('.chat-message-text') ? target : document);
                Promise.resolve().then(() => {
                    renderAllChatMarkdown(base);
                    formatAllChatSubtitles(base);
                });
            } catch (_) {
            }
        }, true);

        document.body.addEventListener('htmx:afterSwap', function () {
            Promise.resolve().then(() => {
                initChatComposer();
                updateChatSendButtonState();
            });
        }, true);
        document.body.addEventListener('htmx:afterSettle', function () {
            Promise.resolve().then(() => {
                initChatComposer();
                updateChatSendButtonState();
            });
        }, true);
