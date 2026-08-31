package com.judepereira.jupiter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.judepereira.jupiter.agent.catalog.AgentDefinitionService;
import com.judepereira.jupiter.agent.catalog.ThinkingLevel;
import com.judepereira.jupiter.persistence.Persistence.ChatMessageMetadata;
import com.judepereira.jupiter.ui.UiController;
import com.judepereira.jupiter.testsupport.ModelCatalogTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

public class ChatTemplateRenderTest {

    @Test
    public void chatFragmentRendersSelectorsAndAssistantMetadata() {
        AgentDefinitionService agentService = new AgentDefinitionService(new ObjectMapper());
        var modelService = ModelCatalogTestSupport.modelCatalogService();

        SpringTemplateEngine engine = engine();

        WebContext context = webContext();
        context.setVariable("shellRefresh", false);
        context.setVariable("hasPending", false);
        context.setVariable("reviewOob", false);
        context.setVariable("reviewPanelOpen", false);
        context.setVariable("subagentView", false);
        context.setVariable("changedFiles", List.of());
        context.setVariable("reviewSource", null);
        context.setVariable("selectedFile", null);
        context.setVariable("chatMessages", List.of(
                new UiController.ChatMessage("assistant", "Thinking…", 1L, false, "assistant-1", null, List.of(),
                        new ChatMessageMetadata("plan", "Plan", "openai/gpt-5.5", "HIGH"), "GPT-5.5")
        ));
        context.setVariable("agents", agentService.listPrimaryAgents());
        context.setVariable("models", modelService.list());
        context.setVariable("thinkingLevels", List.of(ThinkingLevel.values()));
        context.setVariable("selectedAgent", agentService.getRequired("plan"));
        context.setVariable("selectedModel", modelService.getRequired("openai/gpt-5.5"));
        context.setVariable("selectedThinking", ThinkingLevel.HIGH);

        String html = engine.process("fragments/chat", context);

        assertThat(html).contains("id=\"chat-agent-select\"", "id=\"chat-model-select\"", "id=\"chat-thinking-select\"");
        assertThat(html).contains("class=\"chat-message-subtitle\"", "data-agent-label=\"Plan\"", "data-agent-id=\"plan\"", "data-model-id=\"openai/gpt-5.5\"", "data-model-label=\"GPT-5.5\"", "data-thinking-level=\"HIGH\"");
        assertThat(html).doesNotContain("chat-message-meta", "chat-meta-chip", "Explore");
    }

    @Test
    public void chatFragmentRendersCompletedAssistantSubtitleButNotPendingOne() {
        SpringTemplateEngine engine = engine();

        WebContext context = webContext();
        context.setVariable("shellRefresh", false);
        context.setVariable("hasPending", false);
        context.setVariable("reviewOob", false);
        context.setVariable("reviewPanelOpen", false);
        context.setVariable("subagentView", false);
        context.setVariable("changedFiles", List.of());
        context.setVariable("reviewSource", null);
        context.setVariable("selectedFile", null);
        context.setVariable("chatMessages", List.of(
                new UiController.ChatMessage("assistant", "Done", 1L, false, "assistant-done", 2L, List.of(),
                        new ChatMessageMetadata("plan", "Plan", "openai/gpt-5.5", "HIGH"), "GPT-5.5"),
                new UiController.ChatMessage("assistant", "Thinking…", 3L, true, "assistant-pending", null, List.of(), null)
        ));

        String html = engine.process("fragments/chat", context);

        assertThat(html).contains("data-completed-ts=\"2\"");
        assertThat(html).contains("assistant-done", "data-model-id=\"openai/gpt-5.5\"", "data-model-label=\"GPT-5.5\"");
        assertThat(html).doesNotContain("assistant-pending\" data-completed-ts");
        assertThat(html).contains("chat-message-subtitle");
        assertThat(html).doesNotContain("assistant-pending\" data-completed-ts");
    }

