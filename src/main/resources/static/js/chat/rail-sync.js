export function syncFaviconWithRail() {
    try {
        const projectTabs = document.getElementById("project-tabs");
        const failedDotPresent = projectTabs?.dataset.globalFailed === "true";
        const unreadDotPresent = projectTabs?.dataset.globalUnread === "true";
        const favicon32 = document.getElementById("favicon-32x32");
        const favicon16 = document.getElementById("favicon-16x16");
        const topbarLogo = document.getElementById("topbar-logo");
        const base = failedDotPresent ? "/favicon-failed" : unreadDotPresent ? "/favicon-complete" : "/favicon";
        const source32 = base + "-32x32.png";
        if (favicon32) {
            favicon32.setAttribute("href", source32);
        }
        if (favicon16) {
            favicon16.setAttribute("href", base + "-16x16.png");
        }
        if (topbarLogo) {
            topbarLogo.setAttribute("src", source32);
        }
    } catch (error) {
        console.error(error);
    }
}

export function refreshWorkspaceRail() {
    const rail = document.getElementById("workspace-session-rail");
    const tabs = document.querySelector(".project-tabs");
    if (!rail && !tabs) {
        return;
    }
    fetch("/ui/workspaces/rail", { headers: { "HX-Request": "true" } })
        .then((response) => {
            if (!response.ok) {
                throw new Error("Workspace rail refresh failed");
            }
            return response.text();
        })
        .then((html) => {
            const responseRoot = document.createElement("div");
            responseRoot.innerHTML = html;
            const refreshedRail = responseRoot.querySelector("#workspace-session-rail");
            const refreshedTabs = responseRoot.querySelector("#project-tabs");
            const currentRail = document.getElementById("workspace-session-rail");
            const currentTabs = document.querySelector(".project-tabs");
            if (refreshedRail && currentRail) {
                currentRail.outerHTML = refreshedRail.outerHTML;
                if (window.htmx) {
                    window.htmx.process(document.getElementById("workspace-session-rail"));
                }
            }
            if (refreshedTabs && currentTabs) {
                currentTabs.outerHTML = refreshedTabs.outerHTML;
                if (window.htmx) {
                    window.htmx.process(document.getElementById("project-tabs"));
                }
            }
            syncFaviconWithRail();
        })
        .catch((error) => {
            console.error(error);
        });
}

export function initWorkspaceRailSync() {
    if (window.__workspaceRailRefreshSource) {
        return window.__workspaceRailRefreshSource;
    }

    let workspaceRailRefreshTimer = null;

    const scheduleWorkspaceRailRefresh = () => {
        if (workspaceRailRefreshTimer) {
            return;
        }
        workspaceRailRefreshTimer = window.setTimeout(() => {
            workspaceRailRefreshTimer = null;
            refreshWorkspaceRail();
        }, 50);
    };

    const workspaceRailRefreshSource = new EventSource("/ui/workspaces/rail/stream");
    window.__workspaceRailRefreshSource = workspaceRailRefreshSource;
    workspaceRailRefreshSource.addEventListener("workspace-rail-refresh", scheduleWorkspaceRailRefresh);
    workspaceRailRefreshSource.addEventListener("error", (error) => {
        console.error("Workspace rail stream error", error);
        const hasBackendMessage = error && typeof error.data === "string" && error.data.trim();
        if (!hasBackendMessage) {
            window.__connectionLossMonitor && window.__connectionLossMonitor.transportFailure();
        }
    });
    return workspaceRailRefreshSource;
}
