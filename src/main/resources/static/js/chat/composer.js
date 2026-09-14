import { getHtmxRequestPath, shouldFlushDraftBeforeRequest } from "./shared.js";
import {
	clearChatDraftAutosaveState,
	flushChatDraftSave,
	invalidateChatDraftAutosaveState,
	scheduleChatDraftSave,
	syncChatDraftAutosaveStateFromTextarea,
} from "./draft-autosave.js";
import { bindAutoScrollListeners, checkAndMaybeScroll } from "./scroll.js";
import { isCommandPickerOpen, openCommandPicker } from "./commands.js";

let chatDraftFlushBound = false;
let htmxAfterOnLoadBound = false;
let htmxControlSwapHooksBound = false;
let pendingChatControlState = null;
let chatComposerConfig = {
	activePrimaryPendingAssistantRow: () => null,
	requestStopActiveChat: () => {},
	updateChatSendButtonState: () => {},
};

const chatMobileViewportQuery = window.matchMedia("(max-width: 600px)");

function isMobileChatViewport() {
	return chatMobileViewportQuery.matches;
}

export function configureChatComposer(config) {
	if (!config) return;
	if (typeof config.activePrimaryPendingAssistantRow === "function") {
		chatComposerConfig.activePrimaryPendingAssistantRow = config.activePrimaryPendingAssistantRow;
	}
	if (typeof config.requestStopActiveChat === "function") {
		chatComposerConfig.requestStopActiveChat = config.requestStopActiveChat;
	}
	if (typeof config.updateChatSendButtonState === "function") {
		chatComposerConfig.updateChatSendButtonState = config.updateChatSendButtonState;
	}
}

function captureChatControlState(form) {
	const controls = form?.querySelector("#chat-controls");
	if (!controls) return null;
	const value = (id) => controls.querySelector(id)?.value;
	return {
		agentId: value("#chat-agent-select"),
		modelId: value("#chat-model-select"),
		thinkingLevel: value("#chat-thinking-select"),
		modelExplicit: form.dataset.modelExplicit === "1",
	};
}

function restoreChatControlState(form, state) {
	if (!form || !state) return;
	const controls = form.querySelector("#chat-controls");
	if (!controls) return;
	const select = (id) => controls.querySelector(id);
	const agentSelect = select("#chat-agent-select");
	const modelSelect = select("#chat-model-select");
	const thinkingSelect = select("#chat-thinking-select");
	const hasValue = (element, value) =>
		element && Array.from(element.options).some((option) => option.value === value);

	if (hasValue(agentSelect, state.agentId)) agentSelect.value = state.agentId;
	if (hasValue(modelSelect, state.modelId)) modelSelect.value = state.modelId;
	if (hasValue(thinkingSelect, state.thinkingLevel)) thinkingSelect.value = state.thinkingLevel;

	const serverExplicit = controls.dataset.modelExplicit;
	form.dataset.modelExplicit =
		serverExplicit === "0" || serverExplicit === "1"
			? serverExplicit
			: state.modelExplicit
				? "1"
				: "0";
	if (hasValue(agentSelect, state.agentId) && hasValue(modelSelect, state.modelId)) {
		form.dataset.modelExplicit = state.modelExplicit ? "1" : "0";
	}
}

function bindChatControlSwapHooks() {
	if (htmxControlSwapHooksBound) return;
	htmxControlSwapHooksBound = true;
	const beforeSwap = (event) => {
		const target = event.detail?.target;
		if (target?.id !== "chat-controls") return;
		pendingChatControlState = captureChatControlState(document.getElementById("chat-send-form"));
	};
	const afterSwap = (event) => {
		const target = event.detail?.target;
		if (target?.id !== "chat-controls") return;
		const form = document.getElementById("chat-send-form");
		restoreChatControlState(form, pendingChatControlState);
		pendingChatControlState = null;
		if (form) bindChatControlListeners(form);
	};
	// OOB swaps have their own lifecycle; keep this generic so settings and other refreshes share it.
	document.body.addEventListener("htmx:beforeSwap", beforeSwap, true);
	document.body.addEventListener("htmx:afterSwap", afterSwap, true);
	document.body.addEventListener("htmx:oobBeforeSwap", beforeSwap, true);
	document.body.addEventListener("htmx:oobAfterSwap", afterSwap, true);
}

