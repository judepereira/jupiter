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
            const reviewOpen = !!(review && !review.classList.contains('closed'));
            shell.dataset.reviewOpen = reviewOpen ? 'true' : 'false';

            // On small screens the review panel is controlled by responsive CSS:
            // tablet keeps the pre-existing stacked layout, phone uses a drawer.
            if (window.innerWidth <= 900) {
                divider.classList.add('hidden');
                shell.classList.remove('review-open');
                shell.classList.remove('review-closed');
                return;
            }

            shell.classList.toggle('review-open', reviewOpen);
            shell.classList.toggle('review-closed', !reviewOpen);
            divider.classList.toggle('hidden', !reviewOpen);
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
