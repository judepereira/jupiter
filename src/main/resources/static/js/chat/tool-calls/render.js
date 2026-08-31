import {clearToolCallImages, getToolCallContainer, getToolCallGroups, getDirectToolCallChild, bindDynamicSubagentButton} from './dom.js';
import {canCoalesceToolCallGroup, findToolCallEntry, rememberToolCallIdentity, toolCallKey, toolCallRegistryScope, updateToolCallGroupKind, isSpecialStandaloneToolCall, toolCallGroupKind, registerToolCallEntry} from './identity.js';
import {taskToolCallBody, toolCallInputText, toolCallOutputText, toolCallStatusText} from './payload.js';
import {buildToolCallBundleRefs, buildToolCallGroupRefs, buildToolCallCallRefs, createToolCallGroup, getOrCreateToolCallBundleCallsContainer, getToolCallBundle, refreshToolCallBundleLabel, refreshToolCallGroupSummary, toolCallGroupSummaryText} from './groups.js';

export function createToolCallCall(groupRefs, payload, processHtmxElementFn) {
    try {
        if (!groupRefs || !groupRefs.callsContainer) return null;

        const call = document.createElement('div');
        call.className = 'tool-call-call';
        groupRefs.callsContainer.appendChild(call);

        const refs = buildToolCallCallRefs(call, processHtmxElementFn);
        if (!refs) return null;

        const toolCallId = payload && payload.toolCallId != null ? String(payload.toolCallId).trim() : '';
        const toolName = payload && payload.toolName != null ? String(payload.toolName).trim() : 'tool';
        const key = toolCallKey(payload);

        rememberToolCallIdentity(call, toolCallId, key);
        call.dataset.toolCallGroupKind = toolCallGroupKind(toolName);
        registerToolCallEntry(toolCallRegistryScope(groupRefs.group || call), call, toolCallId, key);
        rememberToolCallIdentity(refs.details, toolCallId, key);
        refs.details.dataset.toolCallToolName = toolName;
        refs.details.dataset.toolCallState = 'running';
        refs.details.dataset.toolCallSuccess = 'false';

        refreshToolCallGroupSummary(groupRefs);
        return {group: groupRefs.group, summary: groupRefs.summary, nameSpan: groupRefs.nameSpan, statusSpan: groupRefs.statusSpan, detail: refs.detail, details: refs.details, subagent: refs.subagent, button: refs.button, inputPre: refs.inputPre, outputSection: refs.outputSection, outputPre: refs.outputPre, imageFigure: refs.imageFigure, nestedCalls: refs.nestedCalls};
    } catch (_) {
        return null;
    }
}

export function buildToolCallEntry(entry, processHtmxElementFn) {
    try {
        if (!entry || !entry.classList || !entry.classList.contains('tool-call-call')) return null;
        const group = entry.closest('details.tool-call');
        const groupRefs = buildToolCallGroupRefs(group);
        const callRefs = buildToolCallCallRefs(entry, processHtmxElementFn);
        if (!groupRefs || !callRefs) return null;
        return {group: groupRefs.group, summary: groupRefs.summary, nameSpan: groupRefs.nameSpan, statusSpan: groupRefs.statusSpan, detail: callRefs.detail, details: callRefs.details, subagent: callRefs.subagent, button: callRefs.button, inputPre: callRefs.inputPre, outputSection: callRefs.outputSection, outputPre: callRefs.outputPre, imageFigure: callRefs.imageFigure, nestedCalls: callRefs.nestedCalls};
    } catch (_) {
        return null;
    }
}

