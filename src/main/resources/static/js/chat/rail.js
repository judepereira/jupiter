import {syncFaviconWithRail} from './dom.js';

let workspaceRailRefreshTimer = null;
let workspaceRailSourceBound = false;

function refreshWorkspaceRail() {
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

export function bindWorkspaceRailRefresh() {
    if (workspaceRailSourceBound) return;
    workspaceRailSourceBound = true;

    if (!window.__workspaceRailRefreshSource) {
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
    }
}

export {refreshWorkspaceRail};
