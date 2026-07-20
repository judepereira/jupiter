(() => {
    const root = document.getElementById('system-balloon-root');
    if (!root || window.__systemBalloonSource) return;

    const balloons = new Map();
    const MAX_VISIBLE = 5;
    const AUTO_DISMISS_MS = 6000;
    const EXIT_MS = 160;

    function normalizeType(type) {
        const value = String(type || '').trim().toLowerCase();
        if (value === 'error' || value === 'success' || value === 'warning') return value;
        return 'info';
    }

    function parsePayload(data) {
        return JSON.parse(data);
    }

    function removeBalloon(id, animate) {
        const entry = balloons.get(id);
        if (!entry) return;
        balloons.delete(id);
        clearTimeout(entry.timer);

        const node = entry.node;
        if (!node || !node.isConnected) return;
        if (!animate) {
            node.remove();
            return;
        }

        node.classList.add('is-leaving');
        window.setTimeout(() => node.remove(), EXIT_MS);
    }

    function trimBalloons() {
        const children = Array.from(root.children);
        const excess = children.length - MAX_VISIBLE;
        for (let i = 0; i < excess; i++) {
            const oldest = children[children.length - 1 - i];
            if (!oldest) break;
            removeBalloon(oldest.dataset.balloonId, true);
        }
    }

    function createBalloon(payload) {
        if (!payload || payload.id == null) return;

        const id = String(payload.id);
        removeBalloon(id, false);

        const title = String(payload.title ?? '').trim();
        const body = String(payload.body ?? '').trim();
        const type = normalizeType(payload.type);

        const node = document.createElement('div');
        node.className = 'system-balloon ' + type;
        node.dataset.balloonId = id;
        node.dataset.type = type;

        const content = document.createElement('div');
        content.className = 'system-balloon__content';

        if (title) {
            const titleEl = document.createElement('p');
            titleEl.className = 'system-balloon__title';
            titleEl.textContent = title;
            content.appendChild(titleEl);
        }

        const bodyEl = document.createElement('p');
        bodyEl.className = 'system-balloon__body';
        bodyEl.textContent = body;
        content.appendChild(bodyEl);

        const close = document.createElement('button');
        close.type = 'button';
        close.className = 'system-balloon__close';
        close.setAttribute('aria-label', 'Close notification');
        close.textContent = '×';
        close.addEventListener('click', () => removeBalloon(id, true));

        node.appendChild(content);
        node.appendChild(close);
        root.insertBefore(node, root.firstChild);

        requestAnimationFrame(() => node.classList.add('is-visible'));

        const timer = window.setTimeout(() => removeBalloon(id, true), AUTO_DISMISS_MS);
        balloons.set(id, {node, timer});
        trimBalloons();
    }

    const source = new EventSource('/ui/system-balloons/stream');
    window.__systemBalloonSource = source;

    source.addEventListener('balloon', event => {
        try {
            createBalloon(parsePayload(event.data));
        } catch (error) {
            console.error('Failed to parse system balloon', error);
        }
    });

    source.addEventListener('error', error => {
        console.error('System balloon stream error', error);
    });
})();