export function ensureToolCallEntry(target, payload, processHtmxElementFn) {
    try {
        const container = getToolCallContainer(target);
        if (!container) return null;

        const toolCallId = payload && payload.toolCallId != null ? String(payload.toolCallId).trim() : '';
        const toolName = payload && payload.toolName != null ? String(payload.toolName).trim() : 'tool';

        const existingEntry = findToolCallEntry(container, payload);
        const existing = existingEntry ? buildToolCallEntry(existingEntry, processHtmxElementFn) : null;
        if (existing) {
            if (payload && payload.imageUrl) {
                clearToolCallImages(existingEntry);
            }
            refreshToolCallGroupSummary({group: existing.group, summary: existing.summary, nameSpan: existing.nameSpan, statusSpan: existing.statusSpan, callsContainer: existing.group ? getDirectToolCallChild(getDirectToolCallChild(existing.group, 'tool-call-detail'), 'tool-call-calls') : null});
            return existing;
        }

        const bundle = getToolCallBundle(container);
        let bundleRefs = bundle ? buildToolCallBundleRefs(bundle) : null;
        const groupContainer = isSpecialStandaloneToolCall(toolName)
            ? container
            : (bundleRefs ? bundleRefs.callsContainer : getOrCreateToolCallBundleCallsContainer(container));
        if (!groupContainer) return null;
        if (!bundleRefs && !isSpecialStandaloneToolCall(toolName)) {
            const createdBundle = getToolCallBundle(container);
            bundleRefs = createdBundle ? buildToolCallBundleRefs(createdBundle) : null;
        }

        const groups = getToolCallGroups(groupContainer);
        let groupRefs = null;
        if (groups.length > 0) {
            const lastGroup = groups[groups.length - 1];
            if (canCoalesceToolCallGroup(lastGroup, toolName) && !lastGroup.classList.contains('tool-call-bundle')) {
                groupRefs = buildToolCallGroupRefs(lastGroup);
            }
        }

        if (!groupRefs) {
            groupRefs = createToolCallGroup(groupContainer, toolName);
        }

        if (!groupRefs) return null;
        const entry = createToolCallCall(groupRefs, payload, processHtmxElementFn);
        if (bundleRefs) refreshToolCallBundleLabel(bundleRefs);
        return entry;
    } catch (_) {
        return null;
    }
}

