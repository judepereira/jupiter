function focusSessionNameInput() {
    const input = document.getElementById("session-name-input");
    if (input) {
        input.focus();
    }
}

function restoreSessionCreateButton(event) {
    const input = document.getElementById("session-name-input");
    if (!input) {
        return;
    }

    const form = document.querySelector("[data-session-create-form]");
    if (!form) {
        return;
    }

    const target = event && event.target;
    if (target && target.closest && target.closest("[data-session-create-form]")) {
        return;
    }

    window.htmx.ajax("GET", "/ui/sessions/new/button", { target: form, swap: "outerHTML" });
}

document.addEventListener("htmx:after:swap", () => Promise.resolve().then(focusSessionNameInput), true);
document.addEventListener("htmx:after:settle", () => Promise.resolve().then(focusSessionNameInput), true);
document.addEventListener("click", restoreSessionCreateButton);