// Simple draggable divider for resizing the review panel.
(function () {
    const divider = document.getElementById('panel-divider');
    const shell = document.getElementById('shell');
    // Prefer the shell-level review panel only.
    function getShellReviewPanel() {
        if (!shell) return null;
        for (const child of shell.children) {
            if (child.id === 'review') return child;
        }
        return null;
    }
    let review = getShellReviewPanel();

    // Only initialize divider behavior if both elements are present.
    if (divider && shell) {
        const MIN_PX = 138; // min review width
        const MAX_RATIO = 0.7; // max as % of shell width

        let dragging = false;

        function setReviewWidthPx(px) {
            // clamp
            const shellRect = shell.getBoundingClientRect();
            const maxPx = Math.floor(shellRect.width * MAX_RATIO);
            const clamped = Math.max(MIN_PX, Math.min(px, maxPx));
            // set CSS variable on root of shell so grid uses it
            shell.style.setProperty('--review-width', clamped + 'px');
        }

        function onPointerDown(e) {
            if (e.button && e.button !== 0) return; // only left
            // don't allow dragging when review isn't present/open or on small screens
            if (window.innerWidth <= 900) return;
            if (!review || review.classList.contains('closed')) return;
            dragging = true;
            document.body.classList.add('dragging-divider');
            divider.classList.add('dragging');
            divider.setPointerCapture(e.pointerId);
        }

        function onPointerMove(e) {
            if (!dragging) return;
            const shellRect = shell.getBoundingClientRect();
            // Calculate review width robustly using computed layout values (no magic constants)
            // gap between grid columns (shell gap)
            const gapStr = getComputedStyle(shell).getPropertyValue('gap') || '12px';
            const gap = parseFloat(gapStr) || 12;
            // divider width from layout
            const dividerRect = divider.getBoundingClientRect();
            const dividerW = Math.max(1, Math.floor(dividerRect.width)) || 3;

            // review right edge is the shell edge; the terminal control is fixed chrome.
            const reviewRight = shellRect.right;
            const pointerCenter = e.clientX + (dividerW / 2) + (gap);
            const reviewPx = Math.floor(reviewRight - pointerCenter);
            setReviewWidthPx(reviewPx);
        }

        function endDrag(e) {
            if (!dragging) return;
            dragging = false;
            document.body.classList.remove('dragging-divider');
            divider.classList.remove('dragging');
            try {
                divider.releasePointerCapture(e && e.pointerId);
            } catch (_) {
            }
        }

        divider.addEventListener('pointerdown', onPointerDown);
        window.addEventListener('pointermove', onPointerMove);
        window.addEventListener('pointerup', endDrag);
        window.addEventListener('pointercancel', endDrag);

        // Utility to update divider visibility based on whether review is closed
        function updateDividerVisibility() {
            // hide divider when there's no review or it is closed
            review = getShellReviewPanel();
            // On small screens we want the stacked layout. Ensure the divider
            // is hidden, but also keep the shell in a neutral state: don't add
            // review-closed/open classes which are desktop-specific (they
            // change grid-template-columns). This prevents transient class
            // toggles from placing elements into implicit rows.
            if (window.innerWidth <= 900) {
                divider.classList.add('hidden');
                shell.classList.remove('review-open');
                shell.classList.remove('review-closed');
                // ensure bottom-rail gets the small-screen placement handled
                // by CSS media query (.bottom-rail { grid-column: 3 }). No JS
                // changes to grid columns here.
                return;
            }

            if (!review || review.classList.contains('closed')) {
                divider.classList.add('hidden');
                // no review: mark shell closed so grid drops the columns on desktop
                shell.classList.remove('review-open');
                shell.classList.add('review-closed');
            } else {
                divider.classList.remove('hidden');
                shell.classList.remove('review-closed');
                shell.classList.add('review-open');
            }
        }

        // Run on load
        // Ensure shell class reflects current state on load
        updateDividerVisibility();

        // If HTMX is used to swap review, listen for afterSwap events and update visibility
        // Use multiple lifecycle hooks and a small rAF delay to ensure DOM is stable
        // when we read classes/measurements. This defends against transient states
        // where the element is present but the shell class hasn't been synced yet.
        function handleHtmxUpdate(evt) {
            // If swap targeted the shell-level review panel, refresh reference.
            try {
                const trg = evt && evt.detail && evt.detail.target;
                if (trg && (trg.id === 'review' || (review && review.contains && review.contains(trg)))) {
                    const newReview = getShellReviewPanel();
                    if (newReview) review = newReview;
                }
            } catch (_) { /* defensive */
            }

            // Run update in next microtask + rAF to ensure HTMX DOM operations
            // and any synchronous JS mutations are finished before we measure.
            Promise.resolve().then(() => {
                requestAnimationFrame(() => {
                    updateDividerVisibility();
                });
            });
        }

        document.body.addEventListener('htmx:afterSwap', handleHtmxUpdate, true);
        document.body.addEventListener('htmx:afterSettle', handleHtmxUpdate, true);

        // Also update on window resize to ensure clamp limits remain sensible
        window.addEventListener('resize', () => {
            // update divider visibility on breakpoint changes
            updateDividerVisibility();
            // ensure current --review-width still within new bounds
            const current = getComputedStyle(shell).getPropertyValue('--review-width').trim();
            if (!current) return;
            if (current.endsWith('%')) return; // percentage is okay
            const px = parseFloat(current);
            if (Number.isFinite(px)) setReviewWidthPx(px);
        });
    }

    // Simple draggable divider for resizing the terminal bottom panel.
    (function () {
        let dragging = false;
        let activePointerId = null;
        let activeDivider = null;

        function getShell() {
            return document.getElementById('shell');
        }

        function getBottomPanel() {
            return document.getElementById('bottom-panel');
        }

        function getDivider() {
            return document.getElementById('terminal-panel-divider');
        }

        function setPanelHeightPx(px) {
            const shell = getShell();
            if (!shell) return;

            const shellRect = shell.getBoundingClientRect();
            const minPx = 160;
            const maxPx = Math.floor(shellRect.height - 120);
            if (maxPx < minPx) return;
            const clamped = Math.max(minPx, Math.min(px, maxPx));
            shell.style.setProperty('--terminal-panel-height', clamped + 'px');
        }

        function clampTerminalPanelHeight() {
            const shell = getShell();
            if (!shell) return;

            const current = getComputedStyle(shell).getPropertyValue('--terminal-panel-height').trim();
            if (!current || current.endsWith('%')) return;

            const px = parseFloat(current);
            if (Number.isFinite(px)) setPanelHeightPx(px);
        }

        function beginDrag(e) {
            if (e.button && e.button !== 0) return;

            const shell = getShell();
            const bottomPanel = getBottomPanel();
            const divider = getDivider();
            if (!shell || !bottomPanel || !divider || bottomPanel.classList.contains('closed')) return;
            if (shell.getBoundingClientRect().height <= 280) return;

            dragging = true;
            activePointerId = e.pointerId;
            activeDivider = divider;

            divider.classList.add('dragging');
            try {
                divider.setPointerCapture(e.pointerId);
            } catch (_) {
            }
            e.preventDefault();
        }

        function onPointerMove(e) {
            if (!dragging || e.pointerId !== activePointerId) return;

            const shell = getShell();
            if (!shell) return;

            const shellRect = shell.getBoundingClientRect();
            setPanelHeightPx(shellRect.bottom - e.clientY);
        }

        function endDrag(e) {
            if (!dragging) return;
            if (e && e.pointerId != null && activePointerId != null && e.pointerId !== activePointerId) return;

            dragging = false;
            const divider = activeDivider || getDivider();
            activePointerId = null;
            activeDivider = null;

            if (divider) {
                divider.classList.remove('dragging');
                try {
                    divider.releasePointerCapture(e && e.pointerId);
                } catch (_) {
                }
            }
        }

        document.addEventListener('pointerdown', e => {
            const divider = e.target && e.target.closest ? e.target.closest('#terminal-panel-divider') : null;
            if (!divider) return;
            beginDrag(e);
        });
        document.addEventListener('pointermove', onPointerMove);
        document.addEventListener('pointerup', endDrag);
        document.addEventListener('pointercancel', endDrag);
        window.addEventListener('resize', clampTerminalPanelHeight);
        document.body.addEventListener('htmx:afterSwap', () => {
            if (dragging) endDrag({pointerId: activePointerId});
        }, true);
    })();

    // Global keyboard shortcuts.
    (function () {
        if (window.__appKeyboardShortcutsBound) return;
        window.__appKeyboardShortcutsBound = true;

        function cycleSelect(select, step) {
            const options = select && select.options;
            if (!options || !options.length) return false;
            const next = (select.selectedIndex + step + options.length) % options.length;
            if (next === select.selectedIndex) return false;
            select.selectedIndex = next;
            select.dispatchEvent(new Event('change', {bubbles: true}));
            return true;
        }

        document.addEventListener('keydown', e => {
            if (e.repeat || e.isComposing) return;

            if (e.ctrlKey && !e.metaKey && !e.altKey && (e.code === 'Backquote' || e.key === '~' || e.key === '`')) {
                const button = document.getElementById('toggle-terminal-rail-btn');
                if (!button) return;
                e.preventDefault();
                button.click();
                return;
            }

            if (e.metaKey && !e.ctrlKey && !e.altKey && !e.shiftKey && e.key === '.') {
                const select = document.getElementById('chat-agent-select');
                if (!select) return;
                if (cycleSelect(select, 1)) e.preventDefault();
                return;
            }

            if (e.metaKey && e.shiftKey && !e.ctrlKey && !e.altKey && (e.key === 'D' || e.key === 'd')) {
                const select = document.getElementById('chat-thinking-select');
                if (!select) return;
                if (cycleSelect(select, 1)) e.preventDefault();
            }
        }, true);
    })();

    // Chat composer logic: kept outside the divider-guard so it runs even when
    // divider or shell are absent (HTMX swaps may only render chat fragments).
    (function () {
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
        // Ensure we add the textarea clear listener only once across re-inits
        let htmxAfterOnLoadBound = false;

        function scrollChatToBottom() {
            try {
                const history = document.getElementById('chat-history');
                const list = document.getElementById('chat-messages-list');
                if (!history || !list) return;
                // Use rAF to ensure layout is settled before manipulating scroll
                requestAnimationFrame(() => {
                    // Defensive: only set when it actually would move
                    const target = history.scrollHeight - history.clientHeight;
                    if (Number.isFinite(target)) history.scrollTop = target;
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
                    // Consider "near bottom" to be within 48px of the max scroll
                    const max = history.scrollHeight - history.clientHeight;
                    const cur = history.scrollTop;
                    if (!Number.isFinite(max) || !Number.isFinite(cur)) return false;
                    return (max - cur) <= 48;
                } catch (_) {
                    return false;
                }
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
        }

        function getChatSelectOption(select) {
            if (!select) return null;
            return select.options ? select.options[select.selectedIndex] : null;
        }

        function updateChatQualitySummary(form) {
            const agentSelect = form && form.querySelector('#chat-agent-select');
            const modelSelect = form && form.querySelector('#chat-model-select');
            const thinkingSelect = form && form.querySelector('#chat-thinking-select');
            const currentModel = form && form.querySelector('[data-chat-current-model]');
            const currentThinking = form && form.querySelector('[data-chat-current-thinking]');
            const writeAccess = form && form.querySelector('[data-chat-write-access]');
            const commandAccess = form && form.querySelector('[data-chat-command-access]');
            if (!agentSelect || !modelSelect || !thinkingSelect || !currentModel || !currentThinking || !writeAccess || !commandAccess) return;

            const agentOption = getChatSelectOption(agentSelect);
            const modelOption = getChatSelectOption(modelSelect);
            const thinkingOption = getChatSelectOption(thinkingSelect);
            currentModel.textContent = modelOption ? modelOption.textContent : '';
            currentThinking.textContent = thinkingOption ? thinkingOption.textContent : '';
            writeAccess.textContent = agentOption && agentOption.dataset && agentOption.dataset.allowWrite === 'true' ? 'yes' : 'no';
            commandAccess.textContent = agentOption && agentOption.dataset && agentOption.dataset.allowCommand === 'true' ? 'yes' : 'no';
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
            updateChatQualitySummary(form);
        }

        function bindChatControlListeners(form) {
            if (!form || form.dataset.chatControlsBound === '1') return;
            form.dataset.chatControlsBound = '1';

            const agentSelect = form.querySelector('#chat-agent-select');
            const modelSelect = form.querySelector('#chat-model-select');
            const thinkingSelect = form.querySelector('#chat-thinking-select');
            if (!agentSelect || !modelSelect || !thinkingSelect) return;

            agentSelect.addEventListener('change', () => syncChatDefaults(form));
            modelSelect.addEventListener('change', () => updateChatQualitySummary(form));
            thinkingSelect.addEventListener('change', () => updateChatQualitySummary(form));
            syncChatDefaults(form);
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

                // Avoid double-binding when initializer is rerun for HTMX swaps.
                if (textarea.dataset.chatBound !== '1') {
                    textarea.dataset.chatBound = '1';

                    // Handle keyboard: Enter submits, Option+Enter inserts a newline.
                    // Respect IME composition.
                    function onKeyDown(e) {
                        const isEnter = e.key === 'Enter' || e.keyCode === 13;
                        if (!isEnter) return;
                        if (e.isComposing) return; // IME in progress
                        if (e.altKey) return; // Option+Enter should keep the textarea newline behavior

                        // Submit on plain Enter.
                        e.preventDefault();
                        if (typeof form.requestSubmit === 'function') {
                            form.requestSubmit();
                        } else {
                            form.submit();
                        }
                    }

                    textarea.addEventListener('input', () => resizeChatTextarea(textarea));
                    textarea.addEventListener('keydown', onKeyDown);
                    // Clear textarea after successful htmx form submit when targeting messages list
                    if (!htmxAfterOnLoadBound) {
                        htmxAfterOnLoadBound = true;
                        document.body.addEventListener('htmx:afterOnLoad', function (evt) {
                            try {
                                const detail = evt && evt.detail;
                                const target = detail && detail.target;
                                // Only clear when the request was a form submit to /ui/chat/send
                                if (!detail || !detail.xhr) return;
                                // HTMX exposes the request path on detail.path in some builds; fallback to inspecting the request URL
                                const path = (detail.path) || (detail.xhr && detail.xhr.responseURL) || '';
                                if (!path) return;
                                if (!path.includes('/ui/chat/send')) return;

                                const textarea = document.getElementById('chat-input');
                                if (!textarea) return;
                                // Clear and reset height
                                textarea.value = '';
                                resizeChatTextarea(textarea);
                            } catch (_) {
                            }
                        }, true);
                    }
                }

                // Initial resize to match any prefilled content
                // Use rAF to allow browser to compute styles if needed
                requestAnimationFrame(() => resizeChatTextarea(textarea));
                bindChatControlListeners(form);
                // Bind auto-scroll listeners once chat composer exists on page
                // and perform an initial check/scroll.
                bindAutoScrollListeners();
                checkAndMaybeScroll();
            } catch (_) { /* defensive - don't break other UI */
            }
        }

        // Run once on load to bind any existing chat fragment
        initChatComposer();
        // Initial render of any server-rendered messages into markdown
        try {
            renderAllChatMarkdown();
        } catch (_) {
        }

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

        // Streaming SSE binding: open EventSource for pending assistant rows.
        // We run after HTMX swaps/settles so newly swapped pending rows can start streaming.
        function bindPendingStreams() {
            try {
                const list = document.getElementById('chat-messages-list');
                if (!list) return;
                const rows = list.querySelectorAll('li[data-pending="true"]');
                rows.forEach(row => {
                    if (row.dataset.streamBound === '1') return; // already bound
                    const url = row.dataset.streamUrl;
                    if (!url) return;
                    row.dataset.streamBound = '1';

                    // Buffer incoming deltas and batch DOM writes
                    const FLUSH_INTERVAL_MS = 40; // throttle cadence for visible progressive updates
                    let buffer = '';
                    let gotDelta = false;
                    let rafPending = false;
                    let flushTimer = null;
                    let lastFlushTime = 0;
                    const textSpan = row.querySelector('.chat-message-text');

                    function flushBuffer() {
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
                    row._es = es;

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

                    es.addEventListener('status', (e) => {
                        try {
                            const payload = parseStreamPayload(e);
                            const st = (payload && payload.status != null) ? payload.status : (e.data || '');
                            if (st) row.title = st;
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

                            // mark non-pending
                            row.classList.remove('pending');
                            row.removeAttribute('data-pending');
                            row.dataset.streamBound = '0';
                        } catch (_) {
                        }
                        try {
                            es.close();
                        } catch (_) {
                        }
                        // remove stream-local listener when stream completes
                        removeStreamScrollListener();
                    });

                    es.addEventListener('error', (e) => {
                        try {
                            const payload = parseStreamPayload(e);
                            const data = (payload && payload.message) ? payload.message : ((e && e.data) ? e.data : 'Stream error');
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
                            row.classList.remove('pending');
                            row.removeAttribute('data-pending');
                            row.dataset.streamBound = '0';
                        } catch (_) {
                        }
                        try {
                            es.close();
                        } catch (_) {
                        }
                        // remove stream-local listener on error as well
                        removeStreamScrollListener();
                    });

                    // Tool call events: render compact tool-call details under the pending row
                    es.addEventListener('tool_call', (e) => {
                        try {
                            const payload = parseStreamPayload(e) || {};
                            // payload expected: {toolName, success, inputPreview, outputPreview, inputTruncated, outputTruncated}
                            const toolName = payload.toolName != null ? String(payload.toolName) : '';
                            const success = Boolean(payload.success);
                            const inputPreview = payload.inputPreview != null ? String(payload.inputPreview) : '';
                            const outputPreview = payload.outputPreview != null ? String(payload.outputPreview) : '';
                            const inputTruncated = Boolean(payload.inputTruncated);
                            const outputTruncated = Boolean(payload.outputTruncated);

                            // ensure container exists on the row
                            let callsContainer = row.querySelector('.tool-calls');
                            if (!callsContainer) {
                                callsContainer = document.createElement('div');
                                callsContainer.className = 'tool-calls';
                                row.appendChild(callsContainer);
                            }

                            // build details block
                            const details = document.createElement('details');
                            details.className = 'tool-call';

                            const summary = document.createElement('summary');
                            summary.className = 'tool-call-summary';

                            const nameSpan = document.createElement('span');
                            nameSpan.className = 'tool-call-name';
                            nameSpan.textContent = toolName || 'tool';

                            const statusSpan = document.createElement('span');
                            statusSpan.className = 'tool-call-status';
                            if (success) statusSpan.classList.add('tool-call-status-success'); else statusSpan.classList.add('tool-call-status-failure');
                            statusSpan.textContent = success ? ' success' : ' failure';

                            summary.appendChild(nameSpan);
                            summary.appendChild(statusSpan);
                            details.appendChild(summary);

                            const detailDiv = document.createElement('div');
                            detailDiv.className = 'tool-call-detail';

                            // Input section
                            const inSection = document.createElement('div');
                            inSection.className = 'tool-call-section';
                            const inLabel = document.createElement('span');
                            inLabel.className = 'tool-call-label';
                            inLabel.textContent = 'Input:';
                            const inPre = document.createElement('pre');
                            inPre.className = 'tool-call-pre';
                            inPre.textContent = inputPreview;
                            inSection.appendChild(inLabel);
                            inSection.appendChild(inPre);
                            if (inputTruncated) {
                                const tr = document.createElement('span');
                                tr.className = 'tool-call-truncated';
                                tr.textContent = ' (truncated)';
                                inSection.appendChild(tr);
                            }

                            // Output section
                            const outSection = document.createElement('div');
                            outSection.className = 'tool-call-section';
                            const outLabel = document.createElement('span');
                            outLabel.className = 'tool-call-label';
                            outLabel.textContent = 'Output:';
                            const outPre = document.createElement('pre');
                            outPre.className = 'tool-call-pre';
                            outPre.textContent = outputPreview;
                            outSection.appendChild(outLabel);
                            outSection.appendChild(outPre);
                            if (outputTruncated) {
                                const tr2 = document.createElement('span');
                                tr2.className = 'tool-call-truncated';
                                tr2.textContent = ' (truncated)';
                                outSection.appendChild(tr2);
                            }

                            detailDiv.appendChild(inSection);
                            detailDiv.appendChild(outSection);
                            details.appendChild(detailDiv);

                            // Append to container
                            callsContainer.appendChild(details);

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
        document.body.addEventListener('htmx:afterSwap', function (evt) {
            Promise.resolve().then(bindPendingStreams);
        }, true);
        document.body.addEventListener('htmx:afterSettle', function (evt) {
            Promise.resolve().then(bindPendingStreams);
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
                Promise.resolve().then(() => renderAllChatMarkdown(base));
            } catch (_) {
            }
        }, true);
        document.body.addEventListener('htmx:afterSettle', function (evt) {
            try {
                const target = (evt && evt.detail && evt.detail.target) || evt.target || document;
                const liveList = document.getElementById('chat-messages-list');
                const base = liveList ? liveList : (target && target.querySelector && target.querySelector('.chat-message-text') ? target : document);
                Promise.resolve().then(() => renderAllChatMarkdown(base));
            } catch (_) {
            }
        }, true);

        document.body.addEventListener('htmx:afterSwap', function () {
            Promise.resolve().then(initChatComposer);
        }, true);
        document.body.addEventListener('htmx:afterSettle', function () {
            Promise.resolve().then(initChatComposer);
        }, true);
    })();

    // Terminal pane: bind xterm.js mounts and keep them resized after HTMX swaps.
    (function () {
        const mounts = new Map();
        let syncQueued = false;
        const lightTerminalTheme = {
            background: '#ffffff',
            foreground: '#1f2937',
            cursor: '#334155',
            selectionBackground: '#dbeafe',
            black: '#111827',
            red: '#dc2626',
            green: '#16a34a',
            yellow: '#ca8a04',
            blue: '#2563eb',
            magenta: '#7c3aed',
            cyan: '#0891b2',
            white: '#e5e7eb',
            brightBlack: '#374151',
            brightRed: '#ef4444',
            brightGreen: '#22c55e',
            brightYellow: '#eab308',
            brightBlue: '#3b82f6',
            brightMagenta: '#8b5cf6',
            brightCyan: '#06b6d4',
            brightWhite: '#f9fafb'
        };

        function toWebSocketUrl(rawUrl) {
            if (!rawUrl) return '';
            try {
                const url = new URL(rawUrl, window.location.href);
                if (url.protocol === 'http:') url.protocol = 'ws:';
                else if (url.protocol === 'https:') url.protocol = 'wss:';
                return url.toString();
            } catch (_) {
                return rawUrl;
            }
        }

        function disposeMount(mount) {
            const entry = mounts.get(mount);
            if (!entry) return;
            mounts.delete(mount);
            try {
                entry.resizeObserver && entry.resizeObserver.disconnect();
            } catch (_) {
            }
            try {
                entry.socket.close();
            } catch (_) {
            }
            try {
                entry.terminal.dispose();
            } catch (_) {
            }
        }

        function fitEntry(entry) {
            if (!entry || !document.contains(entry.mount)) return;
            entry.fitAddon.fit();
            const cols = Math.floor(entry.terminal.cols);
            const rows = Math.floor(entry.terminal.rows);
            const resizeSignature = cols + 'x' + rows;
            if (entry.socket.readyState === WebSocket.OPEN && resizeSignature !== entry.lastSentResizeSignature) {
                entry.socket.send(JSON.stringify({type: 'resize', cols, rows}));
                entry.lastSentResizeSignature = resizeSignature;
            }
        }

        function scheduleInitialFit(entry) {
            requestAnimationFrame(() => {
                requestAnimationFrame(() => {
                    fitEntry(entry);
                });
            });
        }

        function syncTerminals() {
            syncQueued = false;

            for (const mount of mounts.keys()) {
                if (!document.contains(mount)) {
                    disposeMount(mount);
                }
            }

            const terminalMounts = document.querySelectorAll('.terminal-mount[data-terminal-id][data-ws-url]');
            terminalMounts.forEach(mount => {
                if (!mounts.has(mount)) {
                    initMount(mount);
                }
                fitEntry(mounts.get(mount));
            });
        }

        function queueSync() {
            if (syncQueued) return;
            syncQueued = true;
            Promise.resolve().then(() => requestAnimationFrame(syncTerminals));
        }

        function initMount(mount) {
            if (!window.Terminal || !window.FitAddon || !window.FitAddon.FitAddon) return;

            const terminalId = mount.dataset.terminalId;
            const wsUrl = toWebSocketUrl(mount.dataset.wsUrl);
            if (!terminalId || !wsUrl) return;

            const terminal = new window.Terminal({
                cursorBlink: true,
                convertEol: true,
                fontFamily: 'ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", monospace',
                fontSize: 13,
                theme: lightTerminalTheme
            });
            const fitAddon = new window.FitAddon.FitAddon();
            terminal.loadAddon(fitAddon);
            terminal.open(mount);

            const socket = new WebSocket(wsUrl);
            const entry = {mount, terminal, fitAddon, socket, resizeObserver: null, lastSentResizeSignature: ''};
            mounts.set(mount, entry);

            socket.addEventListener('open', () => {
                scheduleInitialFit(entry);
            });

            const resizeObserver = new ResizeObserver(() => queueSync());
            entry.resizeObserver = resizeObserver;
            resizeObserver.observe(mount);
            const bottomPanel = mount.closest('.bottom-panel');
            if (bottomPanel) resizeObserver.observe(bottomPanel);

            terminal.onData(data => {
                try {
                    if (socket.readyState === WebSocket.OPEN) {
                        socket.send(JSON.stringify({type: 'input', data}));
                    }
                } catch (_) {
                }
            });

            socket.addEventListener('message', evt => {
                try {
                    const payload = JSON.parse(evt.data);
                    if (!payload || typeof payload !== 'object') return;
                    if (payload.type === 'output' && payload.data != null) {
                        terminal.write(String(payload.data));
                    } else if (payload.type === 'exit') {
                        terminal.writeln('');
                        terminal.writeln(`[terminal exited with code ${payload.code ?? 'unknown'}]`);
                        socket.close();
                    } else if (payload.type === 'error') {
                        terminal.writeln('');
                        terminal.writeln(`[terminal error] ${payload.message ?? 'Terminal error'}`);
                        socket.close();
                    }
                } catch (_) {
                }
            });
            socket.addEventListener('close', () => {
            });
            socket.addEventListener('error', () => {
                try {
                    terminal.writeln('');
                    terminal.writeln('[terminal connection error]');
                } catch (_) {
                }
            });
        }

        queueSync();
        window.addEventListener('resize', queueSync);
        document.body.addEventListener('htmx:afterSwap', queueSync, true);
        document.body.addEventListener('htmx:afterSettle', queueSync, true);
    })();

    (function () {
        function focusSessionNameInput() {
            const input = document.getElementById('session-name-input');
            if (input) input.focus();
        }

        function restoreSessionCreateButton(event) {
            const input = document.getElementById('session-name-input');
            if (!input) return;

            const form = document.querySelector('[data-session-create-form]');
            if (!form) return;

            const target = event && event.target;
            if (target && target.closest && target.closest('[data-session-create-form]')) return;

            window.htmx.ajax('GET', '/ui/sessions/new/button', {target: form, swap: 'outerHTML'});
        }

        document.body.addEventListener('htmx:afterSwap', () => Promise.resolve().then(focusSessionNameInput), true);
        document.body.addEventListener('htmx:afterSettle', () => Promise.resolve().then(focusSessionNameInput), true);
        document.addEventListener('click', restoreSessionCreateButton);
    })();

})();
