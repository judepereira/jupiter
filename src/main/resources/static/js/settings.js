        function getEnvScope(node) {
            return node && node.closest ? node.closest('[data-settings-env-field]') : null;
        }

        function appendEnvironmentVariableRow(scope) {
            const template = scope && scope.querySelector ? scope.querySelector('[data-settings-env-template]') : null;
            const list = scope && scope.querySelector ? scope.querySelector('[data-settings-env-vars]') : null;
            if (!template || !list || !template.content || !template.content.firstElementChild) return;

            const row = template.content.firstElementChild.cloneNode(true);
            list.appendChild(row);
            const firstInput = row.querySelector('input');
            if (firstInput) firstInput.focus();
        }

        function removeEnvironmentVariableRow(button) {
            const row = button && button.closest ? button.closest('[data-settings-env-row]') : null;
            if (!row) return;
            row.remove();
        }

        function handleEnvironmentVariableClick(event) {
            const target = event.target && event.target.closest ? event.target.closest('[data-settings-env-add], [data-settings-env-remove]') : null;
            if (!target) return;

            const scope = getEnvScope(target);
            if (!scope) return;

            event.preventDefault();
            if (target.matches('[data-settings-env-add]')) {
                appendEnvironmentVariableRow(scope);
                return;
            }
            removeEnvironmentVariableRow(target);
        }

        document.addEventListener('click', handleEnvironmentVariableClick, true);