    @Test
    public void chatFragmentOnlyRendersForkButtonForCompletedPrimaryAssistantMessages() {
        SpringTemplateEngine engine = engine();

        WebContext context = webContext();
        context.setVariable("shellRefresh", false);
        context.setVariable("hasPending", false);
        context.setVariable("reviewOob", false);
        context.setVariable("reviewPanelOpen", false);
        context.setVariable("subagentView", false);
        context.setVariable("changedFiles", List.of());
        context.setVariable("reviewSource", null);
        context.setVariable("selectedFile", null);
        context.setVariable("chatMessages", List.of(
                new UiController.ChatMessage("assistant", "Broken metadata row", 1L, false, "assistant-broken", null,
                        List.of(), new ChatMessageMetadata("plan", "Plan", "openai/gpt-5.5", "HIGH"), "GPT-5.5"),
                new UiController.ChatMessage("assistant", "Done", 3L, false, "assistant-done", 4L, List.of(),
                        new ChatMessageMetadata("plan", "Plan", "openai/gpt-5.5", "HIGH"), "GPT-5.5")
        ));

        String html = engine.process("fragments/chat", context);

        String brokenRow = html.substring(html.indexOf("assistant-broken"), html.indexOf("</li>", html.indexOf("assistant-broken")));
        assertThat(brokenRow).doesNotContain("chat-message-fork-button", "hx-post=\"/ui/chat/fork/assistant-broken\"");
        assertThat(html).contains("assistant-done", "Fork", "hx-post=\"/ui/chat/fork/assistant-done\"", "hx-target=\"#shell\"", "hx-swap=\"none\"");
    }

    @Test
    public void chatResponseFragmentRendersCompletedAssistantSubtitleButNotPendingOne() {
        SpringTemplateEngine engine = engine();

        WebContext context = webContext();
        context.setVariable("shellRefresh", false);
        context.setVariable("reviewOob", false);
        context.setVariable("reviewPanelOpen", false);
        context.setVariable("changedFiles", List.of());
        context.setVariable("reviewSource", null);
        context.setVariable("selectedFile", null);
        context.setVariable("newChatMessages", List.of(
                new UiController.ChatMessage("assistant", "Done", 1L, false, "assistant-done", 2L, List.of(), null),
                new UiController.ChatMessage("assistant", "Thinking…", 3L, true, "assistant-pending", null, List.of(), null)
        ));

        String html = engine.process("fragments/chat-response", context);

        assertThat(html).contains("data-completed-ts=\"2\"");
        assertThat(html).doesNotContain("assistant-pending\" data-completed-ts");
    }

    @Test
    public void chatRowsFragmentRendersOnlyLiRows() {
        SpringTemplateEngine engine = engine();

        WebContext context = webContext();
        context.setVariable("messages", List.of(
                new UiController.ChatMessage("assistant", "Thinking…", 1L, true, "assistant-pending", null, List.of(), null)
        ));
        context.setVariable("pendingStreamUrlPrefix", "/ui/chat/stream");
        context.setVariable("subagentView", false);

        String html = engine.process("fragments/chat-rows", context);

        assertThat(html).contains("<li", "data-id=\"assistant-pending\"", "data-stream-url=\"/ui/chat/stream/assistant-pending\"");
        assertThat(html).doesNotContain("<ul", "chat-send-form", "chat-container");
    }

    @Test
    public void chatRowsFragmentRendersToolCallsAboveAssistantTextAndSubtitleBelow() {
        SpringTemplateEngine engine = engine();

        WebContext context = webContext();
        context.setVariable("messages", List.of(
                new UiController.ChatMessage("assistant", "Thinking…", 1L, false, "assistant-done", 2L,
                        List.of(new UiController.ToolCallView("tool-call-1", "read_file", true, "input", "output", false, false, null, null, null)),
                        new ChatMessageMetadata("plan", "Plan", "openai/gpt-5.5", "HIGH"),
                        "GPT-5.5")
        ));
        context.setVariable("pendingStreamUrlPrefix", "/ui/chat/stream");
        context.setVariable("subagentView", false);

        String html = engine.process("fragments/chat-rows", context);

        assertThat(html).containsSubsequence("class=\"tool-calls\"", "class=\"chat-message-text\"", "class=\"chat-message-subtitle\"");
        assertThat(html).contains("data-completed-ts=\"2\"", "data-agent-label=\"Plan\"", "data-model-label=\"GPT-5.5\"");
    }

