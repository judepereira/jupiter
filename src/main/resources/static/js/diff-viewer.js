const viewers = new WeakMap();
const sourcesByHost = new WeakMap();

function disposeViewer(element) {
    const viewer = viewers.get(element);
    if (!viewer) return;

    viewer.getWrapperElement().remove();
    element.hidden = false;
    viewers.delete(element);
}

function sourcesIn(root) {
    const sources = [];
    if (root.matches?.('#diff-content.diff-source')) sources.push(root);
    root.querySelectorAll?.('#diff-content.diff-source').forEach((source) => sources.push(source));
    if (root.matches?.('.diff-viewer-host')) {
        const source = sourcesByHost.get(root);
        if (source) sources.push(source);
    }
    return [...new Set(sources)];
}

function initDiffViewer(root = document) {
    sourcesIn(root).forEach((element) => {
        if (viewers.has(element) || typeof CodeMirror === 'undefined') return;
        element.hidden = true;
        const host = document.createElement('div');
        host.className = 'diff-viewer-host';
        element.after(host);
        const viewer = CodeMirror(host, {
            value: element.textContent || '',
            mode: 'text/x-diff',
            readOnly: true,
            lineNumbers: true,
            lineWrapping: false,
            tabindex: 0,
        });
        viewer.getWrapperElement().classList.add('diff-viewer');
        viewer.getWrapperElement().setAttribute('role', 'presentation');
        viewers.set(element, viewer);
        sourcesByHost.set(host, element);
    });
}

document.addEventListener('DOMContentLoaded', () => initDiffViewer());
document.addEventListener('htmx:afterSwap', (event) => initDiffViewer(event.target));
document.addEventListener('htmx:beforeCleanupElement', (event) => {
    sourcesIn(event.target).forEach(disposeViewer);
});
