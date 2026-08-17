(() => {
    const HEALTH_URL = '/health';
    const HEALTH_TIMEOUT_MS = 2500;
    const HEALTH_POLL_MS = 2000;

    if (window.__connectionLossMonitor) return;

    const state = {
        overlayVisible: false,
        probeInFlight: false,
        reloadInFlight: false,
        timer: null,
        overlay: null,
        refreshButton: null,
        initialProbeStarted: false,
        probeShouldReloadOnHealthy: false
    };

    function getOverlay() {
        if (state.overlay && document.contains(state.overlay)) return state.overlay;
        state.overlay = document.getElementById('connection-loss-overlay');
        state.refreshButton = state.overlay ? state.overlay.querySelector('[data-connection-loss-refresh]') : null;
        if (state.refreshButton && !state.refreshButton.dataset.bound) {
            state.refreshButton.dataset.bound = 'true';
            state.refreshButton.addEventListener('click', () => reloadPage());
        }
        return state.overlay;
    }

    function setOverlayVisible(visible) {
        const overlay = getOverlay();
        if (!overlay) return;
        state.overlayVisible = Boolean(visible);
        overlay.hidden = !visible;
        overlay.setAttribute('aria-hidden', visible ? 'false' : 'true');
        document.documentElement.classList.toggle('connection-loss-overlay-open', visible);
        document.body.classList.toggle('connection-loss-overlay-open', visible);
    }

    function clearTimer() {
        if (state.timer) {
            clearTimeout(state.timer);
            state.timer = null;
        }
    }

    function scheduleProbe(delay = HEALTH_POLL_MS) {
        if (state.timer) return;
        state.timer = window.setTimeout(() => {
            state.timer = null;
            probeHealth();
        }, delay);
    }

    async function probeHealth() {
        if (state.probeInFlight || state.reloadInFlight) return;
        state.probeInFlight = true;
        const controller = new AbortController();
        const timeoutId = window.setTimeout(() => controller.abort(), HEALTH_TIMEOUT_MS);

        try {
            const response = await fetch(HEALTH_URL, {
                method: 'GET',
                cache: 'no-store',
                headers: {
                    'Cache-Control': 'no-cache',
                    'Pragma': 'no-cache'
                },
                signal: controller.signal
            });
            if (response.ok) {
                if (state.probeShouldReloadOnHealthy) {
                    reloadPage();
                    return;
                }
                setOverlayVisible(false);
                clearTimer();
                return;
            }
            showOverlayAndPoll();
        } catch (_) {
            showOverlayAndPoll();
        } finally {
            window.clearTimeout(timeoutId);
            state.probeInFlight = false;
        }
    }

    function showOverlayAndPoll() {
        setOverlayVisible(true);
        state.probeShouldReloadOnHealthy = true;
        scheduleProbe(HEALTH_POLL_MS);
    }

    function reloadPage() {
        if (state.reloadInFlight) return;
        state.reloadInFlight = true;
        state.probeShouldReloadOnHealthy = false;
        clearTimer();
        try {
            window.location.reload();
        } catch (_) {
            state.reloadInFlight = false;
        }
    }

    function transportFailure() {
        state.probeShouldReloadOnHealthy = true;
        if (state.overlayVisible) {
            if (state.probeInFlight || state.timer) return;
            scheduleProbe(0);
            return;
        }
        probeHealth();
    }

    const api = {
        transportFailure,
        showOverlayAndPoll,
        probeHealth,
        reloadPage
    };
    window.connectionLossMonitor = api;
    window.__connectionLossMonitor = api;

    function startInitialProbe() {
        if (state.initialProbeStarted) return;
        state.initialProbeStarted = true;
        getOverlay();
        probeHealth();
    }

    document.addEventListener('htmx:sendError', transportFailure, true);
    document.addEventListener('htmx:timeout', transportFailure, true);

    window.addEventListener('online', () => {
        if (state.overlayVisible) probeHealth();
    });
    if (document.readyState === 'complete' || document.readyState === 'interactive') {
        startInitialProbe();
    } else {
        document.addEventListener('DOMContentLoaded', startInitialProbe, {once: true});
    }
})();
