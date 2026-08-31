import {bindDynamicSubagentButton, getDirectToolCallChild, getToolCallGroups} from './dom.js';
import {normalizeToolCallName} from './identity.js';

export function buildToolCallBundleRefs(bundle) {
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


export function getToolCallBundle(container) {
    try {
        const groups = getToolCallGroups(container);
        const lastGroup = groups.length > 0 ? groups[groups.length - 1] : null;
        return lastGroup && lastGroup.classList && lastGroup.classList.contains('tool-call-bundle') ? lastGroup : null;
    } catch (_) {
        return null;
    }
}

export function createToolCallBundle(container) {
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

export function getOrCreateToolCallBundleCallsContainer(container) {
    try {
        let bundle = getToolCallBundle(container);
        if (!bundle) {
            if (!container) return null;
            bundle = document.createElement('details');
            bundle.className = 'tool-call tool-call-bundle';
            bundle.dataset.toolCallKind = 'bundle';
            container.appendChild(bundle);
            const refs = buildToolCallBundleRefs(bundle);
            if (!refs) return null;
            refs.nameSpan.textContent = 'Used';
            return refs.callsContainer;
        }
        const refs = buildToolCallBundleRefs(bundle);
        return refs ? refs.callsContainer : null;
    } catch (_) {
        return null;
    }
}

export function refreshToolCallBundleLabel(bundleRefs) {
    try {
        if (!bundleRefs || !bundleRefs.bundle) return;

        const groups = bundleRefs.callsContainer ? Array.from(bundleRefs.callsContainer.children).filter(child => child && child.classList && child.classList.contains('tool-call')) : [];
        const counts = new Map();
        const order = [];
        for (const group of groups) {
            const calls = getDirectToolCallChild(getDirectToolCallChild(group, 'tool-call-detail'), 'tool-call-calls');
            for (const call of calls ? Array.from(calls.children) : []) {
                const toolName = normalizeToolCallName(call.dataset ? call.dataset.toolCallToolName : '');
                if (!toolName) continue;
                if (!counts.has(toolName)) order.push(toolName);
                counts.set(toolName, (counts.get(toolName) || 0) + 1);
            }
        }

        const label = order.map(name => counts.get(name) > 1 ? name + ' (' + counts.get(name) + ')' : name).join(', ');
        bundleRefs.bundle.dataset.toolCallKind = 'bundle';
        bundleRefs.bundle.dataset.toolCallSummaryLabel = label ? 'Used: ' + label : 'Used';
        if (bundleRefs.nameSpan) bundleRefs.nameSpan.textContent = bundleRefs.bundle.dataset.toolCallSummaryLabel;
    } catch (_) {
    }
}

export function createToolCallGroup(container, toolName) {
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

export function buildTaskToolCallGroupRefs(group) {
    try {
        if (!group) return null;

        group.classList.add('task-tool-call');

        let summary = getDirectToolCallChild(group, 'tool-call-summary');
        if (!summary) {
            summary = document.createElement('summary');
            summary.className = 'tool-call-summary tool-call-summary-task';
            group.appendChild(summary);
        } else {
            summary.classList.add('tool-call-summary-task');
        }

        let main = summary.querySelector('.tool-call-summary-main');
        if (!main) {
            main = document.createElement('div');
            main.className = 'tool-call-summary-main';
            summary.appendChild(main);
        }

        let head = main.querySelector('.tool-call-summary-head');
        if (!head) {
            head = document.createElement('div');
            head.className = 'tool-call-summary-head';
            main.appendChild(head);
        }

        let nameSpan = head.querySelector('.tool-call-name');
        if (!nameSpan) {
            nameSpan = document.createElement('span');
            nameSpan.className = 'tool-call-name';
            head.appendChild(nameSpan);
        }

        let statusSpan = head.querySelector('.tool-call-status');
        if (!statusSpan) {
            statusSpan = document.createElement('span');
            statusSpan.className = 'tool-call-status';
            head.appendChild(statusSpan);
        }

        let taskBody = main.querySelector('.tool-call-summary-task-body');
        if (!taskBody) {
            taskBody = document.createElement('div');
            taskBody.className = 'tool-call-summary-task-body';
            main.appendChild(taskBody);
        }
        const existingTaskBody = String(taskBody.textContent || '').trim();
        if (existingTaskBody && group.dataset && !String(group.dataset.toolCallTaskBody || '').trim()) {
            group.dataset.toolCallTaskBody = existingTaskBody;
        }

        let button = summary.querySelector('.tool-call-subagent-button');
        const createdButton = !button;
        if (!button) {
            button = document.createElement('button');
            button.type = 'button';
            button.className = 'btn btn-outline-primary btn-sm d-inline-flex align-items-center gap-1 tool-call-subagent-button';
            button.dataset.dynamicSubagentButton = 'true';
            summary.appendChild(button);
        }
        button.setAttribute('hx-trigger', 'click');
        if (button.dataset.dynamicSubagentButton === 'true') {
            bindDynamicSubagentButton(button);
        } else if (createdButton) {
            button.dataset.dynamicSubagentButton = 'true';
            bindDynamicSubagentButton(button);
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

        return {group, summary, nameSpan, statusSpan, detail, callsContainer, taskBody, button};
    } catch (_) {
        return null;
    }
}

export function buildToolCallGroupRefs(group) {
    try {
        if (!group) return null;

        if (normalizeToolCallName(group.dataset ? group.dataset.toolCallToolName : '') === 'task') {
            return buildTaskToolCallGroupRefs(group);
        }

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

export function buildToolCallCallRefs(call, processHtmxElementFn) {
    try {
        if (!call) return null;

        let subagent = getDirectToolCallChild(call, 'tool-call-subagent') || null;
        let button = subagent ? subagent.querySelector('.tool-call-subagent-button') : null;

        if (call.closest && call.closest('details.tool-call.task-tool-call')) {
            if (subagent) subagent.remove();
            subagent = null;
            button = null;
        }

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

export function toolCallGroupSummaryText(calls) {
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

export function refreshToolCallGroupSummary(groupRefs) {
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

        group.dataset.toolCallToolName = toolName;
        group.dataset.toolCallCount = String(count);
        group.dataset.toolCallState = state;
        group.dataset.toolCallSuccess = success ? 'true' : 'false';

        if (toolName === 'task') {
            const taskBody = groupRefs.taskBody || (group.querySelector && group.querySelector('.tool-call-summary-task-body')) || null;
            const subagentName = String(group.dataset.toolCallSubagentAgentName || 'task');
            const subagentSessionId = String(group.dataset.toolCallSubagentSessionId || '').trim();
            const button = groupRefs.button || (group.querySelector && group.querySelector('.tool-call-subagent-button')) || null;
            const firstCall = (groupRefs.callsContainer && groupRefs.callsContainer.children)
                ? Array.from(groupRefs.callsContainer.children).find(child => child && child.classList && child.classList.contains('tool-call-call')) || null
                : ((group.querySelector && group.querySelector('.tool-call-call')) || null);

            if (groupRefs.nameSpan) {
                groupRefs.nameSpan.textContent = subagentName;
            }
            if (taskBody) {
                const existingText = String(taskBody.textContent || '');
                const text = group.dataset.toolCallTaskBody || existingText || (firstCall && firstCall.dataset ? firstCall.dataset.toolCallTaskBody || '' : '');
                if (text) {
                    taskBody.textContent = text;
                    taskBody.hidden = !String(text || '').trim();
                    if (!group.dataset.toolCallTaskBody) {
                        group.dataset.toolCallTaskBody = text;
                    }
                } else if (!existingText.trim()) {
                    taskBody.hidden = true;
                }
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
            if (groupRefs.group) {
                groupRefs.group.dataset.toolCallState = state;
                groupRefs.group.dataset.toolCallSuccess = state === 'running' ? 'false' : (success ? 'true' : 'false');
            }
            if (button) {
                if (subagentSessionId) {
                    button.hidden = false;
                    button.setAttribute('hx-get', '/ui/chat/subagent/' + encodeURIComponent(subagentSessionId));
                    button.setAttribute('hx-target', '#chat-container');
                    button.setAttribute('hx-swap', 'outerHTML');
                    button.setAttribute('hx-trigger', 'click');
                    button.textContent = 'View Session';
                } else {
                    button.hidden = true;
                }
            }
            return;
        }

        const label = toolCallGroupSummaryText(calls) || (count > 1 ? toolName + ' (' + count + ')' : toolName);
        group.dataset.toolCallSummaryLabel = label;

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
