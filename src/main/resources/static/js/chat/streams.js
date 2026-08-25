import {processHtmxElement} from './dom.js';
import {renderAllChatMarkdown, renderChatMarkdown, getRawChatMarkdown, ensureChatMessageSubtitle, updateChatRowCompletion, formatAllChatSubtitles} from './markdown.js';
import {syncFaviconWithRail} from './dom.js';
import {initChatComposer} from './composer.js';
import {bindWorkspaceRailRefresh, refreshWorkspaceRail} from './rail.js';

let activePendingStreams = new Map();
let stopRequestInFlight = false;
let streamsBound = false;

export function getCurrentOpenSubagentSessionId() {
    try {
        const container = document.getElementById('chat-container');
        const sessionId = container && container.dataset && container.dataset.subagentSessionId != null ? String(container.dataset.subagentSessionId).trim() : '';
        return sessionId || '';
    } catch (_) {
        return '';
    }
}

export function getOpenSubagentPendingRow(childSessionId) {
    try {
        if (!childSessionId) return null;
        if (getCurrentOpenSubagentSessionId() !== String(childSessionId)) return null;
        const list = document.getElementById('chat-messages-list');
        if (!list) return null;
        return list.querySelector('li[data-pending="true"]');
    } catch (_) {
        return null;
    }
}

export function activePrimaryPendingAssistantRow() {
    try {
        if (getCurrentOpenSubagentSessionId()) return null;
        const list = document.getElementById('chat-messages-list');
        if (!list) return null;
        return list.querySelector('li[data-role="assistant"][data-pending="true"][data-stream-url]');
    } catch (_) {
        return null;
    }
}

export function updateChatSendButtonState() {
    try {
        const form = document.getElementById('chat-send-form');
        const button = document.getElementById('chat-send-btn');
        if (!form || !button) return;
        const activeRow = activePrimaryPendingAssistantRow();
        const running = Boolean(activeRow);
        form.dataset.chatRunning = running ? 'true' : 'false';
        button.classList.toggle('btn-outline-danger', running);
        button.classList.toggle('btn-outline-light', stopRequestInFlight);
        if (stopRequestInFlight) {
            button.textContent = 'Stopping...';
            button.setAttribute('aria-label', 'Stopping current response');
            button.setAttribute('aria-busy', 'true');
        } else if (running) {
            button.textContent = 'Stop';
            button.setAttribute('aria-label', 'Stop current response');
            button.removeAttribute('aria-busy');
        } else {
            button.textContent = 'Send';
            button.setAttribute('aria-label', 'Send message');
            button.removeAttribute('aria-busy');
        }
    } catch (_) {
    }
}

export function replaceChatContainerFromHtml(html, deps) {
    try {
        if (!html) return;
        const runtime = deps || window.__chatRuntime || {};
        const template = document.createElement('template');
        template.innerHTML = html.trim();
        const incoming = template.content.querySelector('#chat-container');
        const current = document.getElementById('chat-container');
        if (incoming && current) {
            current.outerHTML = incoming.outerHTML;
            Promise.resolve().then(() => {
                initChatComposer(runtime);
                bindPendingStreams(runtime);
                renderAllChatMarkdown();
                formatAllChatSubtitles();
                updateChatSendButtonState();
            });
        }
    } catch (_) {
    }
}

export function requestStopActiveChat(deps) {
    try {
        if (stopRequestInFlight) return;
        const runtime = deps || window.__chatRuntime || {};
        const row = activePrimaryPendingAssistantRow();
        if (!row || !row.dataset || !row.dataset.id) return;
        stopRequestInFlight = true;
        updateChatSendButtonState();
        const body = new URLSearchParams();
        body.set('assistantId', row.dataset.id);
        fetch('/ui/chat/stop', {
            method: 'POST',
            headers: {'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8', 'HX-Request': 'true'},
            body: body.toString()
        }).then(response => {
            if (!response.ok) throw new Error('Stop request failed');
            return response.text();
        }).then(html => replaceChatContainerFromHtml(html, runtime))
            .catch(error => console.error(error))
            .finally(() => {
                stopRequestInFlight = false;
                updateChatSendButtonState();
            });
    } catch (_) {
        stopRequestInFlight = false;
        updateChatSendButtonState();
    }
}

export function clearPendingStream(assistantId, source) {
    if (activePendingStreams.get(assistantId) === source) {
        activePendingStreams.delete(assistantId);
    }
    stopRequestInFlight = false;
    updateChatSendButtonState();
}

export function getLiveChatRow(assistantId) {
    try {
        if (!assistantId) return null;
        const list = document.getElementById('chat-messages-list');
        if (!list) return null;
        const candidates = Array.from(list.querySelectorAll('li[data-id="' + assistantId + '"]'));
        const visibleCandidates = candidates.filter(row => row && row.getClientRects && row.getClientRects().length > 0);
        const visiblePendingCandidates = visibleCandidates.filter(row => row.dataset.pending === 'true');
        if (visiblePendingCandidates.length > 0) return visiblePendingCandidates[0];
        if (visibleCandidates.length > 0) return visibleCandidates[0];
        const pendingCandidates = candidates.filter(row => row.dataset.pending === 'true');
        return pendingCandidates[0] || candidates[0] || null;
    } catch (_) {
        return null;
    }
}

export function clearPendingChatRowState(row) {
    if (!row) return;
    row.classList.remove('pending');
    row.removeAttribute('data-pending');
    row.dataset.streamBound = '0';
}

function toolCallKey(payload) {
    const toolName = payload && payload.toolName != null ? String(payload.toolName) : '';
    return [toolName, toolCallInputText(payload)].join('\u001f');
}

function toolCallInputText(payload) {
    try {
        if (!payload) return '';
        const preview = payload.inputPreview != null ? String(payload.inputPreview) : '';
        if (preview.trim()) return preview;

        const args = payload.args;
        if (args == null) return '';
        if (typeof args === 'string') return args;
        if (typeof args === 'object') {
            for (const key of ['input', 'task', 'message', 'prompt', 'text']) {
                if (args[key] != null && String(args[key]).trim()) return String(args[key]);
            }
            try {
                return JSON.stringify(args, null, 2);
            } catch (_) {
                return String(args);
            }
        }
        return String(args);
    } catch (_) {
        return '';
    }
}

function toolCallStatusText(state, success) {
    if (state === 'running') return 'running';
    if (state === 'done') return 'done';
    if (state === 'error') return 'error';
    return success ? 'success' : 'failure';
}

