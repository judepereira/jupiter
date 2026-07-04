// Simple draggable divider for resizing the review panel.
(function(){
    const divider = document.getElementById('panel-divider');
    const shell = document.getElementById('shell');
    let review = document.getElementById('review');
    if(!divider || !shell) return;

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
        const dividerW = Math.max(1, Math.floor(dividerRect.width)) || 12;

        // review right edge sits left of the right rail by gap
        const reviewRight = shellRect.right - railWidth - gap;
        // treat pointer X as the divider center; account for half divider and half gap between divider and review
        const pointerCenter = e.clientX + (dividerW / 2) + (gap / 2);
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
        // hide on small screens where layout stacks regardless of review presence
        if(window.innerWidth <= 900){
            divider.classList.add('hidden');
            return;
        }

        if(!review || review.classList.contains('closed')){
            divider.classList.add('hidden');
        } else {
            divider.classList.remove('hidden');
        }
    }

    // Run on load
    updateDividerVisibility();

    // If HTMX is used to swap review, listen for afterSwap events and update visibility
    document.body.addEventListener('htmx:afterSwap', (evt)=>{
        // if swap targeted #review or replaced part of it, re-query
        if(!evt.detail || !evt.detail.target) {
            updateDividerVisibility();
            return;
        }
        const trg = evt.detail.target;
        if(trg.id === 'review' || trg.closest && trg.closest('#review')){
            // re-select review in case it's been replaced
            const newReview = document.getElementById('review');
            if(newReview) review = newReview; // update reference
        }
        updateDividerVisibility();
    }, true);

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

})();
