export function syncFaviconWithRail() {
    try {
        const unreadDotPresent = !!document.querySelector('#workspace-session-rail .unread-dot');
        const favicon32 = document.getElementById('favicon-32x32');
        const favicon16 = document.getElementById('favicon-16x16');
        const topbarLogo = document.getElementById('topbar-logo');
        const base = unreadDotPresent ? '/favicon-complete' : '/favicon';
        const source32 = base + '-32x32.png';
        if (favicon32) favicon32.setAttribute('href', source32);
        if (favicon16) favicon16.setAttribute('href', base + '-16x16.png');
        if (topbarLogo) topbarLogo.setAttribute('src', source32);
    } catch (error) {
        console.error(error);
    }
}

export function refreshWorkspaceRail() {
    const rail = document.getElementById('workspace-session-rail');
    if (!rail) return;
    fetch('/ui/workspaces/rail', {headers: {'HX-Request': 'true'}})
        .then(response => {
            if (!response.ok) throw new Error('Workspace rail refresh failed');
            return response.text();
        })
        .then(html => {
            rail.outerHTML = html;
            if (window.htmx) window.htmx.process(document.getElementById('workspace-session-rail'));
            syncFaviconWithRail();
        })
        .catch(error => {
            console.error(error);
        });
}

export function initWorkspaceRailSync() {
    if (window.__workspaceRailRefreshSource) return window.__workspaceRailRefreshSource;

    let workspaceRailRefreshTimer = null;

    const scheduleWorkspaceRailRefresh = () => {
        if (workspaceRailRefreshTimer) return;
        workspaceRailRefreshTimer = window.setTimeout(() => {
            workspaceRailRefreshTimer = null;
            refreshWorkspaceRail();
        }, 50);
    };

    const workspaceRailRefreshSource = new EventSource('/ui/workspaces/rail/stream');
    window.__workspaceRailRefreshSource = workspaceRailRefreshSource;
    workspaceRailRefreshSource.addEventListener('workspace-rail-refresh', scheduleWorkspaceRailRefresh);
    workspaceRailRefreshSource.addEventListener('error', error => {
        console.error('Workspace rail stream error', error);
        const hasBackendMessage = error && typeof error.data === 'string' && error.data.trim();
        if (!hasBackendMessage) {
            window.__connectionLossMonitor && window.__connectionLossMonitor.transportFailure();
        }
    });
    return workspaceRailRefreshSource;
}