function toolCallOutputText(payload) {
    try {
        if (!payload) return '';
        const value = payload.outputPreview != null ? payload.outputPreview
            : (payload.finalText != null ? payload.finalText
                : (payload.textSummary != null ? payload.textSummary
                    : (payload.machineSummary != null ? payload.machineSummary
                        : (payload.text != null ? payload.text : (payload.errorText != null ? payload.errorText : '')))));
        if (value == null) return '';
        if (typeof value === 'string') return value;
        return JSON.stringify(value, null, 2);
    } catch (_) {
        return '';
    }
}

function getDirectToolCallChild(parent, className) {
    return Array.from(parent && parent.children ? parent.children : []).find(child => child && child.classList && child.classList.contains(className)) || null;
}

function getDirectToolCallChildren(parent, className) {
    return Array.from(parent && parent.children ? parent.children : []).filter(child => child && child.classList && child.classList.contains(className));
}

function getToolCallGroups(container) {
    return getDirectToolCallChildren(container, 'tool-call');
}

function getToolCallGroupCalls(group) {
    const detail = getDirectToolCallChild(group, 'tool-call-detail');
    const callsContainer = detail ? getDirectToolCallChild(detail, 'tool-call-calls') : null;
    return callsContainer ? Array.from(callsContainer.children).filter(child => child && child.classList && child.classList.contains('tool-call-call')) : [];
}

function isSpecialStandaloneToolCall(toolName) {
    const kind = toolCallGroupKind(normalizeToolCallName(toolName));
    return kind === 'task' || kind === 'image';
}

function getToolCallContainer(target) {
    try {
        if (!target) return null;
        if (target.classList && target.classList.contains('tool-calls')) return target;
        let container = getDirectToolCallChild(target, 'tool-calls');
        if (!container) {
            container = document.createElement('div');
            container.className = 'tool-calls';
            target.appendChild(container);
        }
        return container;
    } catch (_) {
        return null;
    }
}

function buildToolCallBundleRefs(bundle) {
    try {
        if (!bundle) return null;

        let summary = getDirectToolCallChild(bundle, 'tool-call-summary');
        if (!summary) {
            summary = document.createElement('summary');
            summary.className = 'tool-call-summary';
            bundle.appendChild(summary);
        }

        let nameSpan = summary.querySelector('.tool-call-name');
        if (!nameSpan) {
            nameSpan = document.createElement('span');
            nameSpan.className = 'tool-call-name';
            summary.appendChild(nameSpan);
        }

        let detail = getDirectToolCallChild(bundle, 'tool-call-detail');
        if (!detail) {
            detail = document.createElement('div');
            detail.className = 'tool-call-detail';
            bundle.appendChild(detail);
        }

        let callsContainer = getDirectToolCallChild(detail, 'tool-call-calls');
        if (!callsContainer) {
            callsContainer = document.createElement('div');
            callsContainer.className = 'tool-call-calls';
            detail.appendChild(callsContainer);
        }

        return {bundle, summary, nameSpan, detail, callsContainer};
    } catch (_) {
        return null;
    }
}

function getToolCallBundle(container) {
    try {
        const groups = getToolCallGroups(container);
        const lastGroup = groups.length > 0 ? groups[groups.length - 1] : null;
        return lastGroup && lastGroup.classList && lastGroup.classList.contains('tool-call-bundle') ? lastGroup : null;
    } catch (_) {
        return null;
    }
}

function getOrCreateToolCallBundleCallsContainer(container) {
    try {
        const bundle = getToolCallBundle(container) || createToolCallBundle(container);
        if (!bundle) return null;
        return bundle.callsContainer || getDirectToolCallChild(getDirectToolCallChild(bundle.bundle || bundle, 'tool-call-detail'), 'tool-call-calls');
    } catch (_) {
        return null;
    }
}

function createToolCallBundle(container) {
    try {
        if (!container) return null;

        const bundle = document.createElement('details');
        bundle.className = 'tool-call tool-call-bundle';
        bundle.dataset.toolCallKind = 'bundle';
        container.appendChild(bundle);

        const refs = buildToolCallBundleRefs(bundle);
        if (!refs) return null;
        refs.nameSpan.textContent = 'Used';
        return refs;
    } catch (_) {
        return null;
    }
}

function refreshToolCallBundleSummary(bundleRefs) {
    try {
        if (!bundleRefs || !bundleRefs.bundle) return;

        const groups = bundleRefs.callsContainer ? Array.from(bundleRefs.callsContainer.children).filter(child => child && child.classList && child.classList.contains('tool-call')) : [];
        const counts = new Map();
        const order = [];
        let allSuccess = true;

        for (const group of groups) {
            const calls = getToolCallGroupCalls(group);
            for (const call of calls) {
                const toolName = normalizeToolCallName(call.dataset ? call.dataset.toolCallToolName : '');
                if (!toolName) continue;
                if (!counts.has(toolName)) order.push(toolName);
                counts.set(toolName, (counts.get(toolName) || 0) + 1);
                if (call.dataset.toolCallSuccess !== 'true') {
                    allSuccess = false;
                }
            }
            if (group.dataset.toolCallSuccess !== 'true') {
                allSuccess = false;
            }
        }

        const label = order.map(name => counts.get(name) > 1 ? name + ' (' + counts.get(name) + ')' : name).join(', ');
        bundleRefs.bundle.dataset.toolCallKind = 'bundle';
        bundleRefs.bundle.dataset.toolCallState = allSuccess ? 'done' : 'error';
        bundleRefs.bundle.dataset.toolCallSuccess = allSuccess ? 'true' : 'false';
        bundleRefs.bundle.dataset.toolCallSummaryLabel = label ? 'Used: ' + label : 'Used';
        if (bundleRefs.summary) {
            bundleRefs.summary.dataset.toolCallState = bundleRefs.bundle.dataset.toolCallState;
        }
        if (bundleRefs.nameSpan) {
            bundleRefs.nameSpan.textContent = bundleRefs.bundle.dataset.toolCallSummaryLabel;
        }
    } catch (_) {
    }
}

const EXPLORATORY_TOOL_CALLS = new Set(['list_files', 'read_file', 'search_code']);
const IMAGE_TOOL_CALLS = new Set(['display_image']);

function normalizeToolCallName(name) {
    return String(name == null ? '' : name).trim() || 'tool';
}

function toolCallGroupKind(toolName) {
    if (toolName === 'task') return 'task';
    if (EXPLORATORY_TOOL_CALLS.has(toolName)) return 'exploratory';
    if (IMAGE_TOOL_CALLS.has(toolName)) return 'image';
    return 'other';
}

function parseToolCallList(value) {
    if (!value) return [];
    try {
        const parsed = JSON.parse(value);
        if (!Array.isArray(parsed)) return [];
        return parsed.map(item => String(item).trim()).filter(Boolean);
    } catch (_) {
        return [String(value).trim()].filter(Boolean);
    }
}

