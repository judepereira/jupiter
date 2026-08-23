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

        function getMcpScope(node) {
            return node && node.closest ? node.closest('[data-settings-mcp-field]') : null;
        }

        function getMcpList(scope) {
            return scope && scope.querySelector ? scope.querySelector('[data-settings-mcp-list]') : null;
        }

        function getMcpServerTemplate(scope) {
            return scope && scope.querySelector ? scope.querySelector('[data-settings-mcp-server-template]') : null;
        }

        function getMcpHeaderTemplate(server) {
            return server && server.querySelector ? server.querySelector('[data-settings-mcp-header-template]') : null;
        }

        function appendMcpServer(scope) {
            const template = getMcpServerTemplate(scope);
            const list = getMcpList(scope);
            if (!template || !list || !template.content || !template.content.firstElementChild) return;

            const server = template.content.firstElementChild.cloneNode(true);
            list.appendChild(server);
            const input = server.querySelector('[data-mcp-server-name]');
            if (input) input.focus();
        }

        function removeMcpServer(button) {
            const server = button && button.closest ? button.closest('[data-settings-mcp-server]') : null;
            if (!server) return;
            server.remove();
        }

        function appendMcpHeader(button) {
            const server = button && button.closest ? button.closest('[data-settings-mcp-server]') : null;
            if (!server) return;
            const template = getMcpHeaderTemplate(server);
            const list = server.querySelector ? server.querySelector('[data-settings-mcp-header-list]') : null;
            if (!template || !list || !template.content || !template.content.firstElementChild) return;

            const row = template.content.firstElementChild.cloneNode(true);
            list.appendChild(row);
            const input = row.querySelector('[data-mcp-server-header-name]');
            if (input) input.focus();
        }

        function removeMcpHeader(button) {
            const row = button && button.closest ? button.closest('[data-mcp-server-header-row]') : null;
            if (!row) return;
            row.remove();
        }

        function serializeMcpCatalog(form) {
            const servers = [];
            const rows = form && form.querySelectorAll ? form.querySelectorAll('[data-settings-mcp-server]') : [];
            rows.forEach(function (server) {
                const idField = server.dataset.mcpServerId;
                const name = server.querySelector('[data-mcp-server-name]')?.value ?? '';
                const url = server.querySelector('[data-mcp-server-url]')?.value ?? '';
                const enabled = !!server.querySelector('[data-mcp-server-enabled]')?.checked;
                const exposedProjectIds = Array.from(server.querySelectorAll('[data-mcp-server-projects] option:checked')).map(function (option) {
                    return Number(option.value);
                });
                const headers = Array.from(server.querySelectorAll('[data-mcp-server-header-row]')).map(function (row) {
                    return {
                        name: row.querySelector('[data-mcp-server-header-name]')?.value ?? '',
                        value: row.querySelector('[data-mcp-server-header-value]')?.value ?? ''
                    };
                });

                const serverData = {
                    name: name,
                    url: url,
                    enabled: enabled,
                    exposedProjectIds: exposedProjectIds,
                    headers: headers
                };
                if (idField) {
                    serverData.id = Number(idField);
                }
                servers.push(serverData);
            });
            return JSON.stringify({servers: servers});
        }

        function handleMcpClick(event) {
            const target = event.target && event.target.closest ? event.target.closest('[data-settings-mcp-add-server], [data-settings-mcp-remove-server], [data-settings-mcp-add-header], [data-settings-mcp-remove-header]') : null;
            if (!target) return;

            const scope = getMcpScope(target);
            if (!scope) return;

            event.preventDefault();
            if (target.matches('[data-settings-mcp-add-server]')) {
                appendMcpServer(scope);
                return;
            }
            if (target.matches('[data-settings-mcp-remove-server]')) {
                removeMcpServer(target);
                return;
            }
            if (target.matches('[data-settings-mcp-add-header]')) {
                appendMcpHeader(target);
                return;
            }
            removeMcpHeader(target);
        }

        function handleMcpFormSubmit(event) {
            const form = event.target && event.target.matches ? event.target : null;
            if (!form || !form.matches('[data-settings-mcp-form]')) return;
            const hidden = form.querySelector('input[name="mcpCatalogJson"]');
            if (hidden) hidden.value = serializeMcpCatalog(form);
        }

        document.addEventListener('click', handleEnvironmentVariableClick, true);
        document.addEventListener('click', handleMcpClick, true);
        document.addEventListener('submit', handleMcpFormSubmit, true);
