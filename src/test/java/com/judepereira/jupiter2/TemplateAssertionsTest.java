package com.judepereira.jupiter2;

import com.judepereira.jupiter2.ui.UiController;
import org.junit.jupiter.api.Test;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class TemplateAssertionsTest {

    @Test
    public void chatTemplateTargetsMessagesList_forPollingAndForm() throws Exception {
        Path p = Path.of("src/main/resources/templates/fragments/chat.html");
        String s = Files.readString(p);
        // Ensure no polling attributes remain (streaming-only)
        assertThat(s).doesNotContain("/ui/chat/poll", "every 1s", "hx-get")
                .contains("id=\"chat-messages-list\"");

        // Ensure the standalone row fragment was removed to avoid evaluation when m is null
        assertThat(s).doesNotContain("th:fragment=\"row\"");

        // Ensure streaming attributes and pending markers are present and form still posts to send
        // New behavior: form appends to the messages list (hx-swap="beforeend") targeting #chat-messages-list
        assertThat(s).contains("data-stream-url", "data-pending", "class=\"chat-message-text\"")
                .contains("id=\"chat-send-form\"", "hx-post=\"/ui/chat/send\"", "hx-target=\"#chat-messages-list\"")
                .contains("hx-swap=\"beforeend\"")
                // tool-call markup should be present and use Thymeleaf text bindings
                .contains("class=\"tool-calls\"", "class=\"tool-call-name\"", "th:text=\"${call.toolName}\"", "th:text=\"${call.success} ? 'success' : 'failure'\"");
    }

    @Test
    public void appJs_contains_chat_listener_guards() throws Exception {
        Path p = Path.of("src/main/resources/static/app.js");
        String s = Files.readString(p);

        // Ensure we added a guard to avoid binding the htmx afterOnLoad listener repeatedly
        // Key behaviors to prevent streaming regressions:
        // - chat composer uses plain Enter to submit
        // - Option+Enter (altKey) keeps the newline behavior
        // - Meta/Command is no longer required for submit
        // - JSON.parse is used for payloads
        // - payload.text != null checks preserve whitespace-only deltas
        // - a bounded flush interval (setTimeout) exists
        // - flushBuffer is invoked before final replacement on done
        assertThat(s).contains("EventSource", "bindPendingStreams", "requestAnimationFrame", "streamBound", "htmx:beforeSwap",
                "Option+Enter", "altKey", "Submit on plain Enter.",
                "JSON.parse", "payload.text != null", "setTimeout", "flushBuffer",
                // Streaming auto-scroll: live state and listener lifecycle
                "shouldStickToBottom", "STREAM_BOTTOM_THRESHOLD_PX", "addEventListener('scroll'", "removeEventListener('scroll'", "stickBeforeFlush",
                // tool-call SSE event and status/classes
                "tool_call", "tool-call-status-success", "tool-call-pre", "tool-call-name");
        assertThat(s).doesNotContain("metaKey");
    }

    @Test
    public void markdown_support_present_in_templates_and_assets() throws Exception {
        Path idx = Path.of("src/main/resources/templates/index.html");
        String index = Files.readString(idx);

        // index.html should include DOMPurify and marked and load them before /app.js
        int iDom = index.indexOf("dompurify");
        int iMarked = index.indexOf("marked");
        int iApp = index.indexOf("/app.js");
        assertThat(iDom).as("dompurify present").isGreaterThanOrEqualTo(0);
        assertThat(iMarked).as("marked present").isGreaterThanOrEqualTo(0);
        assertThat(iApp).as("app.js present").isGreaterThanOrEqualTo(0);
        assertThat(iDom).isLessThan(iApp);
        assertThat(iMarked).isLessThan(iApp);

        // index toggle should target the shell-level review panel
        assertThat(index).contains("hx-target=\"#shell > #review\"");

        // app.js should expose markdown helpers and use DOMPurify/marked and dataset.rawMarkdown
        Path app = Path.of("src/main/resources/static/app.js");
        String appJs = Files.readString(app);
        assertThat(appJs).contains("renderChatMarkdown", "renderAllChatMarkdown", "getRawChatMarkdown",
                "DOMPurify.sanitize", "marked.parse", "dataset.rawMarkdown");
        // additional runtime marker and live-list re-render after HTMX swaps
        assertThat(appJs).contains("markdown-rendered", "renderAllChatMarkdown(base)", "liveList");

        // CSS should include markdown styling for chat messages (lightweight check)
        Path css = Path.of("src/main/resources/static/app.css");
        String cssS = Files.readString(css);
        // Ensure we only apply pre-wrap to non-markdown-rendered spans and avoid the old broad selector
        assertThat(cssS).contains(".chat-message-text:not(.markdown-rendered)")
                .doesNotContain("#chat-messages-list li span")
                // tool-call css selectors
                .contains(".tool-calls", ".tool-call-pre", ".tool-call-status-success");
    }

    @Test
    public void chatResponse_emits_only_rows_and_uses_fragment_param_syntax() throws Exception {
        Path p = Path.of("src/main/resources/templates/fragments/chat-response.html");
        String s = Files.readString(p);

        // Ensure we do not emit a wrapper <div> or nested <ul> for appended rows
        assertThat(s).doesNotContain("<div>", "chat-messages-list-new", "<ul")
                // ensure outdated fragment invocation syntax is not present
                .doesNotContain("row(m=${m})")
                .doesNotContain("fragments/chat :: row");
        // Ensure chat-response only emits the review fragment when it is explicitly marked OOB
        assertThat(s).contains("fragments/projects :: shellUpdates")
                .contains("<th:block th:if=\"${shellRefresh}\">")
                .contains("th:if=\"${reviewOob}\"")
                .doesNotContain("fragments/chat :: row", "chat-messages-list-new");
    }

    @Test
    public void reviewFragment_toggleIsInBandWhileChatResponseMarksReviewOob() {
        SpringTemplateEngine engine = new SpringTemplateEngine();
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding(StandardCharsets.UTF_8.name());
        resolver.setCacheable(false);
        engine.setTemplateResolver(resolver);

        Context toggleContext = new Context();
        toggleContext.setVariable("reviewPanelOpen", true);
        toggleContext.setVariable("reviewOob", false);
        toggleContext.setVariable("changedFiles", List.of());
        String toggleHtml = engine.process("fragments/review", toggleContext);
        assertThat(toggleHtml).contains("id=\"review\"").doesNotContain("hx-swap-oob");

        Context chatContext = new Context();
        chatContext.setVariable("shellRefresh", false);
        chatContext.setVariable("reviewOob", true);
        chatContext.setVariable("reviewPanelOpen", true);
        chatContext.setVariable("changedFiles", List.of());
        chatContext.setVariable("newChatMessages", List.of(new UiController.ChatMessage("user", "hi", 1L, false, "m1", List.of())));
        String chatHtml = engine.process("fragments/chat-response", chatContext);
        assertThat(chatHtml).contains("hx-swap-oob=\"outerHTML\"").contains("id=\"review\"");
    }

    @Test
    public void projectFragments_cover_tabs_modal_and_directoryTree() throws Exception {
        Path p = Path.of("src/main/resources/templates/fragments/projects.html");
        String s = Files.readString(p);
        assertThat(s).contains("id=\"top-bar\"", "class=\"project-tabs\"", "Add project")
                .contains("projectList=${projects ?: T(java.util.List).of()}", "activeProjectView=${activeProject}")
                .contains("hx-get=\"/ui/projects/new\"", "hx-target=\"#modal-root\"", "hx-swap=\"innerHTML\"")
                .contains("id=\"workspace-session-rail\"", "No project selected")
                .contains("workspaceList=${workspaces ?: T(java.util.List).of()}", "sessionList=${sessions ?: T(java.util.List).of()}")
                .contains("hx-post=@{/ui/workspaces/{id}/activate", "hx-post=@{/ui/sessions/{id}/activate")
                .contains("id=\"project-modal\"", "directory-browser-tree")
                .contains("hx-post=\"/ui/projects/add\"", "hx-target=\"#shell\"", "hx-swap=\"none\"")
                .contains("fragments/directory-list :: tree", "th:fragment=\"shellUpdates\"");
    }

    @Test
    public void directoryFragment_uses_lazy_path_loading_hooks() throws Exception {
        Path p = Path.of("src/main/resources/templates/fragments/directory-list.html");
        String s = Files.readString(p);
        assertThat(s).contains("hx-get=@{/ui/projects/directory(path=${path})}", "hx-target='closest .directory-node'", "hx-swap='outerHTML'")
                .contains("hx-get=@{/ui/projects/directory/select(path=${path})}", "class=\"directory-tree\"");
    }
}
