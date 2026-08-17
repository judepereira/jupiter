import { createPointerResizeController } from './pointer-resize.js';

const divider = document.getElementById('left-rail-divider');
const shell = document.getElementById('shell');
const leftRail = document.getElementById('left-rail');

if (divider && shell && leftRail) {
    const MIN_PX = 120;
    const MAX_PX = 360;

    function isEnabled() {
        return window.innerWidth > 900;
    }

    function setRailWidthPx(px) {
        const shellRect = shell.getBoundingClientRect();
        const maxPx = Math.min(MAX_PX, Math.floor(shellRect.width * 0.4));
        const clamped = Math.max(MIN_PX, Math.min(px, maxPx));
        shell.style.setProperty('--rail-width', clamped + 'px');
    }

    function clampRailWidth() {
        const current = getComputedStyle(shell).getPropertyValue('--rail-width').trim();
        if (!current || current.endsWith('%')) return;

        const px = parseFloat(current);
        if (Number.isFinite(px)) setRailWidthPx(px);
    }

    function updateVisibility() {
        const enabled = isEnabled();
        divider.classList.toggle('hidden', !enabled);
        shell.classList.toggle('left-rail-open', enabled);
        shell.classList.remove('left-rail-closed');
        if (enabled) clampRailWidth();
    }

    createPointerResizeController({
        resolveHandle: event => event.target && event.target.closest ? event.target.closest('#left-rail-divider') : null,
        isEnabled,
        bodyClass: 'dragging-divider',
        onMove: event => {
            const shellRect = shell.getBoundingClientRect();
            setRailWidthPx(Math.floor(event.clientX - shellRect.left));
        },
        onEnd: () => {
            clampRailWidth();
        },
    });

    window.addEventListener('resize', updateVisibility);
    document.body.addEventListener('htmx:afterSwap', () => {
        if (window.innerWidth <= 900) divider.classList.add('hidden');
    }, true);

    updateVisibility();
}