function readToolCallValues(entry, datasetKey, legacyKey) {
    const values = parseToolCallList(entry && entry.dataset ? entry.dataset[datasetKey] : '');
    const legacyValue = entry && entry.dataset ? String(entry.dataset[legacyKey] || '').trim() : '';
    if (legacyValue && !values.includes(legacyValue)) values.push(legacyValue);
    return values;
}

function writeToolCallValues(entry, datasetKey, values) {
    entry.dataset[datasetKey] = JSON.stringify(Array.from(new Set(values.filter(Boolean))));
}

function rememberToolCallIdentity(entry, toolCallId, key) {
    try {
        if (!entry) return;
        if (toolCallId) {
            const toolCallIds = readToolCallValues(entry, 'toolCallIds', 'toolCallId');
            if (!toolCallIds.includes(toolCallId)) toolCallIds.push(toolCallId);
            writeToolCallValues(entry, 'toolCallIds', toolCallIds);
            entry.dataset.toolCallId = toolCallId;
        }
        if (key) {
            const toolCallKeys = readToolCallValues(entry, 'toolCallKeys', 'toolCallKey');
            if (!toolCallKeys.includes(key)) toolCallKeys.push(key);
            writeToolCallValues(entry, 'toolCallKeys', toolCallKeys);
            entry.dataset.toolCallKey = key;
        }
    } catch (_) {
    }
}

function toolCallGroupSummaryText(calls) {
    try {
        if (!calls || calls.length === 0) return '';

        const segments = [];
        let currentName = normalizeToolCallName(calls[0] && calls[0].dataset ? calls[0].dataset.toolCallToolName : '');
        let currentCount = 1;

        for (let i = 1; i < calls.length; i++) {
            const nextName = normalizeToolCallName(calls[i] && calls[i].dataset ? calls[i].dataset.toolCallToolName : '');
            if (nextName === currentName) {
                currentCount++;
                continue;
            }

            segments.push(currentCount > 1 ? currentName + ' (' + currentCount + ')' : currentName);
            currentName = nextName;
            currentCount = 1;
        }

        segments.push(currentCount > 1 ? currentName + ' (' + currentCount + ')' : currentName);
        return segments.join(', ');
    } catch (_) {
        return '';
    }
}

function canAppendToolCallEntry(entry, payload) {
    const existingName = normalizeToolCallName(entry && entry.dataset ? entry.dataset.toolCallToolName : '');
    const nextName = normalizeToolCallName(payload && payload.toolName);
    if (!existingName || !nextName) return false;

    const existingKind = entry && entry.dataset && entry.dataset.toolCallGroupKind ? entry.dataset.toolCallGroupKind : toolCallGroupKind(existingName);
    const nextKind = toolCallGroupKind(nextName);

    if (existingKind === 'task' || existingKind === 'image' || nextKind === 'task' || nextKind === 'image') return false;
    if (existingKind === 'exploratory' && nextKind === 'exploratory') return true;
    return existingName === nextName;
}

function updateToolCallGroupKind(details, toolName) {
    if (!details) return;
    const kind = toolCallGroupKind(normalizeToolCallName(toolName));
    details.dataset.toolCallGroupKind = kind;
}

function entryHasToolCallId(entry, toolCallId) {
    if (!entry || !toolCallId) return false;
    return readToolCallValues(entry, 'toolCallIds', 'toolCallId').includes(toolCallId);
}

function entryHasToolCallKey(entry, key) {
    if (!entry || !key) return false;
    return readToolCallValues(entry, 'toolCallKeys', 'toolCallKey').includes(key);
}

function findToolCallEntry(container, payload) {
    try {
        const toolCallId = payload && payload.toolCallId != null ? String(payload.toolCallId).trim() : '';
        const key = toolCallKey(payload);
        const searchScopes = [];

        const addScope = (scope) => {
            if (scope && !searchScopes.includes(scope)) searchScopes.push(scope);
        };

        addScope(container);
        try {
            addScope(toolCallRegistryScope(container));
            addScope(container && container.closest ? container.closest('li[data-id]') : null);
            addScope(document.getElementById('chat-messages-list'));
        } catch (_) {
        }

        for (const scope of searchScopes) {
            const registry = toolCallRegistry(scope);
            if (registry) {
                if (toolCallId && registry.byId.has(toolCallId)) return registry.byId.get(toolCallId);
                if (key && registry.byKey.has(key)) return registry.byKey.get(key);
            }
            const entries = scope && scope.querySelectorAll ? Array.from(scope.querySelectorAll('.tool-call-call')) : [];
            for (const entry of entries) {
                if (toolCallId && entryHasToolCallId(entry, toolCallId)) return entry;
                if (key && entryHasToolCallKey(entry, key)) return entry;
            }
        }

        return null;
    } catch (_) {
        return null;
    }
}

function hasVisibleImageFigure(entry) {
    return Boolean(entry && entry.querySelector && entry.querySelector('.tool-call-image-preview'));
}

function clearToolCallImages(entry) {
    try {
        if (!entry || !entry.querySelectorAll) return;
        Array.from(entry.querySelectorAll('.tool-call-image-preview')).forEach(node => node.remove());
    } catch (_) {
    }
}

function toolCallRegistryScope(target) {
    try {
        return target && target.closest ? target.closest('li[data-id]') : null;
    } catch (_) {
        return null;
    }
}

function toolCallRegistry(scope) {
    if (!scope) return null;
    if (!scope.__toolCallRegistry) {
        try {
            Object.defineProperty(scope, '__toolCallRegistry', {
                value: {byId: new Map(), byKey: new Map()},
                configurable: true,
                enumerable: false,
                writable: false
            });
        } catch (_) {
            scope.__toolCallRegistry = {byId: new Map(), byKey: new Map()};
        }
    }
    return scope.__toolCallRegistry;
}

function registerToolCallEntry(scope, entry, toolCallId, key) {
    try {
        const registry = toolCallRegistry(scope);
        if (!registry || !entry) return;
        if (toolCallId) registry.byId.set(toolCallId, entry);
        if (key) registry.byKey.set(key, entry);
    } catch (_) {
    }
}

function buildToolCallGroupRefs(group) {
    try {
        if (!group) return null;

        let summary = getDirectToolCallChild(group, 'tool-call-summary');
        if (!summary) {
            summary = document.createElement('summary');
            summary.className = 'tool-call-summary';
            group.appendChild(summary);
        }

        let nameSpan = summary.querySelector('.tool-call-name');
        if (!nameSpan) {
            nameSpan = document.createElement('span');
            nameSpan.className = 'tool-call-name';
            summary.appendChild(nameSpan);
        }

        let statusSpan = summary.querySelector('.tool-call-status');
        if (!statusSpan) {
            statusSpan = document.createElement('span');
            statusSpan.className = 'tool-call-status';
            summary.appendChild(statusSpan);
        }
        let detail = getDirectToolCallChild(group, 'tool-call-detail');
        if (!detail) {
            detail = document.createElement('div');
            detail.className = 'tool-call-detail';
            group.appendChild(detail);
        }

        let callsContainer = getDirectToolCallChild(detail, 'tool-call-calls');
        if (!callsContainer) {
            callsContainer = document.createElement('div');
            callsContainer.className = 'tool-call-calls';
            detail.appendChild(callsContainer);
        }

        return {group, summary, nameSpan, statusSpan, detail, callsContainer};
    } catch (_) {
        return null;
    }
}

