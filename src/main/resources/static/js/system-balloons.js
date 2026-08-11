    const root = document.getElementById('system-balloon-root');
    if (root && !window.__systemBalloonSource) {
        const balloons = new Map();
        const MAX_VISIBLE = 5;
        const AUTO_DISMISS_MS = 6000;
        const EXIT_MS = 160;

        function normalizeType(type) {
            const value = String(type || '').trim().toLowerCase();
            if (value === 'error' || value === 'success' || value === 'warning') return value;
            return 'info';
        }

        function parsePayload(data) {
            return JSON.parse(data);
        }

        function removeBalloon(id, animate) {
            const entry = balloons.get(id);
            if (!entry) return;
            balloons.delete(id);
            clearTimeout(entry.timer);

            const node = entry.node;
            if (!node || !node.isConnected) return;
            if (!animate) {
                node.remove();
                return;
            }

            node.classList.add('is-leaving');
            window.setTimeout(() => node.remove(), EXIT_MS);
        }

        function trimBalloons() {
            const children = Array.from(root.children);
            const excess = children.length - MAX_VISIBLE;
            for (let i = 0; i < excess; i++) {
                const oldest = children[children.length - 1 - i];
                if (!oldest) break;
                removeBalloon(oldest.dataset.balloonId, true);
            }
        }

        function createBalloon(payload) {
            if (!payload || payload.id == null) return;

            const id = String(payload.id);
            removeBalloon(id, false);

            const title = String(payload.title ?? '').trim();
            const body = String(payload.body ?? '').trim();
            const type = normalizeType(payload.type);

            const node = document.createElement('div');
            node.className = 'system-balloon ' + type;
            node.dataset.balloonId = id;
            node.dataset.type = type;

            const content = document.createElement('div');
            content.className = 'system-balloon__content';

            if (title) {
                const titleEl = document.createElement('p');
                titleEl.className = 'system-balloon__title';
                titleEl.textContent = title;
                content.appendChild(titleEl);
            }

            const bodyEl = document.createElement('p');
            bodyEl.className = 'system-balloon__body';
            bodyEl.textContent = body;
            content.appendChild(bodyEl);

            const close = document.createElement('button');
            close.type = 'button';
            close.className = 'system-balloon__close';
            close.setAttribute('aria-label', 'Close notification');
            close.textContent = '×';
            close.addEventListener('click', () => removeBalloon(id, true));

            node.appendChild(content);
            node.appendChild(close);
            root.insertBefore(node, root.firstChild);

            requestAnimationFrame(() => node.classList.add('is-visible'));

            const timer = window.setTimeout(() => removeBalloon(id, true), AUTO_DISMISS_MS);
            balloons.set(id, {node, timer});
            trimBalloons();
        }

        const source = new EventSource('/ui/system-balloons/stream');
        window.__systemBalloonSource = source;

        source.addEventListener('balloon', event => {
            try {
                createBalloon(parsePayload(event.data));
            } catch (error) {
                console.error('Failed to parse system balloon', error);
            }
        });

        source.addEventListener('error', error => {
            console.error('System balloon stream error', error);
        });
    }
