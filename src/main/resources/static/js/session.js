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