function buildToolCallCallRefs(call, processHtmxElementFn) {
    try {
        if (!call) return null;

        let subagent = getDirectToolCallChild(call, 'tool-call-subagent') || null;
        let button = subagent ? subagent.querySelector('.tool-call-subagent-button') : null;

        let inputSection = Array.from(call.children || []).find(child => child && child.classList && child.classList.contains('tool-call-section') && child.dataset.toolCallField === 'input') || null;
        if (!inputSection) {
            inputSection = document.createElement('section');
            inputSection.className = 'tool-call-section';
            inputSection.dataset.toolCallField = 'input';
            call.appendChild(inputSection);
        }

        let inputLabel = inputSection.querySelector('.tool-call-label');
        if (!inputLabel) {
            inputLabel = document.createElement('div');
            inputLabel.className = 'tool-call-label';
            inputSection.appendChild(inputLabel);
        }
        inputLabel.textContent = 'Input';

        let inputPre = inputSection.querySelector('.tool-call-pre');
        if (!inputPre) {
            inputPre = document.createElement('pre');
            inputPre.className = 'tool-call-pre';
            inputSection.appendChild(inputPre);
        }

        let outputSection = Array.from(call.children || []).find(child => child && child.classList && child.classList.contains('tool-call-section') && child.dataset.toolCallField === 'output') || null;
        if (!outputSection) {
            outputSection = document.createElement('section');
            outputSection.className = 'tool-call-section';
            outputSection.dataset.toolCallField = 'output';
            call.appendChild(outputSection);
        }

        let outputLabel = outputSection.querySelector('.tool-call-label');
        if (!outputLabel) {
            outputLabel = document.createElement('div');
            outputLabel.className = 'tool-call-label';
            outputSection.appendChild(outputLabel);
        }
        outputLabel.textContent = 'Output';

        let outputPre = outputSection.querySelector('.tool-call-pre');
        let imageFigure = outputSection.querySelector('.tool-call-image-preview') || call.querySelector('.tool-call-image-preview');
        if (!outputPre && !imageFigure) {
            outputPre = document.createElement('pre');
            outputPre.className = 'tool-call-pre';
            outputSection.appendChild(outputPre);
        }

        let nestedCalls = getDirectToolCallChild(call, 'tool-calls');
        if (!nestedCalls) {
            nestedCalls = document.createElement('div');
            nestedCalls.className = 'tool-calls';
            call.appendChild(nestedCalls);
        }

        if (processHtmxElementFn) processHtmxElementFn(button);

        return {details: call, detail: call, subagent, button, inputPre, outputSection, outputPre, imageFigure, nestedCalls};
    } catch (_) {
        return null;
    }
}

function refreshToolCallGroupSummary(groupRefs) {
    try {
        if (!groupRefs || !groupRefs.group) return;

        const group = groupRefs.group;
        const calls = groupRefs.callsContainer ? Array.from(groupRefs.callsContainer.children).filter(child => child && child.classList && child.classList.contains('tool-call-call')) : [];
        const toolName = group.dataset.toolCallToolName || (calls[0] && calls[0].dataset && calls[0].dataset.toolCallToolName) || 'tool';
        const count = calls.length;
        const running = calls.some(call => call.dataset.toolCallState === 'running');
        const success = count > 0 && calls.every(call => call.dataset.toolCallSuccess === 'true');
        const state = running ? 'running' : (success ? 'done' : 'error');
        const statusText = running ? 'running' : (success ? 'success' : 'failure');
        const label = toolCallGroupSummaryText(calls) || (count > 1 ? toolName + ' (' + count + ')' : toolName);

        group.dataset.toolCallToolName = toolName;
        group.dataset.toolCallCount = String(count);
        group.dataset.toolCallState = state;
        group.dataset.toolCallSuccess = success ? 'true' : 'false';
        group.dataset.toolCallSummaryLabel = label;
        group.dataset.toolCallKind = group.dataset.toolCallKind || toolCallGroupKind(toolName);

        if (groupRefs.summary) {
            groupRefs.summary.dataset.toolCallState = group.dataset.toolCallState;
        }
        if (groupRefs.nameSpan) {
            groupRefs.nameSpan.textContent = label;
        }
        if (groupRefs.statusSpan) {
            groupRefs.statusSpan.className = 'tool-call-status';
            if (state === 'running') {
                groupRefs.statusSpan.textContent = statusText;
            } else {
                if (success) groupRefs.statusSpan.classList.add('tool-call-status-success');
                else groupRefs.statusSpan.classList.add('tool-call-status-failure');
                groupRefs.statusSpan.textContent = statusText;
            }
        }
    } catch (_) {
    }
}

function createToolCallGroup(container, toolName) {
    try {
        if (!container) return null;

        const group = document.createElement('details');
        group.className = 'tool-call';
        group.dataset.toolCallToolName = toolName;
        container.appendChild(group);

        const refs = buildToolCallGroupRefs(group);
        if (!refs) return null;

        refs.nameSpan.textContent = toolName;
        refs.statusSpan.textContent = 'running';
        return refs;
    } catch (_) {
        return null;
    }
}

function canCoalesceToolCallGroup(lastGroup, toolName) {
    try {
        const lastToolName = normalizeToolCallName(lastGroup && lastGroup.dataset ? lastGroup.dataset.toolCallToolName : '');
        const nextToolName = normalizeToolCallName(toolName);
        if (!lastToolName || !nextToolName) return false;

        const lastKind = toolCallGroupKind(lastToolName);
        const nextKind = toolCallGroupKind(nextToolName);
        if (lastKind === 'task' || lastKind === 'image' || nextKind === 'task' || nextKind === 'image') return false;
        if (lastKind === 'exploratory' && nextKind === 'exploratory') return true;
        return lastToolName === nextToolName;
    } catch (_) {
        return false;
    }
}

function refreshAnyToolCallBundle(container) {
    try {
        if (!container) return;
        const bundle = (container.closest && container.closest('details.tool-call-bundle')) || getToolCallBundle(container);
        if (!bundle) return;
        refreshToolCallBundleSummary(buildToolCallBundleRefs(bundle));
    } catch (_) {
    }
}