    @Test
    public void chatRowsFragmentOmitsBundleAggregateStatusButRendersNestedGroupStatus() {
        SpringTemplateEngine engine = engine();

        WebContext context = webContext();
        context.setVariable("messages", List.of(
                new UiController.ChatMessage("assistant", "Thinking…", 1L, false, "assistant-mixed", null,
                        List.of(
                                new UiController.ToolCallView("tool-call-success", "read_file", true, "input", "output", false, false, null, null, null),
                                new UiController.ToolCallView("tool-call-failure", "read_file", false, "input", "output", false, false, null, null, null)
                        ),
                        null,
                        null)
        ));
        context.setVariable("pendingStreamUrlPrefix", "/ui/chat/stream");
        context.setVariable("subagentView", false);

        String html = engine.process("fragments/chat-rows", context);

        int bundleMarker = html.indexOf("tool-call-bundle");
        int bundleStart = html.lastIndexOf("<details", bundleMarker);
        int summaryStart = html.indexOf("<summary", bundleMarker);
        int summaryEnd = html.indexOf("</summary>", summaryStart);
        assertThat(bundleMarker).isGreaterThanOrEqualTo(0);
        assertThat(bundleStart).isGreaterThanOrEqualTo(0);
        assertThat(summaryStart).isGreaterThan(bundleStart);
        assertThat(summaryEnd).isGreaterThan(summaryStart);

        String bundleOpeningAndSummary = html.substring(bundleStart, summaryEnd + "</summary>".length());
        assertThat(bundleOpeningAndSummary).doesNotContain(
                "tool-call-status", "running", "success", "failure",
                "data-tool-call-state", "data-tool-call-success");

        int nestedGroupMarker = html.indexOf("data-tool-call-tool-name=\"read_file\"", summaryEnd);
        int nestedGroupStart = html.lastIndexOf("<details", nestedGroupMarker);
        int nestedSummaryStart = html.indexOf("<summary", nestedGroupStart);
        int nestedSummaryEnd = html.indexOf("</summary>", nestedSummaryStart);
        assertThat(nestedGroupMarker).isGreaterThan(summaryEnd);
        assertThat(nestedGroupStart).isGreaterThan(bundleStart);
        assertThat(html.substring(nestedGroupStart, nestedSummaryEnd + "</summary>".length()))
                .contains("tool-call-status-failure", ">failure<");
    }

    @Test
    public void chatResponseFragmentRendersOpenSubagentLinkForTaskToolTraces() {
        SpringTemplateEngine engine = engine();
        WebContext context = webContext();
        context.setVariable("shellRefresh", false);
        context.setVariable("reviewOob", false);
        context.setVariable("reviewPanelOpen", false);
        context.setVariable("changedFiles", List.of());
        context.setVariable("reviewSource", null);
        context.setVariable("selectedFile", null);
        context.setVariable("newChatMessages", List.of(
                new UiController.ChatMessage("assistant", "Thinking…", 1L, false, "assistant-1", null,
                        List.of(new UiController.ToolCallView("tool-call-1", "task", true, "{\"agentId\": \"engineer\"}", "child final", false, false,
                                42L, "engineer", "Engineer", "done", null, null, null, null, "Implement the parser")), null)
        ));

        String html = engine.process("fragments/chat-response", context);

        assertThat(html).contains("tool-call-summary-task", "Implement the parser", "View Session", "<span class=\"tool-call-name\">Engineer</span>", "hx-get=\"/ui/chat/subagent/42\"");
        assertThat(html).doesNotContain("tool-call-call\"\n                                            class=\"tool-call-subagent");
    }

    @Test
    public void chatResponseFragmentOpensDisplayImageDetailsAndRendersImagePreview() {
        SpringTemplateEngine engine = engine();
        WebContext context = webContext();
        context.setVariable("shellRefresh", false);
        context.setVariable("reviewOob", false);
        context.setVariable("reviewPanelOpen", false);
        context.setVariable("changedFiles", List.of());
        context.setVariable("reviewSource", null);
        context.setVariable("selectedFile", null);
        context.setVariable("newChatMessages", List.of(
                new UiController.ChatMessage("assistant", "Done", 1L, false, "assistant-1",
                        null, List.of(new UiController.ToolCallView("tool-call-1", "display_image", true, "{\"path\": \"images/cat.png\"}", "Displayed image: images/cat.png", false, false,
                                null, null, null, null, "/ui/chat/image/1/tool-call-1", "Cat", "images/cat.png", "image/png")), null)
        ));

        String html = engine.process("fragments/chat-response", context);

        assertThat(html).contains("<details", "open", "<img", "src=\"/ui/chat/image/1/tool-call-1\"", "alt=\"Cat\"", "images/cat.png", "tool-call-image-caption", ">Cat<");
        assertThat(html).doesNotContain("Displayed image: images/cat.png");
    }

