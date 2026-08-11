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
