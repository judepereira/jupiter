import {processHtmxElement} from '../shared.js';

function parseToolCallHtmlPatches(event) {
    const raw = event && typeof event.data === 'string' ? event.data : '';
    if (!raw.trim()) {
        throw new Error('tool_call_html event has no payload');
    }

    const patches = JSON.parse(raw);
    if (!Array.isArray(patches)) {
        throw new Error('tool_call_html payload must be an array');
    }
    patches.forEach(validatePatch);
    return patches;
}

function validatePatch(patch) {
    if (!patch || typeof patch !== 'object' || Array.isArray(patch)) {
        throw new Error('tool_call_html contains an invalid patch');
    }
    if (typeof patch.html !== 'string') {
        throw new Error('tool_call_html patch is missing html');
    }
    if (typeof patch.targetId !== 'string' || !patch.targetId.trim()) {
        throw new Error('tool_call_html patch is missing targetId');
    }
    if (patch.swapMode !== 'outerHTML' && patch.swapMode !== 'beforeend') {
        throw new Error('tool_call_html patch has unsupported swapMode: ' + String(patch.swapMode));
    }
}

function openDetails(target) {
    const details = new Map();
    if (target.tagName === 'DETAILS' && target.id && target.open) {
        details.set(target.id, true);
    }
    target.querySelectorAll('details[id][open]').forEach(detail => details.set(detail.id, true));
    return details;
}

function restoreOpenDetails(details) {
    details.forEach((open, id) => {
        const replacement = document.getElementById(id);
        if (replacement && replacement.tagName === 'DETAILS') replacement.open = open;
    });
}

function parseHtml(html) {
    const template = document.createElement('template');
    template.innerHTML = html;
    return template;
}

function applyPatch(patch) {
    const target = document.getElementById(patch.targetId);
    if (!target) {
        throw new Error('tool_call_html target not found: ' + patch.targetId);
    }

    if (patch.swapMode === 'beforeend') {
        const template = parseHtml(patch.html);
        const inserted = Array.from(template.content.children);
        target.append(template.content);
        inserted.forEach(element => {
            processHtmxElement(element);
            element.querySelectorAll('*').forEach(processHtmxElement);
        });
        return;
    }

    const details = openDetails(target);
    target.outerHTML = patch.html;
    const replacement = document.getElementById(patch.targetId);
    if (!replacement) {
        throw new Error('tool_call_html outerHTML replacement removed target: ' + patch.targetId);
    }
    processHtmxElement(replacement);
    replacement.querySelectorAll('*').forEach(processHtmxElement);
    restoreOpenDetails(details);
}

function applyToolCallHtmlPatches(event) {
    parseToolCallHtmlPatches(event).forEach(applyPatch);
}

export {
    applyToolCallHtmlPatches,
    parseToolCallHtmlPatches
};