    @Test
    public void chatResponseFragmentBundlesNormalToolCallsAndLeavesSpecialCallsStandalone() {
        SpringTemplateEngine engine = engine();

        WebContext context = webContext();
        context.setVariable("shellRefresh", false);
        context.setVariable("reviewOob", false);
        context.setVariable("reviewPanelOpen", false);
        context.setVariable("changedFiles", List.of());
        context.setVariable("reviewSource", null);
        context.setVariable("selectedFile", null);
        context.setVariable("newChatMessages", List.of(
                new UiController.ChatMessage("assistant", "Thinking…", 1L, false, "assistant-1", null, List.of(
                        new UiController.ToolCallView("read-1", "read_file", true, "read-1 input", "read-1 output", false, false, null, null, null),
                        new UiController.ToolCallView("read-2", "read_file", true, "read-2 input", "read-2 output", false, false, null, null, null),
                        new UiController.ToolCallView("task-1", "task", true, "task input", "task output", false, false, 41L, "engineer-1", "Engineer 1"),
                        new UiController.ToolCallView("read-3", "read_file", true, "read-3 input", "read-3 output", false, false, null, null, null),
                        new UiController.ToolCallView("display-1", "display_image", true, "display input", "display output", false, false, null, null, null, null, "/ui/chat/image/1/display-1", "Cat", "images/cat.png", "image/png")
                ), null)
        ));

        String html = engine.process("fragments/chat-response", context);

        int firstBundle = html.indexOf("Used: read_file (2)");
        int taskIndex = html.indexOf("data-tool-call-tool-name=\"task\"");
        int secondBundle = html.indexOf("Used: read_file", firstBundle + 1);

        assertThat(firstBundle).isGreaterThanOrEqualTo(0);
        assertThat(taskIndex).isGreaterThan(firstBundle);
        assertThat(secondBundle).isGreaterThan(taskIndex);
        assertThat(html).contains("tool-call-summary-task", "View Session", "<span class=\"tool-call-name\">Engineer 1</span>");
        assertThat(html).contains("<span class=\"tool-call-name\">display_image</span>", "src=\"/ui/chat/image/1/display-1\"", "tool-call-image-caption");
        assertThat(html).contains("read-1 input", "read-2 input", "read-3 input");

        int bundleTagStart = html.lastIndexOf("<details", firstBundle);
        int bundleTagEnd = html.indexOf(">", bundleTagStart);
        int displayImageIndex = html.indexOf("tool-call-name\">display_image");
        int displayImageTagStart = html.lastIndexOf("<details", displayImageIndex);
        int displayImageTagEnd = html.indexOf(">", displayImageTagStart);

        assertThat(html.substring(bundleTagStart, bundleTagEnd)).doesNotContain("open");
        assertThat(html.substring(displayImageTagStart, displayImageTagEnd)).contains("open");
    }

    @Test
    public void chatResponseFragmentDoesNotCollapseConsecutiveTaskToolCalls() {
        SpringTemplateEngine engine = engine();
        WebContext context = webContext();
        context.setVariable("shellRefresh", false);
        context.setVariable("reviewOob", false);
        context.setVariable("reviewPanelOpen", false);
        context.setVariable("changedFiles", List.of());
        context.setVariable("reviewSource", null);
        context.setVariable("selectedFile", null);
        context.setVariable("newChatMessages", List.of(
                new UiController.ChatMessage("assistant", "Thinking…", 1L, false, "assistant-1", null, List.of(
                        new UiController.ToolCallView("read-1", "read", true, "read-1 input", "read-1 output", false, false, null, null, null),
                        new UiController.ToolCallView("read-2", "read", true, "read-2 input", "read-2 output", false, false, null, null, null),
                        new UiController.ToolCallView("task-1", "task", true, "task-1 input", "task-1 output", false, false, 41L, "engineer-1", "Engineer 1"),
                        new UiController.ToolCallView("task-2", "task", true, "task-2 input", "task-2 output", false, false, 42L, "engineer-2", "Engineer 2"),
                        new UiController.ToolCallView("read-3", "read", true, "read-3 input", "read-3 output", false, false, null, null, null)
                ), null)
        ));

        String html = engine.process("fragments/chat-response", context);

        int firstBundle = html.indexOf("Used: read (2)");
        int firstTask = html.indexOf("data-tool-call-tool-name=\"task\"");
        int secondTask = html.indexOf("data-tool-call-tool-name=\"task\"", firstTask + 1);
        int secondBundle = html.indexOf("Used: read", firstBundle + 1);

        assertThat(firstBundle).isGreaterThanOrEqualTo(0);
        assertThat(firstTask).isGreaterThan(firstBundle);
        assertThat(secondTask).isGreaterThan(firstTask);
        assertThat(secondBundle).isGreaterThan(secondTask);
        assertThat(html).contains("tool-call-bundle");
        assertThat(html).contains("View Session", "<span class=\"tool-call-name\">Engineer 1</span>", "<span class=\"tool-call-name\">Engineer 2</span>");
        assertThat(html).contains("task-1 input", "task-1 output", "task-2 input", "task-2 output");
    }

