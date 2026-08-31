import {toolCallInputText} from './payload.js';

export const EXPLORATORY_TOOL_CALLS = new Set(['list_files', 'read_file', 'search_code']);
export const IMAGE_TOOL_CALLS = new Set(['display_image']);

export function normalizeToolCallName(name) {
    return String(name == null ? '' : name).trim() || 'tool';
}

export function toolCallGroupKind(toolName) {
    if (toolName === 'task') return 'task';
    if (EXPLORATORY_TOOL_CALLS.has(toolName)) return 'exploratory';
    if (IMAGE_TOOL_CALLS.has(toolName)) return 'image';
    return 'other';
}

export function isSpecialStandaloneToolCall(toolName) {
    const kind = toolCallGroupKind(normalizeToolCallName(toolName));
    return kind === 'task' || kind === 'image';
}

export function parseToolCallList(value) {
    if (!value) return [];
    try {
        const parsed = JSON.parse(value);
        if (!Array.isArray(parsed)) return [];
        return parsed.map(item => String(item).trim()).filter(Boolean);
    } catch (_) {
        return [String(value).trim()].filter(Boolean);
    }
}

export function readToolCallValues(entry, datasetKey, legacyKey) {
    const values = parseToolCallList(entry && entry.dataset ? entry.dataset[datasetKey] : '');
    const legacyValue = entry && entry.dataset ? String(entry.dataset[legacyKey] || '').trim() : '';
    if (legacyValue && !values.includes(legacyValue)) values.push(legacyValue);
    return values;
}

export function writeToolCallValues(entry, datasetKey, values) {
    entry.dataset[datasetKey] = JSON.stringify(Array.from(new Set(values.filter(Boolean))));
}

export function toolCallKey(payload) {
    const toolName = payload && payload.toolName != null ? String(payload.toolName) : '';
    return [toolName, toolCallInputText(payload)].join('\u001f');
}

export function rememberToolCallIdentity(entry, toolCallId, key) {
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

export function canAppendToolCallEntry(entry, payload) {
    const existingName = normalizeToolCallName(entry && entry.dataset ? entry.dataset.toolCallToolName : '');
    const nextName = normalizeToolCallName(payload && payload.toolName);
    if (!existingName || !nextName) return false;

    const existingKind = entry && entry.dataset && entry.dataset.toolCallGroupKind ? entry.dataset.toolCallGroupKind : toolCallGroupKind(existingName);
    const nextKind = toolCallGroupKind(nextName);

    if (existingKind === 'task' || existingKind === 'image' || nextKind === 'task' || nextKind === 'image') return false;
    if (existingKind === 'exploratory' && nextKind === 'exploratory') return true;
    return existingName === nextName;
}

export function canCoalesceToolCallGroup(lastGroup, toolName) {
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

export function updateToolCallGroupKind(details, toolName) {
    if (!details) return;
    const kind = toolCallGroupKind(normalizeToolCallName(toolName));
    details.dataset.toolCallGroupKind = kind;
}

export function entryHasToolCallId(entry, toolCallId) {
    if (!entry || !toolCallId) return false;
    return readToolCallValues(entry, 'toolCallIds', 'toolCallId').includes(toolCallId);
}

export function entryHasToolCallKey(entry, key) {
    if (!entry || !key) return false;
    return readToolCallValues(entry, 'toolCallKeys', 'toolCallKey').includes(key);
}

export function toolCallRegistryScope(target) {
    try {
        return target && target.closest ? target.closest('li[data-id]') : null;
    } catch (_) {
        return null;
    }
}

export function toolCallRegistry(scope) {
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

export function registerToolCallEntry(scope, entry, toolCallId, key) {
    try {
        const registry = toolCallRegistry(scope);
        if (!registry || !entry) return;
        if (toolCallId) registry.byId.set(toolCallId, entry);
        if (key) registry.byKey.set(key, entry);
    } catch (_) {
    }
}

export function entryHasToolCallIdentity(entry, toolCallId, key) {
    if (toolCallId && entryHasToolCallId(entry, toolCallId)) return true;
    if (key && entryHasToolCallKey(entry, key)) return true;
    return false;
}

export function findToolCallEntry(container, payload) {
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
                if (entryHasToolCallIdentity(entry, toolCallId, key)) return entry;
            }
        }

        return null;
    } catch (_) {
        return null;
    }
}
