export function syncFaviconWithRail() {
    try {
        const unreadDotPresent = !!document.querySelector("#workspace-session-rail .unread-dot");
        const favicon32 = document.getElementById("favicon-32x32");
        const favicon16 = document.getElementById("favicon-16x16");
        const topbarLogo = document.getElementById("topbar-logo");
        const base = unreadDotPresent ? "/favicon-complete" : "/favicon";
        const source32 = base + "-32x32.png";
        if (favicon32) favicon32.setAttribute("href", source32);
        if (favicon16) favicon16.setAttribute("href", base + "-16x16.png");
        if (topbarLogo) topbarLogo.setAttribute("src", source32);
    } catch (error) {
        console.error(error);
    }
}

let railRefreshGeneration = 0;
let chatRefreshGeneration = 0;

export function refreshWorkspaceRail() {
    const rail = document.getElementById("workspace-session-rail");
    if (!rail) return;
    const generation = ++railRefreshGeneration;
    fetch("/ui/workspaces/rail", { headers: { "HX-Request": "true" } })
        .then((response) => {
            if (!response.ok) throw new Error("Workspace rail refresh failed");
            return response.text();
        })
        .then((html) => {
            if (generation !== railRefreshGeneration || !rail.isConnected) return;
            const current = document.getElementById("workspace-session-rail");
            if (current) {
                current.outerHTML = html;
                window.htmx.process(document.getElementById("workspace-session-rail"));
            }
            syncFaviconWithRail();
        })
        .catch((error) => {
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

    const workspaceRailRefreshSource = new EventSource("/ui/workspaces/rail/stream");
    window.__workspaceRailRefreshSource = workspaceRailRefreshSource;
    workspaceRailRefreshSource.addEventListener("workspace-rail-refresh", scheduleWorkspaceRailRefresh);
    workspaceRailRefreshSource.addEventListener("chat-turn-started", (event) => {
        const chat = document.getElementById("chat-container");
        if (!chat || chat.dataset.sessionId !== event.data || chat.dataset.subagentSessionId) return;
        const sessionId = chat.dataset.sessionId;
        const generation = ++chatRefreshGeneration;
        let draftState = null;
        const beforeSwap = (swapEvent) => {
            if (swapEvent.detail.target !== chat) return;
            const current = document.getElementById("chat-container");
            if (generation !== chatRefreshGeneration || current !== chat) {
                swapEvent.detail.shouldSwap = false;
                return;
            }
            const messageInput = chat.querySelector("#chat-send-form [name='message']");
            const draft = messageInput ? messageInput.value : "";
            draftState = {
                wasFocused: document.activeElement === messageInput,
                selectionStart: messageInput && messageInput.selectionStart,
                selectionEnd: messageInput && messageInput.selectionEnd,
                scrollTop: messageInput && messageInput.scrollTop,
            };
            const replacement = new DOMParser()
                .parseFromString(swapEvent.detail.serverResponse, "text/html")
                .getElementById("chat-container");
            if (!replacement || replacement.dataset.sessionId !== sessionId || replacement.dataset.subagentSessionId) {
                swapEvent.detail.shouldSwap = false;
                return;
            }
            const newInput = replacement.querySelector("#chat-send-form [name='message']");
            if (newInput) newInput.textContent = draft;
            swapEvent.detail.serverResponse = replacement.outerHTML;
        };
        document.body.addEventListener("htmx:beforeSwap", beforeSwap);
        window.htmx
            .ajax("GET", "/ui/chat/primary", { target: chat, swap: "outerHTML" })
            .then(() => {
                if (generation !== chatRefreshGeneration || !draftState) return;
                const current = document.getElementById("chat-container");
                const currentInput = current && current.querySelector("#chat-send-form [name='message']");
                if (!currentInput) return;
                if (draftState.wasFocused && document.activeElement === document.body) {
                    currentInput.focus();
                    currentInput.setSelectionRange(draftState.selectionStart, draftState.selectionEnd);
                }
                currentInput.scrollTop = draftState.scrollTop;
            })
            .catch((error) => console.error(error))
            .finally(() => document.body.removeEventListener("htmx:beforeSwap", beforeSwap));
    });
    workspaceRailRefreshSource.addEventListener("error", (error) => {
        console.error("Workspace rail stream error", error);
        const hasBackendMessage = error && typeof error.data === "string" && error.data.trim();
        if (!hasBackendMessage) {
            window.__connectionLossMonitor && window.__connectionLossMonitor.transportFailure();
        }
    });
    return workspaceRailRefreshSource;
}
