        const mounts = new Map();
        let syncQueued = false;
        const lightTerminalTheme = {
            background: '#ffffff',
            foreground: '#1f2937',
            cursor: '#334155',
            selectionBackground: '#dbeafe',
            black: '#111827',
            red: '#dc2626',
            green: '#16a34a',
            yellow: '#ca8a04',
            blue: '#2563eb',
            magenta: '#7c3aed',
            cyan: '#0891b2',
            white: '#e5e7eb',
            brightBlack: '#374151',
            brightRed: '#ef4444',
            brightGreen: '#22c55e',
            brightYellow: '#eab308',
            brightBlue: '#3b82f6',
            brightMagenta: '#8b5cf6',
            brightCyan: '#06b6d4',
            brightWhite: '#f9fafb'
        };

        function toWebSocketUrl(rawUrl) {
            if (!rawUrl) return '';
            try {
                const url = new URL(rawUrl, window.location.href);
                if (url.protocol === 'http:') url.protocol = 'ws:';
                else if (url.protocol === 'https:') url.protocol = 'wss:';
                return url.toString();
            } catch (_) {
                return rawUrl;
            }
        }

        function disposeMount(mount) {
            const entry = mounts.get(mount);
            if (!entry) return;
            mounts.delete(mount);
            try {
                entry.resizeObserver && entry.resizeObserver.disconnect();
            } catch (_) {
            }
            try {
                entry.socket.close();
            } catch (_) {
            }
            try {
                entry.terminal.dispose();
            } catch (_) {
            }
        }

        function fitEntry(entry) {
            if (!entry || !document.contains(entry.mount)) return;
            entry.fitAddon.fit();
            const cols = Math.floor(entry.terminal.cols);
            const rows = Math.floor(entry.terminal.rows);
            const resizeSignature = cols + 'x' + rows;
            if (entry.socket.readyState === WebSocket.OPEN && resizeSignature !== entry.lastSentResizeSignature) {
                entry.socket.send(JSON.stringify({type: 'resize', cols, rows}));
                entry.lastSentResizeSignature = resizeSignature;
            }
        }

        function scheduleInitialFit(entry) {
            requestAnimationFrame(() => {
                requestAnimationFrame(() => {
                    fitEntry(entry);
                });
            });
        }

        function scheduleInitialFocus(entry) {
            requestAnimationFrame(() => {
                requestAnimationFrame(() => {
                    if (!mounts.has(entry.mount) || entry.hasFocused) return;
                    entry.terminal.focus();
                    entry.hasFocused = true;
                });
            });
        }

        function syncTerminals() {
            syncQueued = false;

            for (const mount of mounts.keys()) {
                if (!document.contains(mount)) {
                    disposeMount(mount);
                }
            }

            const terminalMounts = document.querySelectorAll('.terminal-mount[data-terminal-id][data-ws-url]');
            terminalMounts.forEach(mount => {
                if (!mounts.has(mount)) {
                    initMount(mount);
                }
                fitEntry(mounts.get(mount));
            });
        }

        function queueSync() {
            if (syncQueued) return;
            syncQueued = true;
            Promise.resolve().then(() => requestAnimationFrame(syncTerminals));
        }

        function initMount(mount) {
            if (!window.Terminal || !window.FitAddon || !window.FitAddon.FitAddon) return;

            const terminalId = mount.dataset.terminalId;
            const wsUrl = toWebSocketUrl(mount.dataset.wsUrl);
            if (!terminalId || !wsUrl) return;

            const terminal = new window.Terminal({
                cursorBlink: true,
                convertEol: true,
                fontFamily: 'ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", monospace',
                fontSize: 13,
                theme: lightTerminalTheme
            });
            const fitAddon = new window.FitAddon.FitAddon();
            terminal.loadAddon(fitAddon);
            terminal.open(mount);

            const socket = new WebSocket(wsUrl);
            let expectedClose = false;
            const entry = {mount, terminal, fitAddon, socket, resizeObserver: null, lastSentResizeSignature: '', hasFocused: false};
            mounts.set(mount, entry);

            socket.addEventListener('open', () => {
                scheduleInitialFit(entry);
                scheduleInitialFocus(entry);
            });

            const resizeObserver = new ResizeObserver(() => queueSync());
            entry.resizeObserver = resizeObserver;
            resizeObserver.observe(mount);
            const bottomPanel = mount.closest('.bottom-panel');
            if (bottomPanel) resizeObserver.observe(bottomPanel);

            terminal.onData(data => {
                try {
                    if (socket.readyState === WebSocket.OPEN) {
                        socket.send(JSON.stringify({type: 'input', data}));
                    }
                } catch (_) {
                }
            });

            socket.addEventListener('message', evt => {
                try {
                    const payload = JSON.parse(evt.data);
                    if (!payload || typeof payload !== 'object') return;
                    if (payload.type === 'output' && payload.data != null) {
                        terminal.write(String(payload.data));
                    } else if (payload.type === 'exit') {
                        terminal.writeln('');
                        terminal.writeln(`[terminal exited with code ${payload.code ?? 'unknown'}]`);
                        expectedClose = true;
                        socket.close();
                    } else if (payload.type === 'error') {
                        terminal.writeln('');
                        terminal.writeln(`[terminal error] ${payload.message ?? 'Terminal error'}`);
                        expectedClose = true;
                        socket.close();
                    }
                } catch (_) {
                }
            });
            socket.addEventListener('close', event => {
                if (event && event.wasClean) return;
                if (expectedClose) return;
                try {
                    terminal.writeln('');
                    terminal.writeln('[terminal connection error]');
                } catch (_) {
                }
                window.__connectionLossMonitor && window.__connectionLossMonitor.transportFailure();
            });
            socket.addEventListener('error', () => {
                if (expectedClose) return;
                try {
                    terminal.writeln('');
                    terminal.writeln('[terminal connection error]');
                } catch (_) {
                }
                window.__connectionLossMonitor && window.__connectionLossMonitor.transportFailure();
            });
        }

        queueSync();
        window.addEventListener('resize', queueSync);
        document.body.addEventListener('htmx:afterSwap', queueSync, true);
        document.body.addEventListener('htmx:afterSettle', queueSync, true);
