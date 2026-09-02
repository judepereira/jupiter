package com.judepereira.jupiter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.judepereira.jupiter.agent.catalog.AgentDefinitionService;
import com.judepereira.jupiter.agent.catalog.ThinkingLevel;
import com.judepereira.jupiter.persistence.Persistence.ChatMessageMetadata;
import com.judepereira.jupiter.ui.ChatPresentationService;
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
import java.util.Set;

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
                new ChatPresentationService.ChatMessage("assistant", "Thinking…", 1L, false, "assistant-1", null, List.of(),
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
                new ChatPresentationService.ChatMessage("assistant", "Done", 1L, false, "assistant-done", 2L, List.of(),
                        new ChatMessageMetadata("plan", "Plan", "openai/gpt-5.5", "HIGH"), "GPT-5.5"),
                new ChatPresentationService.ChatMessage("assistant", "Thinking…", 3L, true, "assistant-pending", null, List.of(), null)
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
                new ChatPresentationService.ChatMessage("assistant", "Broken metadata row", 1L, false, "assistant-broken", null,
                        List.of(), new ChatMessageMetadata("plan", "Plan", "openai/gpt-5.5", "HIGH"), "GPT-5.5"),
                new ChatPresentationService.ChatMessage("assistant", "Done", 3L, false, "assistant-done", 4L, List.of(),
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
                new ChatPresentationService.ChatMessage("assistant", "Done", 1L, false, "assistant-done", 2L, List.of(), null),
                new ChatPresentationService.ChatMessage("assistant", "Thinking…", 3L, true, "assistant-pending", null, List.of(), null)
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
                new ChatPresentationService.ChatMessage("assistant", "Thinking…", 1L, true, "assistant-pending", null, List.of(), null)
        ));
        context.setVariable("pendingStreamUrlPrefix", "/ui/chat/stream");
        context.setVariable("subagentView", false);

        String html = engine.process("fragments/chat-rows", context);

        assertThat(html).contains("<li", "data-id=\"assistant-pending\"", "data-stream-url=\"/ui/chat/stream/assistant-pending\"");
        assertThat(html).doesNotContain("<ul", "chat-send-form", "chat-container");
    }

    @Test
    public void chatRowsFragmentRendersInfoMessagesAsBackgroundUpdates() {
        SpringTemplateEngine engine = engine();

        WebContext context = webContext();
        context.setVariable("messages", List.of(
                new ChatPresentationService.ChatMessage("info", "Git updated workspace", 1L, false, "info-1", 1L, List.of(), null)
        ));
        context.setVariable("pendingStreamUrlPrefix", "/ui/chat/stream");
        context.setVariable("subagentView", false);

        String html = engine.process("fragments/chat-rows", context);

        assertThat(html).contains("data-role=\"info\"", "Background update", "Git updated workspace", "bi-info-circle");
        assertThat(html).doesNotContain("data-stream-url", "chat-message-subtitle");
    }

    @Test
    public void chatRowsFragmentRendersToolCallsAboveAssistantTextAndSubtitleBelow() {
        SpringTemplateEngine engine = engine();

        WebContext context = webContext();
        context.setVariable("messages", List.of(
                new ChatPresentationService.ChatMessage("assistant", "Thinking…", 1L, false, "assistant-done", 2L,
                        List.of(new ChatPresentationService.ToolCallView("tool-call-1", "read_file", true, "input", "output", false, false, null, null, null)),
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
    public void chatRowsFragmentLazilyLoadsOrdinaryPersistedToolGroupsButKeepsDisplayImagesOpen() {
        SpringTemplateEngine engine = engine();

        WebContext context = webContext();
        context.setVariable("messages", List.of(
                new ChatPresentationService.ChatMessage("assistant", "Done", 1L, false, "assistant-lazy", 2L,
                        List.of(
                                new ChatPresentationService.ToolCallView("read-1", "read_file", true, null, null, false, false, null, null, null),
                                new ChatPresentationService.ToolCallView("image-1", "display_image", true, "input", "output", false, false, null, null, null, null,
                                        "/ui/chat/image/1/image-1", "Cat", "images/cat.png", "image/png")
                        ), null, null)
        ));
        context.setVariable("pendingStreamUrlPrefix", "/ui/chat/stream");
        context.setVariable("subagentView", false);
        context.setVariable("fullMode", false);

        String html = engine.process("fragments/chat-rows", context);

        assertThat(html).contains("data-tool-call-tool-name=\"read_file\"", "hx-get=\"/ui/chat/tool-call/assistant-lazy/read-1\"",
                "hx-trigger=\"toggle once\"", "hx-target=\"this\"", "hx-swap=\"outerHTML\"");
        assertThat(html).contains("data-tool-call-tool-name=\"display_image\"", "open", "src=\"/ui/chat/image/1/image-1\"");
        assertThat(html).doesNotContain("read-1 input", "read-1 output");
    }

    @Test
    public void chatRowsFragmentLazilyLoadsPersistedTaskDetailsWithSummaryAndSessionLink() {
        SpringTemplateEngine engine = engine();

        WebContext context = webContext();
        context.setVariable("messages", List.of(
                new ChatPresentationService.ChatMessage("assistant", "Done", 1L, false, "assistant-task", 2L,
                        List.of(new ChatPresentationService.ToolCallView("task-1", "task", true, "full task input", "full task output", false, false,
                                42L, "engineer", "Engineer", "success", "<summary>")), null)
        ));
        context.setVariable("pendingStreamUrlPrefix", "/ui/chat/stream");
        context.setVariable("subagentView", false);
        context.setVariable("fullMode", false);

        String html = engine.process("fragments/chat-rows", context);

        assertThat(html).contains("<summary", "&lt;summary&gt;", "View Session", "hx-get=\"/ui/chat/subagent/42\"",
                "hx-get=\"/ui/chat/tool-call/assistant-task/task-1\"", "hx-trigger=\"toggle once\"", "hx-target=\"this\"", "hx-swap=\"outerHTML\"");
        assertThat(html).doesNotContain("full task input", "full task output", "data-tool-call-task-body", "open");
    }

    @Test
    public void chatRowsFragmentRendersRunningToolCallsWithoutFailureStyling() {
        SpringTemplateEngine engine = engine();

        WebContext context = webContext();
        context.setVariable("messages", List.of(
                new ChatPresentationService.ChatMessage("assistant", "Thinking…", 1L, false, "assistant-running", null,
                        List.of(new ChatPresentationService.ToolCallView("tool-call-1", "read_file", false, "input", "output", false, false, null, null, null, "running")),
                        null,
                        null)
        ));
        context.setVariable("pendingStreamUrlPrefix", "/ui/chat/stream");
        context.setVariable("subagentView", false);

        String html = engine.process("fragments/chat-rows", context);

        assertThat(html).contains("data-tool-call-state=\"running\"", ">running<");
        assertThat(html).doesNotContain("tool-call-status-failure");
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
                new ChatPresentationService.ChatMessage("assistant", "Thinking…", 1L, false, "assistant-1", null,
                        List.of(new ChatPresentationService.ToolCallView("tool-call-1", "task", true, "{\"agentId\": \"engineer\"}", "child final", false, false,
                                42L, "engineer", "Engineer", "done", null, null, null, null, "Implement the parser")), null)
        ));

        String html = engine.process("fragments/chat-response", context);

        assertThat(html).contains("tool-call-summary-task", "Implement the parser", "View Session", "hx-get=\"/ui/chat/subagent/42\"");
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
                new ChatPresentationService.ChatMessage("assistant", "Done", 1L, false, "assistant-1",
                        null, List.of(new ChatPresentationService.ToolCallView("tool-call-1", "display_image", true, "{\"path\": \"images/cat.png\"}", "Displayed image: images/cat.png", false, false,
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
                new ChatPresentationService.ChatMessage("assistant", "Thinking…", 1L, false, "assistant-1", null, List.of(
                        new ChatPresentationService.ToolCallView("read-1", "read_file", true, "read-1 input", "read-1 output", false, false, null, null, null),
                        new ChatPresentationService.ToolCallView("read-2", "read_file", true, "read-2 input", "read-2 output", false, false, null, null, null),
                        new ChatPresentationService.ToolCallView("task-1", "task", true, "task input", "task output", false, false, 41L, "engineer-1", "Engineer 1"),
                        new ChatPresentationService.ToolCallView("read-3", "read_file", true, "read-3 input", "read-3 output", false, false, null, null, null),
                        new ChatPresentationService.ToolCallView("display-1", "display_image", true, "display input", "display output", false, false, null, null, null, null, "/ui/chat/image/1/display-1", "Cat", "images/cat.png", "image/png")
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
                new ChatPresentationService.ChatMessage("assistant", "Thinking…", 1L, false, "assistant-1", null, List.of(
                        new ChatPresentationService.ToolCallView("read-1", "read", true, "read-1 input", "read-1 output", false, false, null, null, null),
                        new ChatPresentationService.ToolCallView("read-2", "read", true, "read-2 input", "read-2 output", false, false, null, null, null),
                        new ChatPresentationService.ToolCallView("task-1", "task", true, "task-1 input", "task-1 output", false, false, 41L, "engineer-1", "Engineer 1"),
                        new ChatPresentationService.ToolCallView("task-2", "task", true, "task-2 input", "task-2 output", false, false, 42L, "engineer-2", "Engineer 2"),
                        new ChatPresentationService.ToolCallView("read-3", "read", true, "read-3 input", "read-3 output", false, false, null, null, null)
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
                new ChatPresentationService.ChatMessage("user", "Primary task:\nwrite a file", 1L, false, "user-1", null, List.of(), null),
                new ChatPresentationService.ChatMessage("assistant", "child final", 2L, false, "assistant-1", null, List.of(), null)
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
    public void chatToolCallFragmentsRenderTaskGroupWithExactlyOneStableTaskSummary() {
        SpringTemplateEngine engine = engine();
        ChatPresentationService.ToolCallView taskCall = new ChatPresentationService.ToolCallView(
                "task-1", "task", true, "task input", "task output", false, false,
                42L, "engineer", "Engineer", "success", "Inspect the task flow");
        ChatPresentationService.ToolCallGroupView taskGroup = new ChatPresentationService.ToolCallGroupView(
                "task", "task", "success", true, 1, List.of(taskCall));
        ChatPresentationService.ChatMessage message = new ChatPresentationService.ChatMessage(
                "assistant", "Done", 1L, false, "assistant-1", 2L, List.of(taskCall), null);

        WebContext groupContext = webContext();
        groupContext.setVariable("group", taskGroup);
        groupContext.setVariable("assistantId", message.id());
        String groupHtml = engine.process("fragments/chat-tool-calls", Set.of("group"), groupContext);
        WebContext blockContext = webContext();
        blockContext.setVariable("block", ChatPresentationService.ToolCallBlockView.group(taskGroup));
        blockContext.setVariable("assistantId", message.id());
        String blockHtml = engine.process("fragments/chat-tool-calls", Set.of("block"), blockContext);

        WebContext pageContext = webContext();
        pageContext.setVariable("messages", List.of(message));
        pageContext.setVariable("pendingStreamUrlPrefix", "/ui/chat/stream");
        pageContext.setVariable("subagentView", false);
        String pageHtml = engine.process("fragments/chat-rows", pageContext);

        String summaryId = "assistant-tool-group-assistant-1-task-1-summary";
        assertThat(groupHtml).contains("tool-call-summary-task", "Engineer", ">success<", "View Session");
        assertThat(groupHtml).contains("id=\"" + summaryId + "\"");
        assertThat(groupHtml).doesNotContain("<span class=\"tool-call-name\">task</span>");
        assertThat(groupHtml.split("<summary\\b", -1).length - 1).isEqualTo(1);
        assertThat(blockHtml.split("<summary\\b", -1).length - 1).isEqualTo(1);
        assertThat(blockHtml).contains("tool-call-summary-task", "Engineer", "id=\"" + summaryId + "\"");
        assertThat(pageHtml.split("<summary\\b", -1).length - 1).isEqualTo(1);
        assertThat(pageHtml).contains("id=\"" + summaryId + "\"", "Engineer", "Inspect the task flow");
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
                new ChatPresentationService.ChatMessage("assistant", "Thinking…", 1L, false, "assistant-1", null,
                        List.of(new ChatPresentationService.ToolCallView("tool-call-1", "task", true, "{\"agentId\": \"engineer\"}", "child final", false, false,
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
