export function createPointerResizeController({
    resolveHandle,
    isEnabled = () => true,
    onMove = () => {},
    onEnd = () => {},
    bodyClass = null,
    dragClass = 'dragging',
}) {
    let dragging = false;
    let activePointerId = null;
    let activeHandle = null;

    function beginDrag(event) {
        const handle = resolveHandle(event);
        if (!handle) return;
        if (event.button != null && event.button !== 0) return;
        if (!isEnabled(event, handle)) return;

        dragging = true;
        activePointerId = event.pointerId;
        activeHandle = handle;

        if (bodyClass) document.body.classList.add(bodyClass);
        handle.classList.add(dragClass);

        try {
            handle.setPointerCapture(event.pointerId);
        } catch (_) {
        }

        event.preventDefault();
    }

    function moveDrag(event) {
        if (!dragging || event.pointerId !== activePointerId) return;
        onMove(event, activeHandle);
    }

    function finishDrag(event, force = false) {
        if (!dragging) return;
        if (!force && event && event.pointerId != null && activePointerId != null && event.pointerId !== activePointerId) return;

        const handle = activeHandle;
        const pointerId = event && event.pointerId != null ? event.pointerId : activePointerId;

        dragging = false;
        activePointerId = null;
        activeHandle = null;

        if (bodyClass) document.body.classList.remove(bodyClass);

        if (handle) {
            handle.classList.remove(dragClass);
            if (pointerId != null) {
                try {
                    handle.releasePointerCapture(pointerId);
                } catch (_) {
                }
            }
        }

        onEnd(event, handle);
    }

    document.addEventListener('pointerdown', beginDrag);
    window.addEventListener('pointermove', moveDrag);
    window.addEventListener('pointerup', finishDrag);
    window.addEventListener('pointercancel', finishDrag);

    return {
        isDragging: () => dragging,
        cancel: () => finishDrag(null, true),
    };
}
