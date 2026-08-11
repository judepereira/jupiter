    function isCreateMode(form) {
        const selected = form && form.querySelector('[data-workspace-branch-mode]:checked');
        return !selected || selected.value === 'create';
    }

    function sanitizeBranchName(value) {
        return String(value || '')
            .replace(/[\u0000-\u001f\u007f\\ ~^:?*\[\]\s]+/g, '-')
            .replace(/@\{/g, '-')
            .replace(/\.\.+/g, '-')
            .replace(/(?:^|\/)\.lock(?=\/|$)/g, '-lock')
            .replace(/\.lock(?=\/|$)/g, '-lock')
            .replace(/\.+(?=\/|$)/g, '-');
    }

    function sanitizeInput(input) {
        const form = input.closest('form');
        if (!isCreateMode(form)) return;
        const sanitized = sanitizeBranchName(input.value);
        if (input.value !== sanitized) {
            input.value = sanitized;
        }
    }

    function initWorkspaceBranchSanitizer(root) {
        const scope = root || document;
        scope.querySelectorAll('[data-workspace-branch-name]').forEach(input => {
            if (input.dataset.workspaceBranchSanitizerBound === '1') return;
            input.dataset.workspaceBranchSanitizerBound = '1';
            input.addEventListener('input', () => sanitizeInput(input));
            input.addEventListener('change', () => sanitizeInput(input));
        });
    }

    document.addEventListener('input', event => {
        const input = event.target && event.target.closest ? event.target.closest('[data-workspace-branch-name]') : null;
        if (input) sanitizeInput(input);
    });
    document.addEventListener('change', event => {
        const mode = event.target && event.target.closest ? event.target.closest('[data-workspace-branch-mode]') : null;
        if (!mode || mode.value !== 'create') return;
        const form = mode.closest('form');
        const input = form && form.querySelector('[data-workspace-branch-name]');
        if (input) sanitizeInput(input);
    });
    document.body.addEventListener('htmx:afterSwap', event => initWorkspaceBranchSanitizer(event.target), true);
    initWorkspaceBranchSanitizer(document);