    @Test
    public void subagentChatFragmentRendersBackButtonAndHidesComposeForm() {
        AgentDefinitionService agentService = new AgentDefinitionService(new ObjectMapper());
        var modelService = ModelCatalogTestSupport.modelCatalogService();

        SpringTemplateEngine engine = new SpringTemplateEngine();
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding(StandardCharsets.UTF_8.name());
        resolver.setCacheable(false);
        engine.setTemplateResolver(resolver);

        WebContext context = webContext();
        context.setVariable("shellRefresh", false);
        context.setVariable("hasPending", false);
        context.setVariable("reviewOob", false);
        context.setVariable("reviewPanelOpen", false);
        context.setVariable("changedFiles", List.of());
        context.setVariable("reviewSource", null);
        context.setVariable("selectedFile", null);
        context.setVariable("subagentView", true);
        context.setVariable("subagentAgentName", "Engineer");
        context.setVariable("subagentAgentId", "engineer");
        context.setVariable("subagentSessionId", 42L);
        context.setVariable("chatMessages", List.of(
                new UiController.ChatMessage("user", "Primary task:\nwrite a file", 1L, false, "user-1", null, List.of(), null),
                new UiController.ChatMessage("assistant", "child final", 2L, false, "assistant-1", null, List.of(), null)
        ));
        context.setVariable("agents", agentService.listPrimaryAgents());
        context.setVariable("models", modelService.list());
        context.setVariable("thinkingLevels", List.of(ThinkingLevel.values()));
        context.setVariable("selectedAgent", agentService.getRequired("plan"));
        context.setVariable("selectedModel", modelService.getRequired("openai/gpt-5.5"));
        context.setVariable("selectedThinking", ThinkingLevel.HIGH);

        String html = engine.process("fragments/chat", context);

        assertThat(html).contains("subagent-bar", "subagent-back-button", "Engineer", "Primary task:", "child final");
        assertThat(html).doesNotContain("id=\"chat-send-form\"");
        assertThat(html).doesNotContain("id=\"chat-agent-select\"");
    }

    @Test
    public void chatFragmentRendersCompletedTaskToolCallWithOpenSubagentButton() {
        AgentDefinitionService agentService = new AgentDefinitionService(new ObjectMapper());
        var modelService = ModelCatalogTestSupport.modelCatalogService();

        SpringTemplateEngine engine = new SpringTemplateEngine();
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding(StandardCharsets.UTF_8.name());
        resolver.setCacheable(false);
        engine.setTemplateResolver(resolver);

        WebContext context = webContext();
        context.setVariable("shellRefresh", false);
        context.setVariable("hasPending", false);
        context.setVariable("reviewOob", false);
        context.setVariable("reviewPanelOpen", false);
        context.setVariable("subagentView", false);
        context.setVariable("changedFiles", List.of());
        context.setVariable("reviewSource", null);
        context.setVariable("selectedFile", null);
        context.setVariable("chatMessages", List.of(
                new UiController.ChatMessage("assistant", "Thinking…", 1L, false, "assistant-1", null,
                        List.of(new UiController.ToolCallView("tool-call-1", "task", true, "{\"agentId\": \"engineer\"}", "child final", false, false,
                                42L, "engineer", "Engineer")), null)
        ));
        context.setVariable("agents", agentService.listPrimaryAgents());
        context.setVariable("models", modelService.list());
        context.setVariable("thinkingLevels", List.of(ThinkingLevel.values()));
        context.setVariable("selectedAgent", agentService.getRequired("plan"));
        context.setVariable("selectedModel", modelService.getRequired("openai/gpt-5.5"));
        context.setVariable("selectedThinking", ThinkingLevel.HIGH);

        String html = engine.process("fragments/chat", context);

        assertThat(html).contains("tool-call-summary-task", "View Session", "Engineer",
                "hx-get=\"/ui/chat/subagent/42\"");
        assertThat(html).doesNotContain("subagent-activities");
    }

    private static SpringTemplateEngine engine() {
        SpringTemplateEngine engine = new SpringTemplateEngine();
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding(StandardCharsets.UTF_8.name());
        resolver.setCacheable(false);
        engine.setTemplateResolver(resolver);
        return engine;
    }

    private static WebContext webContext() {
        MockServletContext servletContext = new MockServletContext();
        JakartaServletWebApplication application = JakartaServletWebApplication.buildApplication(servletContext);
        MockHttpServletRequest request = new MockHttpServletRequest(servletContext);
        request.setContextPath("");
        request.setServletPath("");
        request.setRequestURI("/");
        return new WebContext(application.buildExchange(request, new MockHttpServletResponse()), Locale.US);
    }
}
