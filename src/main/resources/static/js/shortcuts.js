        if (!window.__appKeyboardShortcutsBound) {
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
        }