function refreshParentToolCallBundle(group) {
    try {
        if (!group || !group.closest) return;
        const bundle = group.closest('details.tool-call-bundle');
        if (!bundle) return;
        refreshToolCallBundleSummary(buildToolCallBundleRefs(bundle));
    } catch (_) {
    }
}

function createToolCallCall(groupRefs, payload, processHtmxElementFn) {
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
        refreshParentToolCallBundle(groupRefs.group);
        return {group: groupRefs.group, summary: groupRefs.summary, nameSpan: groupRefs.nameSpan, statusSpan: groupRefs.statusSpan, detail: refs.detail, details: refs.details, subagent: refs.subagent, button: refs.button, inputPre: refs.inputPre, outputSection: refs.outputSection, outputPre: refs.outputPre, imageFigure: refs.imageFigure, nestedCalls: refs.nestedCalls};
    } catch (_) {
        return null;
    }
}

function buildToolCallEntry(entry, processHtmxElementFn) {
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

function ensureToolCallEntry(target, payload, processHtmxElementFn) {
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
            refreshParentToolCallBundle(existing.group || existingEntry);
            return existing;
        }

        const bundle = getToolCallBundle(container);
        const bundleRefs = bundle ? buildToolCallBundleRefs(bundle) : null;
        const groupContainer = isSpecialStandaloneToolCall(toolName)
            ? container
            : (bundleRefs ? bundleRefs.callsContainer : getOrCreateToolCallBundleCallsContainer(container));
        if (!groupContainer) return null;

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
        refreshAnyToolCallBundle(container);
        return entry;
    } catch (_) {
        return null;
    }
}

