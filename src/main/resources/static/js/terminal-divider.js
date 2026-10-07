import { createPointerResizeController } from "./pointer-resize.js";

const controller = createPointerResizeController({
    resolveHandle: (event) =>
        event.target && event.target.closest ? event.target.closest("#terminal-panel-divider") : null,
    isEnabled: () => {
        const shell = document.getElementById("shell");
        const bottomPanel = document.getElementById("bottom-panel");
        if (!shell || !bottomPanel || bottomPanel.classList.contains("closed")) return false;
        if (shell.getBoundingClientRect().height <= 280) return false;
        return true;
    },
    bodyClass: "dragging-divider",
    onMove: (event) => {
        const shell = document.getElementById("shell");
        const bottomPanel = document.getElementById("bottom-panel");
        if (!shell || !bottomPanel) return;

        const shellRect = shell.getBoundingClientRect();
        bottomPanel.style.setProperty(
            "--terminal-panel-height",
            Math.max(160, Math.min(shellRect.bottom - event.clientY, Math.floor(shellRect.height - 120))) + "px",
        );
    },
    onEnd: () => {
        const shell = document.getElementById("shell");
        const bottomPanel = document.getElementById("bottom-panel");
        if (!shell || !bottomPanel) return;
        const height = parseFloat(getComputedStyle(bottomPanel).getPropertyValue("--terminal-panel-height"));
        if (Number.isFinite(height)) {
            fetch("/ui/panel/height", {
                method: "POST",
                headers: { "Content-Type": "application/x-www-form-urlencoded" },
                body: "height=" + encodeURIComponent(Math.round(height)),
            });
        }
    },
});

function clampTerminalPanelHeight() {
    const shell = document.getElementById("shell");
    const bottomPanel = document.getElementById("bottom-panel");
    if (!shell || !bottomPanel) return;

    const current = getComputedStyle(bottomPanel).getPropertyValue("--terminal-panel-height").trim();
    if (!current || current.endsWith("%")) return;

    const px = parseFloat(current);
    if (!Number.isFinite(px)) return;

    const shellRect = shell.getBoundingClientRect();
    const minPx = 160;
    const maxPx = Math.floor(shellRect.height - 120);
    if (maxPx < minPx) return;
    bottomPanel.style.setProperty("--terminal-panel-height", Math.max(minPx, Math.min(px, maxPx)) + "px");
}

window.addEventListener("resize", clampTerminalPanelHeight);
document.body.addEventListener(
    "htmx:afterSwap",
    () => {
        if (controller.isDragging()) controller.cancel();
    },
    true,
);