export function updateToolCallEntry(entry, payload, options, processHtmxElementFn) {
    try {
        if (!entry || !entry.details) return;

        const details = entry.details;
        const group = entry.group || details.closest('details.tool-call');
        const toolName = options && options.toolName != null ? String(options.toolName) : (payload && payload.toolName != null ? String(payload.toolName) : 'tool');
        const inputText = options && Object.prototype.hasOwnProperty.call(options, 'inputText') ? String(options.inputText ?? '') : toolCallInputText(payload);
        const outputText = options && Object.prototype.hasOwnProperty.call(options, 'outputText') ? String(options.outputText ?? '') : toolCallOutputText(payload);
        const state = options && options.state != null ? String(options.state) : (payload && payload.success != null ? (payload.success ? 'done' : 'error') : 'running');
        const statusText = options && options.statusText != null ? String(options.statusText) : toolCallStatusText(state, payload && payload.success);
        const success = options && options.success != null ? Boolean(options.success) : Boolean(payload && payload.success);

        details.dataset.toolCallToolName = toolName;
        details.dataset.toolCallState = state;
        details.dataset.toolCallSuccess = success ? 'true' : 'false';
        updateToolCallGroupKind(group, toolName);
        rememberToolCallIdentity(details, payload && payload.toolCallId != null ? String(payload.toolCallId).trim() : '', toolCallKey(payload));

        entry.nameSpan.textContent = toolName;
        entry.statusSpan.className = 'tool-call-status';
        if (state === 'done' || success) entry.statusSpan.classList.add('tool-call-status-success');
        if (state === 'error' || (payload && payload.success === false)) entry.statusSpan.classList.add('tool-call-status-failure');
        entry.statusSpan.textContent = statusText;

        if (Object.prototype.hasOwnProperty.call(options || {}, 'inputText')) {
            entry.inputPre.textContent = inputText;
        } else if (entry.inputPre && !entry.inputPre.textContent && inputText) {
            entry.inputPre.textContent = inputText;
        }

        const imageUrl = options && options.imageUrl != null ? String(options.imageUrl) : (payload && payload.imageUrl != null ? String(payload.imageUrl) : '');
        const imageAlt = options && options.imageAlt != null ? String(options.imageAlt) : (payload && payload.imageAlt != null ? String(payload.imageAlt) : '');
        const imagePath = options && options.imagePath != null ? String(options.imagePath) : (payload && payload.imagePath != null ? String(payload.imagePath) : '');
        const imageMediaType = options && options.imageMediaType != null ? String(options.imageMediaType) : (payload && payload.imageMediaType != null ? String(payload.imageMediaType) : '');
        const isImage = Boolean(imageUrl);

        if (isImage && entry.outputPre) {
            entry.outputPre.remove();
            entry.outputPre = null;
        } else if (entry.outputPre) {
            if (Object.prototype.hasOwnProperty.call(options || {}, 'outputText')) {
                entry.outputPre.textContent = outputText;
            } else if (outputText && !entry.outputPre.textContent) {
                entry.outputPre.textContent = outputText;
            }
        }
        if (!isImage && entry.imageFigure) {
            entry.imageFigure.remove();
            entry.imageFigure = null;
        }

        if (isImage) {
            if (!entry.imageFigure) {
                entry.imageFigure = document.createElement('figure');
                entry.imageFigure.className = 'tool-call-image-preview';
                entry.detail.appendChild(entry.imageFigure);
            }
            let img = entry.imageFigure.querySelector('img');
            if (!img) {
                img = document.createElement('img');
                entry.imageFigure.appendChild(img);
            }
            img.src = imageUrl;
            img.alt = imageAlt;
            let caption = entry.imageFigure.querySelector('figcaption');
            if (!caption) {
                caption = document.createElement('figcaption');
                entry.imageFigure.appendChild(caption);
            }
            let captionText = caption.querySelector('.tool-call-image-caption');
            if (!captionText) {
                captionText = document.createElement('span');
                captionText.className = 'tool-call-image-caption';
                caption.appendChild(captionText);
            }
            captionText.textContent = imageAlt;
            Array.from(caption.querySelectorAll('br, small')).forEach(node => node.remove());
            if (imagePath) {
                const small = document.createElement('small');
                small.textContent = imagePath;
                caption.appendChild(document.createElement('br'));
                caption.appendChild(small);
            }
            details.open = true;
            if (entry.group) entry.group.open = true;
            entry.imageFigure.dataset.imageMediaType = imageMediaType || '';
        } else if (entry.imageFigure) {
            entry.imageFigure.remove();
            entry.imageFigure = null;
        }

        if (options && options.appendOutputText != null && entry.outputPre) {
            entry.outputPre.textContent = (entry.outputPre.textContent || '') + String(options.appendOutputText);
        }

        const payloadTaskBody = taskToolCallBody(payload);
        const subagentSessionId = options && options.subagentSessionId != null ? String(options.subagentSessionId) : (payload && payload.subagentSessionId != null ? String(payload.subagentSessionId) : '');
        const subagentAgentName = options && options.subagentAgentName != null ? String(options.subagentAgentName) : (payload && payload.subagentAgentName != null ? String(payload.subagentAgentName) : '');
        const taskBody = payloadTaskBody || (options && options.taskBody != null ? String(options.taskBody) : '');

        if (toolName === 'task') {
            details.classList.add('task-tool-call');
            if (group) group.classList.add('task-tool-call');
            if (taskBody) {
                details.dataset.toolCallTaskBody = taskBody;
                if (group) group.dataset.toolCallTaskBody = taskBody;
            } else if (group && !group.dataset.toolCallTaskBody && details.dataset.toolCallTaskBody) {
                group.dataset.toolCallTaskBody = details.dataset.toolCallTaskBody;
            }
            if (subagentSessionId) {
                details.dataset.toolCallSubagentSessionId = subagentSessionId;
                details.dataset.toolCallSubagentAgentName = subagentAgentName || subagentSessionId;
                if (group) {
                    group.dataset.toolCallSubagentSessionId = subagentSessionId;
                    group.dataset.toolCallSubagentAgentName = subagentAgentName || subagentSessionId;
                }
            }
            const groupTaskBody = group && group.dataset ? String(group.dataset.toolCallTaskBody || '') : '';
            const groupSubagentSessionId = group && group.dataset ? String(group.dataset.toolCallSubagentSessionId || '').trim() : '';
            const groupSubagentAgentName = group && group.dataset ? String(group.dataset.toolCallSubagentAgentName || '') : '';
            const effectiveTaskBody = taskBody || groupTaskBody || String(details.dataset.toolCallTaskBody || '');
            const effectiveSubagentSessionId = subagentSessionId || groupSubagentSessionId || String(details.dataset.toolCallSubagentSessionId || '').trim();
            const effectiveSubagentAgentName = subagentAgentName || groupSubagentAgentName || String(details.dataset.toolCallSubagentAgentName || '') || effectiveSubagentSessionId || 'task';
            const groupRefs = buildToolCallGroupRefs(group);
            if (groupRefs) {
                if (groupRefs.nameSpan) groupRefs.nameSpan.textContent = effectiveSubagentAgentName;
                if (groupRefs.taskBody) {
                    groupRefs.taskBody.textContent = effectiveTaskBody;
                    groupRefs.taskBody.hidden = !String(effectiveTaskBody || '').trim();
                }
                if (groupRefs.button) {
                    if (effectiveSubagentSessionId) {
                        groupRefs.button.hidden = false;
                        groupRefs.button.setAttribute('hx-get', '/ui/chat/subagent/' + encodeURIComponent(effectiveSubagentSessionId));
                        groupRefs.button.setAttribute('hx-target', '#chat-container');
                        groupRefs.button.setAttribute('hx-swap', 'outerHTML');
                        groupRefs.button.textContent = 'View Session';
                        bindDynamicSubagentButton(groupRefs.button);
                    } else {
                        groupRefs.button.hidden = true;
                    }
                }
                if (groupRefs.statusSpan) {
                    groupRefs.statusSpan.className = 'tool-call-status';
                    if (state === 'done' || success) groupRefs.statusSpan.classList.add('tool-call-status-success');
                    if (state === 'error' || (payload && payload.success === false)) groupRefs.statusSpan.classList.add('tool-call-status-failure');
                    groupRefs.statusSpan.textContent = statusText;
                }
            }
            if (groupRefs) {
                refreshToolCallGroupSummary({group: groupRefs.group, summary: groupRefs.summary, nameSpan: groupRefs.nameSpan, statusSpan: groupRefs.statusSpan, taskBody: groupRefs.taskBody, button: groupRefs.button, callsContainer: groupRefs.callsContainer});
            }
            return;
        }

        if (subagentSessionId) {
            if (!entry.subagent) {
                entry.subagent = document.createElement('div');
                entry.subagent.className = 'tool-call-subagent';
                entry.detail.insertBefore(entry.subagent, getDirectToolCallChild(entry.detail, 'tool-call-section') || getDirectToolCallChild(entry.detail, 'tool-calls') || null);
            }
            if (!entry.button) {
                entry.button = document.createElement('button');
                entry.button.type = 'button';
                entry.button.className = 'btn btn-outline-primary btn-sm d-inline-flex align-items-center gap-1 tool-call-subagent-button';
                entry.button.dataset.dynamicSubagentButton = 'true';
                entry.subagent.appendChild(entry.button);
            }
            entry.subagent.dataset.childSessionId = subagentSessionId;
            entry.button.setAttribute('hx-get', '/ui/chat/subagent/' + encodeURIComponent(subagentSessionId));
            entry.button.setAttribute('hx-target', '#chat-container');
            entry.button.setAttribute('hx-swap', 'outerHTML');
            entry.button.textContent = 'View Session';
            bindDynamicSubagentButton(entry.button);
        }

        if (group) {
            refreshToolCallGroupSummary({group: group, summary: entry.summary, nameSpan: entry.nameSpan, statusSpan: entry.statusSpan, callsContainer: getDirectToolCallChild(getDirectToolCallChild(group, 'tool-call-detail'), 'tool-call-calls')});
            const bundle = group.closest && group.closest('details.tool-call-bundle');
            if (bundle) refreshToolCallBundleLabel(buildToolCallBundleRefs(bundle));
        }
    } catch (_) {
    }
}

export function appendToolCallToChatRow(target, payload, processHtmxElementFn, options) {
    try {
        const entry = ensureToolCallEntry(target, payload, processHtmxElementFn);
        if (!entry) return null;
        updateToolCallEntry(entry, payload, options || {}, processHtmxElementFn);
        return entry;
    } catch (_) {
        return null;
    }
}

export {buildToolCallBundleRefs, buildToolCallGroupRefs, buildToolCallCallRefs, createToolCallGroup, getOrCreateToolCallBundleCallsContainer, getToolCallBundle, refreshToolCallBundleLabel, refreshToolCallGroupSummary, toolCallGroupSummaryText};