function updateToolCallEntry(entry, payload, options, processHtmxElementFn) {
    try {
        if (!entry) return;
        if (!entry || !entry.details) return;

        const details = entry.details;
        const group = entry.group || details.closest('details.tool-call');
        const toolName = options && options.toolName != null ? String(options.toolName) : (payload && payload.toolName != null ? String(payload.toolName) : 'tool');
        const inputText = options && Object.prototype.hasOwnProperty.call(options, 'inputText') ? String(options.inputText ?? '') : toolCallInputText(payload);
        const outputText = options && Object.prototype.hasOwnProperty.call(options, 'outputText') ? String(options.outputText ?? '') : toolCallOutputText(payload);
        const state = options && options.state != null ? String(options.state) : (payload && payload.success != null ? (payload.success ? 'done' : 'error') : 'running');
        const statusText = options && options.statusText != null ? String(options.statusText) : toolCallStatusText(state, payload && payload.success);
        const success = options && Object.prototype.hasOwnProperty.call(options, 'success') ? Boolean(options.success) : (payload && payload.success != null ? Boolean(payload.success) : state !== 'error');

        details.dataset.toolCallToolName = toolName;
        details.dataset.toolCallState = state;
        details.dataset.toolCallSuccess = success ? 'true' : 'false';
        details.dataset.toolCallStatus = statusText;
        details.dataset.toolCallKey = toolCallKey(payload);
        rememberToolCallIdentity(details, payload && payload.toolCallId != null ? String(payload.toolCallId).trim() : '', toolCallKey(payload));
        updateToolCallGroupKind(details, toolName);

        if (entry.nameSpan) {
            entry.nameSpan.textContent = toolCallGroupSummaryText(group ? getToolCallGroupCalls(group) : [details]) || toolName;
        }
        if (entry.statusSpan) {
            entry.statusSpan.className = 'tool-call-status';
            if (state === 'running') {
                entry.statusSpan.textContent = statusText;
            } else {
                if (success) entry.statusSpan.classList.add('tool-call-status-success');
                else entry.statusSpan.classList.add('tool-call-status-failure');
                entry.statusSpan.textContent = statusText;
            }
        }

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

        const subagentSessionId = options && options.subagentSessionId != null ? String(options.subagentSessionId) : (payload && payload.subagentSessionId != null ? String(payload.subagentSessionId) : '');
        if (subagentSessionId) {
            const name = options && options.subagentAgentName != null ? String(options.subagentAgentName) : (payload && payload.subagentAgentName != null ? String(payload.subagentAgentName) : (payload && payload.name != null ? String(payload.name) : subagentSessionId));
            if (!entry.subagent) {
                entry.subagent = document.createElement('div');
                entry.subagent.className = 'tool-call-subagent';
                entry.detail.insertBefore(entry.subagent, getDirectToolCallChild(entry.detail, 'tool-call-section') || getDirectToolCallChild(entry.detail, 'tool-calls') || null);
            }
            if (!entry.button) {
                entry.button = document.createElement('button');
                entry.button.type = 'button';
                entry.button.className = 'btn btn-outline-primary btn-sm d-inline-flex align-items-center gap-1 tool-call-subagent-button';
                entry.subagent.appendChild(entry.button);
            }
            entry.subagent.dataset.childSessionId = subagentSessionId;
            entry.button.setAttribute('hx-get', '/ui/chat/subagent/' + encodeURIComponent(subagentSessionId));
            entry.button.setAttribute('hx-target', '#chat-container');
            entry.button.setAttribute('hx-swap', 'outerHTML');
            entry.button.replaceChildren(document.createTextNode('Open subagent: '), (() => {
                const strong = document.createElement('strong');
                strong.textContent = name;
                return strong;
            })());
            if (processHtmxElementFn) processHtmxElementFn(entry.button);
        }

        if (group) {
            refreshToolCallGroupSummary({group: group, summary: entry.summary, nameSpan: entry.nameSpan, statusSpan: entry.statusSpan, callsContainer: getDirectToolCallChild(getDirectToolCallChild(group, 'tool-call-detail'), 'tool-call-calls')});
            refreshParentToolCallBundle(group);
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

export function updateOpenSubagentTranscript(payload, kind) {
    try {
        const childSessionId = payload && payload.childSessionId != null ? String(payload.childSessionId) : '';
        const row = getOpenSubagentPendingRow(childSessionId);
        if (!row) return;

        const textSpan = row.querySelector('.chat-message-text');
        if (!textSpan) return;

        if (kind === 'delta') {
            if (payload && payload.delta != null) {
                const prevRaw = getRawChatMarkdown(textSpan);
                renderChatMarkdown(textSpan, prevRaw + String(payload.delta));
            }
            return;
        }

        if (kind === 'started') {
            if (payload && payload.task != null) {
                renderChatMarkdown(textSpan, String(payload.task));
            }
            return;
        }

        if (kind === 'done') {
            if (payload && payload.finalText != null) {
                renderChatMarkdown(textSpan, String(payload.finalText));
            } else if (payload && payload.text != null) {
                renderChatMarkdown(textSpan, String(payload.text));
            }
            clearPendingChatRowState(row);
            return;
        }

        if (kind === 'error') {
            const errorText = payload && payload.errorText != null ? String(payload.errorText) : 'Subagent error';
            const prevRaw = getRawChatMarkdown(textSpan);
            renderChatMarkdown(textSpan, prevRaw + '\n[Error: ' + errorText + ']');
            clearPendingChatRowState(row);
            return;
        }

        if (kind === 'tool_call') {
            appendToolCallToChatRow(row, payload);
        }
    } catch (_) {
    }
}

export function bindPendingStreams(deps) {
    try {
        const runtime = deps || window.__chatRuntime || {};
        const list = document.getElementById('chat-messages-list');
        if (!list) return;
        const rows = list.querySelectorAll('li[data-pending="true"]');
        rows.forEach(row => {
            const assistantId = row.dataset.id != null ? String(row.dataset.id) : '';
            if (!assistantId) return;
            if (row.dataset.streamBound === '1') return;
            const url = row.dataset.streamUrl;
            if (!url) return;
            const existingSource = activePendingStreams.get(assistantId);
            if (existingSource) {
                try {
                    existingSource.close();
                } catch (_) {
                }
            }
            row.dataset.streamBound = '1';

            const FLUSH_INTERVAL_MS = 40;
            let buffer = '';
            let gotDelta = false;
            let rafPending = false;
            let flushTimer = null;
            let lastFlushTime = 0;
            const currentRow = () => getLiveChatRow(assistantId);

            function currentTextSpan() {
                const liveRow = currentRow();
                return liveRow ? liveRow.querySelector('.chat-message-text') : null;
            }

            function flushBuffer() {
                const textSpan = currentTextSpan();
                if (!textSpan) return;
                if (buffer.length === 0) {
                    rafPending = false;
                    if (flushTimer) {
                        clearTimeout(flushTimer);
                        flushTimer = null;
                    }
                    return;
                }
                try {
                    const prevRaw = getRawChatMarkdown(textSpan);
                    const newRaw = prevRaw + buffer;
                    renderChatMarkdown(textSpan, newRaw);
                } catch (_) {
                    try {
                        textSpan.textContent = textSpan.textContent + buffer;
                    } catch (_) {
                    }
                }
                buffer = '';
                rafPending = false;
                lastFlushTime = Date.now();
                if (flushTimer) {
                    clearTimeout(flushTimer);
                    flushTimer = null;
                }
            }

            function scheduleFlush() {
                if (rafPending || flushTimer) return;
                const now = Date.now();
                if (lastFlushTime === 0) {
                    flushBuffer();
                    return;
                }
                const elapsed = now - lastFlushTime;
                if (elapsed >= FLUSH_INTERVAL_MS) {
                    rafPending = true;
                    requestAnimationFrame(flushBuffer);
                    return;
                }
                flushTimer = setTimeout(() => {
                    flushTimer = null;
                    if (rafPending) return;
                    rafPending = true;
                    requestAnimationFrame(flushBuffer);
                }, FLUSH_INTERVAL_MS - elapsed);
            }

            const STREAM_BOTTOM_THRESHOLD_PX = 96;

            function wasNearBottom() {
                try {
                    const history = document.getElementById('chat-history');
                    if (!history) return false;
                    const max = history.scrollHeight - history.clientHeight;
                    const cur = history.scrollTop;
                    if (!Number.isFinite(max) || !Number.isFinite(cur)) return false;
                    return (max - cur) <= STREAM_BOTTOM_THRESHOLD_PX;
                } catch (_) {
                    return false;
                }
            }

            let shouldStickToBottom = wasNearBottom();
            let streamHistoryEl = null;

            function streamScrollListener() {
                shouldStickToBottom = wasNearBottom();
            }

            const es = new EventSource(url);
            activePendingStreams.set(assistantId, es);
            updateChatSendButtonState();

            function parseStreamPayload(e) {
                const raw = (e && e.data) ? e.data : '';
                if (!raw) return {text: ''};
                try {
                    const parsed = JSON.parse(raw);
                    if (parsed && typeof parsed === 'object') return parsed;
                } catch (_) {
                }
                return {text: raw};
            }

            es.addEventListener('delta', (e) => {
                try {
                    const payload = parseStreamPayload(e);
                    if (payload && payload.text != null) {
                        buffer += payload.text;
                        gotDelta = true;
                        scheduleFlush();
                    }
                } catch (_) {
                }
            });

            es.addEventListener('tool_call_started', (e) => {
                try {
                    const payload = parseStreamPayload(e) || {};
                    const liveRow = currentRow();
                    if (!liveRow) return;
                    appendToolCallToChatRow(liveRow, payload, processHtmxElement, {
                        state: 'running',
                        statusText: 'running',
                        success: false,
                        inputText: toolCallInputText(payload),
                        outputText: toolCallOutputText(payload)
                    });
                } catch (_) {
                }
            });

            es.addEventListener('tool_call_progress', (e) => {
                try {
                    const event = parseStreamPayload(e) || {};
                    const payload = event.payload || {};
                    const liveRow = currentRow();
                    if (!liveRow) return;

                    if (event.eventName === 'subagent_started') {
                        appendToolCallToChatRow(liveRow, {
                            toolCallId: event.toolCallId,
                            toolName: event.toolName,
                            inputPreview: toolCallInputText(payload) || payload.task || '',
                            subagentSessionId: payload.childSessionId,
                            subagentAgentName: payload.subagentAgentName
                        }, processHtmxElement, {
                            state: 'running',
                            statusText: 'running',
                            success: false,
                            outputText: payload.task != null ? String(payload.task) : '',
                            subagentSessionId: payload.childSessionId,
                            subagentAgentName: payload.subagentAgentName
                        });
                        updateOpenSubagentTranscript(payload, 'started');
                        return;
                    }

                    if (event.eventName === 'subagent_delta') {
                        const entry = appendToolCallToChatRow(liveRow, {
                            toolCallId: event.toolCallId,
                            toolName: event.toolName,
                            inputPreview: toolCallInputText(payload) || payload.task || ''
                        }, processHtmxElement, {state: 'running', statusText: 'running', success: false});
                        if (entry && entry.outputPre && payload.delta != null) {
                            entry.outputPre.textContent = (entry.outputPre.textContent || '') + String(payload.delta);
                        }
                        updateOpenSubagentTranscript(payload, 'delta');
                        return;
                    }

                    if (event.eventName === 'subagent_tool_call') {
                        const parentEntry = appendToolCallToChatRow(liveRow, {
                            toolCallId: event.toolCallId,
                            toolName: event.toolName,
                            inputPreview: toolCallInputText(payload) || payload.task || ''
                        }, processHtmxElement, {state: 'running', statusText: 'running', success: false});
                        if (parentEntry && parentEntry.nestedCalls) {
                            appendToolCallToChatRow(parentEntry.nestedCalls, payload, processHtmxElement, {
                                state: payload.success ? 'done' : 'error',
                                statusText: payload.success ? 'success' : 'failure',
                                success: Boolean(payload.success)
                            });
                        }
                        updateOpenSubagentTranscript(payload, 'tool_call');
                        return;
                    }

                    if (event.eventName === 'subagent_done') {
                        appendToolCallToChatRow(liveRow, {
                            toolCallId: event.toolCallId,
                            toolName: event.toolName,
                            inputPreview: toolCallInputText(payload) || payload.task || '',
                            subagentSessionId: payload.childSessionId,
                            subagentAgentName: payload.subagentAgentName
                        }, processHtmxElement, {
                            state: 'done',
                            statusText: 'done',
                            success: true,
                            outputText: payload.finalText != null ? String(payload.finalText) : '',
                            subagentSessionId: payload.childSessionId,
                            subagentAgentName: payload.subagentAgentName
                        });
                        updateOpenSubagentTranscript(payload, 'done');
                        return;
                    }

                    if (event.eventName === 'subagent_error') {
                        appendToolCallToChatRow(liveRow, {
                            toolCallId: event.toolCallId,
                            toolName: event.toolName,
                            inputPreview: toolCallInputText(payload) || payload.task || '',
                            subagentSessionId: payload.childSessionId,
                            subagentAgentName: payload.subagentAgentName
                        }, processHtmxElement, {
                            state: 'error',
                            statusText: 'error',
                            success: false,
                            outputText: payload.errorText != null ? String(payload.errorText) : '',
                            subagentSessionId: payload.childSessionId,
                            subagentAgentName: payload.subagentAgentName
                        });
                        updateOpenSubagentTranscript(payload, 'error');
                    }
                } catch (_) {
                }
            });

            es.addEventListener('status', (e) => {
                try {
                    const payload = parseStreamPayload(e);
                    const st = (payload && payload.status != null) ? payload.status : (e.data || '');
                    const liveRow = currentRow();
                    if (st && liveRow) liveRow.title = st;
                } catch (_) {
                }
            });

            es.addEventListener('context_compaction', (e) => {
                try {
                    const payload = parseStreamPayload(e) || {};
                    const id = payload && payload.id != null ? String(payload.id) : '';
                    const text = payload && payload.text != null ? String(payload.text) : '';
                    if (!id || !text) return;

                    Array.from(list.querySelectorAll('li[data-id]')).forEach(item => {
                        if (item.dataset.id === id) item.remove();
                    });

                    const compactedRow = document.createElement('li');
                    compactedRow.dataset.id = id;
                    compactedRow.dataset.system = 'true';

                    const strong = document.createElement('strong');
                    strong.textContent = 'system';

                    const textSpan = document.createElement('span');
                    textSpan.className = 'chat-message-text';

                    compactedRow.appendChild(strong);
                    compactedRow.appendChild(document.createTextNode(': '));
                    compactedRow.appendChild(textSpan);

                    const pendingRow = list.querySelector('li[data-pending="true"]');
                    if (pendingRow && pendingRow.parentNode) {
                        pendingRow.parentNode.insertBefore(compactedRow, pendingRow);
                    } else {
                        list.appendChild(compactedRow);
                    }

                    renderChatMarkdown(textSpan, text);
                    if (runtime && typeof runtime === 'object') runtime.lastMessageCount = list.children.length;

                    if (shouldStickToBottom || wasNearBottom()) {
                        requestAnimationFrame(() => {
                            try {
                                const history = document.getElementById('chat-history');
                                if (history) history.scrollTop = history.scrollHeight - history.clientHeight;
                            } catch (_) {
                            }
                        });
                    }
                } catch (_) {
                }
            });

            es.addEventListener('done', (e) => {
                try {
                    const payload = parseStreamPayload(e);
                    if (flushTimer) {
                        clearTimeout(flushTimer);
                        flushTimer = null;
                    }
                    if (rafPending) {
                        rafPending = false;
                        flushBuffer();
                    } else {
                        flushBuffer();
                    }

                    const textSpan = currentTextSpan();
                    if (payload && payload.text != null && textSpan) {
                        try {
                            const currentRaw = getRawChatMarkdown(textSpan);
                            if (String(payload.text) !== String(currentRaw)) {
                                renderChatMarkdown(textSpan, payload.text);
                            }
                            buffer = '';
                        } catch (_) {
                            if (textSpan.textContent !== payload.text) {
                                try {
                                    textSpan.textContent = payload.text;
                                } catch (_) {
                                }
                            }
                            buffer = '';
                        }
                    } else if (!gotDelta) {
                        const data = e.data || '';
                        if (data && textSpan) {
                            try {
                                const prevRaw = getRawChatMarkdown(textSpan);
                                renderChatMarkdown(textSpan, prevRaw + data);
                            } catch (_) {
                                try {
                                    textSpan.textContent = textSpan.textContent + data;
                                } catch (_) {
                                }
                            }
                        }
                        flushBuffer();
                    }

                    if (payload && Array.isArray(payload.toolCalls) && payload.toolCalls.length > 0) {
                        const doneRow = currentRow();
                        if (doneRow) {
                            payload.toolCalls.forEach(toolCall => {
                                const toolCallId = toolCall && toolCall.toolCallId != null ? String(toolCall.toolCallId).trim() : '';
                                if (toolCallId) {
                                    const existing = document.getElementById('chat-messages-list')?.querySelector('.tool-call-call[data-tool-call-id="' + toolCallId.replace(/"/g, '\\"') + '"]');
                                    if (existing) return;
                                }
                                appendToolCallToChatRow(doneRow, toolCall, processHtmxElement, {
                                    state: toolCall.success ? 'done' : 'error',
                                    statusText: toolCall.success ? 'success' : 'failure',
                                    success: Boolean(toolCall.success),
                                    outputText: toolCallOutputText(toolCall)
                                });
                            });
                        }
                    }

                    const liveRow = currentRow();
                    if (liveRow) {
                        liveRow.classList.remove('pending');
                        liveRow.removeAttribute('data-pending');
                        liveRow.dataset.streamBound = '0';
                        if (payload && payload.completedTs != null) {
                            updateChatRowCompletion(liveRow, payload.completedTs, ensureChatMessageSubtitle, runtime);
                        }
                        updateChatSendButtonState();
                    }
                } catch (_) {
                }
                try {
                    es.close();
                } catch (_) {
                }
                clearPendingStream(assistantId, es);
                refreshWorkspaceRail();
            });

            es.addEventListener('stopped', (e) => {
                try {
                    const payload = parseStreamPayload(e);
                    if (flushTimer) {
                        clearTimeout(flushTimer);
                        flushTimer = null;
                    }
                    flushBuffer();
                    const textSpan = currentTextSpan();
                    if (textSpan && payload && payload.message != null) {
                        renderChatMarkdown(textSpan, payload.message);
                    }
                    const liveRow = currentRow();
                    if (liveRow) {
                        liveRow.classList.remove('pending');
                        liveRow.removeAttribute('data-pending');
                        liveRow.dataset.streamBound = '0';
                        if (payload && payload.completedTs != null) {
                            updateChatRowCompletion(liveRow, payload.completedTs, ensureChatMessageSubtitle, runtime);
                        }
                        updateChatSendButtonState();
                    }
                } catch (_) {
                }
                try {
                    es.close();
                } catch (_) {
                }
                clearPendingStream(assistantId, es);
                refreshWorkspaceRail();
            });

            es.addEventListener('error', (e) => {
                try {
                    const rawData = e && typeof e.data === 'string' ? e.data.trim() : '';
                    const payload = parseStreamPayload(e);
                    const data = (payload && payload.message) ? payload.message : (rawData || 'Stream error');
                    if (rawData) {
                        const textSpan = currentTextSpan();
                        if (textSpan) {
                            try {
                                const prevRaw = getRawChatMarkdown(textSpan);
                                renderChatMarkdown(textSpan, prevRaw + '\n[Error: ' + data + ']');
                            } catch (_) {
                                try {
                                    textSpan.textContent = textSpan.textContent + '\n[Error: ' + data + ']';
                                } catch (_) {
                                }
                            }
                        }
                    } else {
                        if (!(e && e.error)) {
                            window.__connectionLossMonitor && window.__connectionLossMonitor.transportFailure();
                        }
                    }
                    const liveRow = currentRow();
                    if (liveRow) {
                        liveRow.classList.remove('pending');
                        liveRow.removeAttribute('data-pending');
                        liveRow.dataset.streamBound = '0';
                        if (payload && payload.completedTs != null) {
                            updateChatRowCompletion(liveRow, payload.completedTs, ensureChatMessageSubtitle, runtime);
                        }
                        updateChatSendButtonState();
                    }
                } catch (_) {
                }
                try {
                    es.close();
                } catch (_) {
                }
                clearPendingStream(assistantId, es);
                refreshWorkspaceRail();
            });

            es.addEventListener('tool_call', (e) => {
                try {
                    const payload = parseStreamPayload(e) || {};
                    const liveRow = currentRow();
                    if (!liveRow) return;
                    appendToolCallToChatRow(liveRow, payload, processHtmxElement, {
                        state: payload.success ? 'done' : 'error',
                        statusText: payload.success ? 'success' : 'failure',
                        success: Boolean(payload.success),
                        outputText: toolCallOutputText(payload)
                    });

                    const stick = shouldStickToBottom || wasNearBottom();
                    if (stick) {
                        requestAnimationFrame(() => {
                            try {
                                const history = document.getElementById('chat-history');
                                if (history) history.scrollTop = history.scrollHeight - history.clientHeight;
                                shouldStickToBottom = true;
                            } catch (_) {
                            }
                        });
                    }
                } catch (_) {
                }
            });

            es.addEventListener('close', () => {
                try {
                    flushBuffer();
                } catch (_) {
                }
                try {
                    es.close();
                } catch (_) {
                }
                try {
                    removeStreamScrollListener();
                } catch (_) {
                }
                clearPendingStream(assistantId, es);
            });

            try {
                streamHistoryEl = document.getElementById('chat-history');
                if (streamHistoryEl && streamHistoryEl.addEventListener) {
                    streamHistoryEl.addEventListener('scroll', streamScrollListener, {passive: true});
                }
            } catch (_) {
                streamHistoryEl = null;
            }

            const originalFlush = flushBuffer;
            flushBuffer = function () {
                const stickBeforeFlush = shouldStickToBottom || wasNearBottom();
                originalFlush();
                if (stickBeforeFlush) {
                    requestAnimationFrame(() => {
                        try {
                            const history = document.getElementById('chat-history');
                            if (history) history.scrollTop = history.scrollHeight - history.clientHeight;
                        } catch (_) {
                        }
                    });
                }
            };

            function removeStreamScrollListener() {
                try {
                    if (streamHistoryEl && streamHistoryEl.removeEventListener) {
                        streamHistoryEl.removeEventListener('scroll', streamScrollListener, {passive: true});
                    }
                } catch (_) {
                }
            }
        });
    } catch (_) {
    }
}

export function bindPendingStreamListenersOnce() {
    if (streamsBound) return;
    streamsBound = true;
    bindWorkspaceRailRefresh();
    bindPendingStreams();

    document.body.addEventListener('htmx:afterSwap', function () {
        const runtime = window.__chatRuntime || {};
        Promise.resolve().then(() => {
            bindPendingStreams(runtime);
            syncFaviconWithRail();
        });
    }, true);
    document.body.addEventListener('htmx:afterSettle', function () {
        const runtime = window.__chatRuntime || {};
        Promise.resolve().then(() => {
            bindPendingStreams(runtime);
            syncFaviconWithRail();
        });
    }, true);

    document.body.addEventListener('htmx:afterSwap', function (evt) {
        try {
            const target = (evt && evt.detail && evt.detail.target) || evt.target || document;
            const liveList = document.getElementById('chat-messages-list');
            const base = liveList ? liveList : (target && target.querySelector && target.querySelector('.chat-message-text') ? target : document);
            Promise.resolve().then(() => {
                renderAllChatMarkdown(base);
                formatAllChatSubtitles(base);
            });
        } catch (_) {
        }
    }, true);
    document.body.addEventListener('htmx:afterSettle', function (evt) {
        try {
            const target = (evt && evt.detail && evt.detail.target) || evt.target || document;
            const liveList = document.getElementById('chat-messages-list');
            const base = liveList ? liveList : (target && target.querySelector && target.querySelector('.chat-message-text') ? target : document);
            Promise.resolve().then(() => {
                renderAllChatMarkdown(base);
                formatAllChatSubtitles(base);
            });
        } catch (_) {
        }
    }, true);

    document.body.addEventListener('htmx:afterSwap', function () {
        const runtime = window.__chatRuntime || {};
        Promise.resolve().then(() => {
            initChatComposer(runtime);
            updateChatSendButtonState();
        });
    }, true);
    document.body.addEventListener('htmx:afterSettle', function () {
        const runtime = window.__chatRuntime || {};
        Promise.resolve().then(() => {
            initChatComposer(runtime);
            updateChatSendButtonState();
        });
    }, true);
}
