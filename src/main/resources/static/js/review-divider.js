import { createPointerResizeController } from './pointer-resize.js';

const divider = document.getElementById('panel-divider');
const shell = document.getElementById('shell');

function getShellReviewPanel() {
    if (!shell) return null;
    for (const child of shell.children) {
        if (child.id === 'review') return child;
    }
    return null;
}

if (divider && shell) {
    const MIN_PX = 138;
    const MAX_RATIO = 0.7;
    let review = getShellReviewPanel();

    function setReviewWidthPx(px) {
        const shellRect = shell.getBoundingClientRect();
        const maxPx = Math.floor(shellRect.width * MAX_RATIO);
        const clamped = Math.max(MIN_PX, Math.min(px, maxPx));
        shell.style.setProperty('--review-width', clamped + 'px');
    }

    function clampReviewWidth() {
        const current = getComputedStyle(shell).getPropertyValue('--review-width').trim();
        if (!current || current.endsWith('%')) return;

        const px = parseFloat(current);
        if (Number.isFinite(px)) setReviewWidthPx(px);
    }

    function isDesktopReviewResizeEnabled() {
        return window.innerWidth > 900 && !!(review && !review.classList.contains('closed'));
    }

    function updateDividerVisibility() {
        review = getShellReviewPanel();
        const reviewOpen = !!(review && !review.classList.contains('closed'));
        shell.dataset.reviewOpen = reviewOpen ? 'true' : 'false';

        if (window.innerWidth <= 900) {
            divider.classList.add('hidden');
            shell.classList.remove('review-open');
            shell.classList.remove('review-closed');
            return;
        }

        shell.classList.toggle('review-open', reviewOpen);
        shell.classList.toggle('review-closed', !reviewOpen);
        divider.classList.toggle('hidden', !reviewOpen);
        if (reviewOpen) clampReviewWidth();
    }

    function handleHtmxUpdate(evt) {
        try {
            const trg = evt && evt.detail && evt.detail.target;
            if (trg && (trg.id === 'review' || (review && review.contains && review.contains(trg)))) {
                const newReview = getShellReviewPanel();
                if (newReview) review = newReview;
            }
        } catch (_) {
        }

        Promise.resolve().then(() => {
            requestAnimationFrame(() => {
                updateDividerVisibility();
            });
        });
    }

    const controller = createPointerResizeController({
        resolveHandle: event => event.target && event.target.closest ? event.target.closest('#panel-divider') : null,
        isEnabled: () => isDesktopReviewResizeEnabled(),
        bodyClass: 'dragging-divider',
        onMove: event => {
            const shellRect = shell.getBoundingClientRect();
            const gapStr = getComputedStyle(shell).getPropertyValue('column-gap') || '0px';
            const gap = parseFloat(gapStr) || 0;
            const dividerRect = divider.getBoundingClientRect();
            const dividerW = Math.max(1, Math.floor(dividerRect.width)) || 3;
            const pointerCenter = event.clientX + (dividerW / 2) + gap;
            setReviewWidthPx(Math.floor(shellRect.right - pointerCenter));
        },
        onEnd: () => {
            clampReviewWidth();
        },
    });

    document.body.addEventListener('htmx:afterSwap', evt => {
        if (controller.isDragging()) controller.cancel();
        handleHtmxUpdate(evt);
    }, true);
    document.body.addEventListener('htmx:afterSettle', handleHtmxUpdate, true);
    window.addEventListener('resize', updateDividerVisibility);

    updateDividerVisibility();
}