function bindChatDraftFlushListeners() {
	if (chatDraftFlushBound) return;
	chatDraftFlushBound = true;

	document.body.addEventListener(
		"htmx:beforeRequest",
		function (evt) {
			try {
				const path = getHtmxRequestPath(evt);
				if (String(path || "").includes("/ui/chat/send")) {
					invalidateChatDraftAutosaveState();
					return;
				}
				if (!shouldFlushDraftBeforeRequest(path)) return;
				flushChatDraftSave({ keepalive: true, useBeacon: true });
			} catch (_) {}
		},
		true,
	);
}

function getChatSelectOption(select) {
	if (!select) return null;
	return select.options ? select.options[select.selectedIndex] : null;
}

function syncChatDefaults(form) {
	const agentSelect = form && form.querySelector("#chat-agent-select");
	const modelSelect = form && form.querySelector("#chat-model-select");
	const thinkingSelect = form && form.querySelector("#chat-thinking-select");
	if (!agentSelect || !modelSelect || !thinkingSelect) return;

	const agentOption = getChatSelectOption(agentSelect);
	if (!agentOption || !agentOption.dataset) return;

	const resolvedModel = agentOption.dataset.defaultModel;
	if (
		resolvedModel &&
		Array.from(modelSelect.options).some((option) => option.value === resolvedModel)
	) {
		modelSelect.value = resolvedModel;
		form.dataset.modelExplicit = "0";
	} else if (modelSelect.options.length > 0) {
		// No compatible preference is available: keep the picker fallback explicit.
		modelSelect.selectedIndex = 0;
		form.dataset.modelExplicit = "1";
	}
	thinkingSelect.value = agentOption.dataset.defaultThinking;
}

function bindChatControlListeners(form) {
	if (!form) return;
	const controls = form.querySelector("#chat-controls");
	if (!controls) return;
	if (form.dataset.chatControlsBound !== "1") {
		form.dataset.chatControlsBound = "1";
		const initialExplicit = controls.dataset.modelExplicit;
		form.dataset.modelExplicit =
			initialExplicit === "true" ? "1" : initialExplicit || form.dataset.modelExplicit || "0";
		form.addEventListener("htmx:configRequest", (event) => {
			const agentOption = getChatSelectOption(form.querySelector("#chat-agent-select"));
			const modelSelect = form.querySelector("#chat-model-select");
			const parameters = event.detail && event.detail.parameters;
			if (!parameters || !agentOption || !modelSelect) return;
			if (
				agentOption.dataset.defaultModel === modelSelect.value &&
				form.dataset.modelExplicit !== "1"
			) {
				delete parameters.modelId;
			}
		});
	}
	if (controls.dataset.chatControlsBound === "1") return;
	controls.dataset.chatControlsBound = "1";

	const agentSelect = form.querySelector("#chat-agent-select");
	const modelSelect = form.querySelector("#chat-model-select");
	modelSelect?.addEventListener("change", () => {
		form.dataset.modelExplicit = "1";
	});
	if (!agentSelect) return;

	agentSelect.addEventListener("change", () => syncChatDefaults(form));
}

function bindChatSubmitStopListener(form) {
	if (!form || form.dataset.chatStopBound === "1") return;
	form.dataset.chatStopBound = "1";
	form.addEventListener(
		"submit",
		(event) => {
			if (!chatComposerConfig.activePrimaryPendingAssistantRow()) return;
			event.preventDefault();
			event.stopPropagation();
			chatComposerConfig.requestStopActiveChat();
		},
		true,
	);
}

export function resizeChatTextarea(textarea) {
	if (!textarea) return;
	textarea.style.height = "auto";
	const sh = textarea.scrollHeight;
	textarea.style.height = sh + "px";

	const cs = getComputedStyle(textarea);
	const maxH = cs.maxHeight;
	if (maxH && maxH !== "none") {
		const maxVal = parseFloat(maxH);
		if (!isNaN(maxVal) && sh > maxVal) {
			textarea.style.overflowY = "auto";
		} else {
			textarea.style.overflowY = "hidden";
		}
	} else {
		textarea.style.overflowY = "";
	}
}

