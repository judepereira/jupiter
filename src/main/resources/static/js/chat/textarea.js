export function isMobileChatViewport() {
    return window.matchMedia('(max-width: 600px)').matches;
}

export function insertChatTextareaNewline(textarea) {
    if (!textarea) return;
    const start = typeof textarea.selectionStart === 'number' ? textarea.selectionStart : textarea.value.length;
    const end = typeof textarea.selectionEnd === 'number' ? textarea.selectionEnd : textarea.value.length;
    if (typeof textarea.setRangeText === 'function') {
        textarea.setRangeText('\n', start, end, 'end');
    } else {
        const value = textarea.value || '';
        textarea.value = value.slice(0, start) + '\n' + value.slice(end);
        const cursor = start + 1;
        if (typeof textarea.setSelectionRange === 'function') {
            textarea.setSelectionRange(cursor, cursor);
        }
    }
    textarea.dispatchEvent(new Event('input', {bubbles: true}));
}

export function resizeChatTextarea(textarea) {
    if (!textarea) return;
    textarea.style.height = 'auto';
    const sh = textarea.scrollHeight;
    textarea.style.height = sh + 'px';

    const cs = getComputedStyle(textarea);
    const maxH = cs.maxHeight;
    if (maxH && maxH !== 'none') {
        const maxVal = parseFloat(maxH);
        if (!isNaN(maxVal) && sh > maxVal) {
            textarea.style.overflowY = 'auto';
        } else {
            textarea.style.overflowY = 'hidden';
        }
    } else {
        textarea.style.overflowY = '';
    }
}
