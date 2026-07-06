// Simple draggable divider for resizing the review panel.
(function(){
    const divider = document.getElementById('panel-divider');
    const shell = document.getElementById('shell');
    let review = document.getElementById('review');

    // Only initialize divider behavior if both elements are present.
    if(divider && shell){
        const MIN_PX = 220; // min review width
        const MAX_RATIO = 0.7; // max as % of shell width

        let dragging = false;

        function setReviewWidthPx(px){
            // clamp
            const shellRect = shell.getBoundingClientRect();
            const maxPx = Math.floor(shellRect.width * MAX_RATIO);
            const clamped = Math.max(MIN_PX, Math.min(px, maxPx));
            // set CSS variable on root of shell so grid uses it
            shell.style.setProperty('--review-width', clamped + 'px');
        }

        function onPointerDown(e){
            if(e.button && e.button !== 0) return; // only left
            // don't allow dragging when review isn't present/open or on small screens
            if(window.innerWidth <= 900) return;
            if(!review || review.classList.contains('closed')) return;
            dragging = true;
            document.body.classList.add('dragging-divider');
            divider.classList.add('dragging');
            divider.setPointerCapture(e.pointerId);
        }

        function onPointerMove(e){
            if(!dragging) return;
            const shellRect = shell.getBoundingClientRect();
            // Calculate review width robustly using computed layout values (no magic constants)
            // right rail width from CSS variable --rail-width (fallback 40)
            const railWidthStr = getComputedStyle(document.documentElement).getPropertyValue('--rail-width') || '40px';
            const railWidth = parseFloat(railWidthStr) || 40;
            // gap between grid columns (shell gap)
            const gapStr = getComputedStyle(shell).getPropertyValue('gap') || '12px';
            const gap = parseFloat(gapStr) || 12;
            // divider width from layout
            const dividerRect = divider.getBoundingClientRect();
            const dividerW = Math.max(1, Math.floor(dividerRect.width)) || 3;

            // review right edge sits left of the right rail by gap
            const reviewRight = shellRect.right - railWidth - gap;
            const pointerCenter = e.clientX + (dividerW / 2) + (gap);
            const reviewPx = Math.floor(reviewRight - pointerCenter);
            setReviewWidthPx(reviewPx);
        }

        function endDrag(e){
            if(!dragging) return;
            dragging = false;
            document.body.classList.remove('dragging-divider');
            divider.classList.remove('dragging');
            try{ divider.releasePointerCapture(e && e.pointerId); }catch(_){ }
        }

        divider.addEventListener('pointerdown', onPointerDown);
        window.addEventListener('pointermove', onPointerMove);
        window.addEventListener('pointerup', endDrag);
        window.addEventListener('pointercancel', endDrag);

        // Utility to update divider visibility based on whether review is closed
        function updateDividerVisibility(){
            // hide divider when there's no review or it is closed
            review = document.getElementById('review');
            // On small screens we want the stacked layout. Ensure the divider
            // is hidden, but also keep the shell in a neutral state: don't add
            // review-closed/open classes which are desktop-specific (they
            // change grid-template-columns). This prevents transient class
            // toggles from placing elements into implicit rows.
            if(window.innerWidth <= 900){
                divider.classList.add('hidden');
                shell.classList.remove('review-open');
                shell.classList.remove('review-closed');
                // ensure right-rail gets the small-screen placement handled
                // by CSS media query (.right-rail { grid-column: 3 }). No JS
                // changes to grid columns here.
                return;
            }

            if(!review || review.classList.contains('closed')){
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
        function handleHtmxUpdate(evt){
            // If swap targeted #review or contained it, refresh reference
            try{
                const trg = evt && evt.detail && evt.detail.target;
                if(trg && (trg.id === 'review' || (trg.closest && trg.closest('#review')))){
                    const newReview = document.getElementById('review');
                    if(newReview) review = newReview;
                }
            }catch(_){ /* defensive */ }

            // Run update in next microtask + rAF to ensure HTMX DOM operations
            // and any synchronous JS mutations are finished before we measure.
            Promise.resolve().then(()=>{
                requestAnimationFrame(()=>{
                    updateDividerVisibility();
                });
            });
        }

        document.body.addEventListener('htmx:afterSwap', handleHtmxUpdate, true);
        document.body.addEventListener('htmx:afterSettle', handleHtmxUpdate, true);

        // Also update on window resize to ensure clamp limits remain sensible
        window.addEventListener('resize', ()=>{
            // update divider visibility on breakpoint changes
            updateDividerVisibility();
            // ensure current --review-width still within new bounds
            const current = getComputedStyle(shell).getPropertyValue('--review-width').trim();
            if(!current) return;
            if(current.endsWith('%')) return; // percentage is okay
            const px = parseFloat(current);
            if(Number.isFinite(px)) setReviewWidthPx(px);
        });
    }

    // Chat composer logic: kept outside the divider-guard so it runs even when
    // divider or shell are absent (HTMX swaps may only render chat fragments).
    (function(){
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

        function scrollChatToBottom(){
            try{
                const history = document.getElementById('chat-history');
                const list = document.getElementById('chat-messages-list');
                if(!history || !list) return;
                // Use rAF to ensure layout is settled before manipulating scroll
                requestAnimationFrame(()=>{
                    // Defensive: only set when it actually would move
                    const target = history.scrollHeight - history.clientHeight;
                    if(Number.isFinite(target)) history.scrollTop = target;
                });
            }catch(_){ /* defensive */ }
        }

        function checkAndMaybeScroll(){
            try{
                const list = document.getElementById('chat-messages-list');
                if(!list){
                    // If list is absent, reset sentinel so future renders can
                    // trigger the initial scroll.
                    lastMessageCount = -1;
                    return;
                }
                const count = list.children ? list.children.length : 0;
                // On first observed render, always scroll once.
                if(lastMessageCount === -1){
                    lastMessageCount = count;
                    scrollChatToBottom();
                    return;
                }
                if(count > lastMessageCount){
                    lastMessageCount = count;
                    scrollChatToBottom();
                } else {
                    // Update tracked count even when messages are removed or
                    // unchanged so future increases are measured correctly.
                    lastMessageCount = count;
                }
            }catch(_){ /* defensive */ }
        }

        function bindAutoScrollListeners(){
            if(chatAutoScrollBound) return;
            chatAutoScrollBound = true;
            // Integrate with HTMX lifecycle. Use a small async window so the
            // swapped DOM is attached before we measure. We intentionally only
            // trigger scroll when the message count increases (see check fn).
            // Only react to HTMX swaps that actually touch the chat fragment.
            // This avoids scheduling scroll work for unrelated swaps (eg: sidebars,
            // lists) which could otherwise cause an initial or unexpected
            // scroll-to-bottom.

            function isHistoryNearBottom(){
                try{
                    const history = document.getElementById('chat-history');
                    if(!history) return false;
                    // Consider "near bottom" to be within 48px of the max scroll
                    const max = history.scrollHeight - history.clientHeight;
                    const cur = history.scrollTop;
                    if(!Number.isFinite(max) || !Number.isFinite(cur)) return false;
                    return (max - cur) <= 48;
                }catch(_){ return false; }
            }

            // Before-swap listener records whether the user was near bottom.
            function htmxBeforeSwapListener(evt){
                try{
                    const trg = (evt && evt.detail && evt.detail.target) || evt.target;
                    if(!trg) return;
                    if(trg.id === 'chat-history' || trg.id === 'chat-messages-list' ||
                       (trg.closest && (trg.closest('#chat-history') || trg.closest('#chat-messages-list') || trg.closest('#chat-send-form') || trg.closest('#chat-input')))){
                        wasNearBottomBeforeSwap = isHistoryNearBottom();
                    }
                }catch(_){ /* defensive */ }
            }

            function htmxChatListener(evt){
                try{
                    // Prefer HTMX-provided detail.target but fall back to the
                    // event target — this improves compatibility with different
                    // dispatching paths while still avoiding global reactions.
                    const trg = (evt && evt.detail && evt.detail.target) || evt.target;
                    if(!trg) return;

                    // If the swap directly targeted the chat history/list or is
                    // contained within it, run the check.
                    if(trg.id === 'chat-history' || trg.id === 'chat-messages-list' ||
                       (trg.closest && (trg.closest('#chat-history') || trg.closest('#chat-messages-list') || trg.closest('#chat-send-form') || trg.closest('#chat-input')))){
                        // Run count-based auto-scroll logic as before
                        Promise.resolve().then(checkAndMaybeScroll);
                        // If the user was near-bottom before the swap, ensure
                        // we re-pin them to the bottom after the swap as well
                        // (covers pending->final replacements that keep message
                        // count unchanged but change element heights).
                        if(wasNearBottomBeforeSwap){
                            Promise.resolve().then(()=>{
                                // Double-guard: only scroll if still near-bottom or
                                // the flag was set; this avoids forcing scroll when
                                // the user had scrolled up after the beforeSwap.
                                scrollChatToBottom();
                                wasNearBottomBeforeSwap = false;
                            });
                        }
                    }
                }catch(_){ /* defensive */ }
            }

            // Listen before swap to capture scroll position, then react
            // after swap/settle to restore if needed.
            document.body.addEventListener('htmx:beforeSwap', htmxBeforeSwapListener, true);
            document.body.addEventListener('htmx:afterSwap', htmxChatListener, true);
            document.body.addEventListener('htmx:afterSettle', htmxChatListener, true);
        }

        function initChatComposer(){
            try{
                const form = document.getElementById('chat-send-form');
                const textarea = document.getElementById('chat-input');
                if(!form || !textarea) return;

                // Avoid double-binding when initializer is rerun for HTMX swaps.
                if(textarea.dataset.chatBound === '1') return;
                textarea.dataset.chatBound = '1';

                function autoResize(){
                    // Reset to natural height then apply scrollHeight
                    textarea.style.height = 'auto';
                    const sh = textarea.scrollHeight;
                    textarea.style.height = sh + 'px';

                    // Toggle overflow only when content exceeds computed max-height
                    const cs = getComputedStyle(textarea);
                    const maxH = cs.maxHeight;
                    if(maxH && maxH !== 'none'){
                        const maxVal = parseFloat(maxH);
                        if(!isNaN(maxVal) && sh > maxVal){
                            textarea.style.overflowY = 'auto';
                        } else {
                            textarea.style.overflowY = 'hidden';
                        }
                    } else {
                        // No max-height set; let natural overflow
                        textarea.style.overflowY = '';
                    }
                }

                // Handle keyboard: default textarea Enter/Shift+Enter behaviour (newline).
                // Submit only when Enter is pressed with the Meta/Command key. Respect IME composition.
                function onKeyDown(e){
                    const isEnter = e.key === 'Enter' || e.keyCode === 13;
                    if(!isEnter) return;
                    if(e.isComposing) return; // IME in progress
                    // Only intercept Command/Meta+Enter. Leave plain Enter and Shift+Enter alone.
                    if(!e.metaKey) return;
                    if(e.shiftKey) return; // allow Command+Shift+Enter to behave like newline as well

                    // Submit
                    e.preventDefault();
                    if(typeof form.requestSubmit === 'function'){
                        form.requestSubmit();
                    } else {
                        form.submit();
                    }
                }

                textarea.addEventListener('input', autoResize);
                textarea.addEventListener('keydown', onKeyDown);
                // Clear textarea after successful htmx form submit when targeting messages list
                if(!htmxAfterOnLoadBound){
                    htmxAfterOnLoadBound = true;
                    document.body.addEventListener('htmx:afterOnLoad', function(evt){
                        try{
                            const detail = evt && evt.detail;
                            const target = detail && detail.target;
                            // Only clear when the request was a form submit to /ui/chat/send
                            if(!detail || !detail.xhr) return;
                            // HTMX exposes the request path on detail.path in some builds; fallback to inspecting the request URL
                            const path = (detail.path) || (detail.xhr && detail.xhr.responseURL) || '';
                            if(!path) return;
                            if(!path.includes('/ui/chat/send')) return;

                            const textarea = document.getElementById('chat-input');
                            if(!textarea) return;
                            // Clear and reset height
                            textarea.value = '';
                            textarea.style.height = 'auto';
                            // run autoResize logic
                            const sh = textarea.scrollHeight;
                            textarea.style.height = sh + 'px';
                        }catch(_){ }
                    }, true);
                }

                // Initial resize to match any prefilled content
                // Use rAF to allow browser to compute styles if needed
                requestAnimationFrame(autoResize);
                // Bind auto-scroll listeners once chat composer exists on page
                // and perform an initial check/scroll.
                bindAutoScrollListeners();
                checkAndMaybeScroll();
            }catch(_){ /* defensive - don't break other UI */ }
        }

        // Run once on load to bind any existing chat fragment
        initChatComposer();

        // Re-run after HTMX swaps/settles so newly swapped chat fragment gets handlers
        document.body.addEventListener('htmx:afterSwap', function(){
            // small async window to allow swap to fully attach DOM
            Promise.resolve().then(initChatComposer);
        }, true);
        document.body.addEventListener('htmx:afterSettle', function(){
            Promise.resolve().then(initChatComposer);
        }, true);
    })();

})();