function insertChatTextareaNewline(textarea) {
	if (!textarea) return;
	const start =
		typeof textarea.selectionStart === "number" ? textarea.selectionStart : textarea.value.length;
	const end =
		typeof textarea.selectionEnd === "number" ? textarea.selectionEnd : textarea.value.length;
	if (typeof textarea.setRangeText === "function") {
		textarea.setRangeText("\n", start, end, "end");
	} else {
		const value = textarea.value || "";
		textarea.value = value.slice(0, start) + "\n" + value.slice(end);
		const cursor = start + 1;
		if (typeof textarea.setSelectionRange === "function") {
			textarea.setSelectionRange(cursor, cursor);
		}
	}
	textarea.dispatchEvent(new Event("input", { bubbles: true }));
}

function bindHtmxAfterOnLoadListener() {
	if (htmxAfterOnLoadBound) return;
	htmxAfterOnLoadBound = true;
	document.body.addEventListener(
		"htmx:afterOnLoad",
		function (evt) {
			try {
				const detail = evt && evt.detail;
				if (!detail || !detail.xhr) return;
				const path = detail.path || (detail.xhr && detail.xhr.responseURL) || "";
				if (!path) return;
				if (!path.includes("/ui/chat/send")) return;

				const textarea = document.getElementById("chat-input");
				if (!textarea) return;
				textarea.value = "";
				textarea.dataset.chatSlashRestoredValue = "";
				resizeChatTextarea(textarea);
				clearChatDraftAutosaveState("");
			} catch (_) {}
		},
		true,
	);
}

export function initChatComposer() {
	try {
		bindChatControlSwapHooks();
		const form = document.getElementById("chat-send-form");
		const textarea = document.getElementById("chat-input");
		if (!form || !textarea) return;

		const isFreshComposer = textarea.dataset.chatBound !== "1";
		syncChatDraftAutosaveStateFromTextarea(textarea, isFreshComposer);

		// Avoid double-binding when initializer is rerun for HTMX swaps.
		if (isFreshComposer) {
			textarea.dataset.chatBound = "1";
			textarea.dataset.chatSlashRestoredValue =
				textarea.value && textarea.value.startsWith("/") ? textarea.value : "";

			function onKeyDown(e) {
				if (isCommandPickerOpen()) {
					return;
				}
				const isEnter = e.key === "Enter" || e.keyCode === 13;
				if (!isEnter) return;
				if (e.isComposing) return;

				if (e.altKey || isMobileChatViewport()) {
					e.preventDefault();
					insertChatTextareaNewline(textarea);
					return;
				}

				e.preventDefault();
				if (chatComposerConfig.activePrimaryPendingAssistantRow()) {
					return;
				}
				if (typeof form.requestSubmit === "function") {
					form.requestSubmit();
				} else {
					form.submit();
				}
			}

			textarea.addEventListener("input", () => {
				resizeChatTextarea(textarea);
				scheduleChatDraftSave(textarea.value);
			});
			textarea.addEventListener("keydown", onKeyDown);
			textarea.addEventListener("beforeinput", (event) => {
				if (isCommandPickerOpen()) return;
				if (textarea.selectionStart !== 0 || textarea.selectionEnd !== 0 || textarea.value) return;
				if (event.inputType !== "insertText" || event.data !== "/") return;
				event.preventDefault();
				openCommandPicker(textarea, "/");
			});
			textarea.addEventListener("input", () => {
				if (isCommandPickerOpen()) return;
				if (!textarea.value.startsWith("/")) {
					textarea.dataset.chatSlashRestoredValue = "";
					return;
				}
				if (textarea.dataset.chatSlashRestoredValue === textarea.value) return;
				openCommandPicker(textarea, textarea.value);
			});
			bindHtmxAfterOnLoadListener();
		}

		requestAnimationFrame(() => resizeChatTextarea(textarea));
		bindChatControlListeners(form);
		bindChatSubmitStopListener(form);
		bindAutoScrollListeners();
		bindChatDraftFlushListeners();
		checkAndMaybeScroll();
		chatComposerConfig.updateChatSendButtonState();
	} catch (_) {}
}
