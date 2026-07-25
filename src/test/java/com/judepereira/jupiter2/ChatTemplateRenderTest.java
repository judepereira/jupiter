package com.judepereira.jupiter2;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.judepereira.jupiter2.agent.catalog.AgentDefinitionService;
import com.judepereira.jupiter2.agent.catalog.ThinkingLevel;
import com.judepereira.jupiter2.persistence.Persistence.ChatMessageMetadata;
import com.judepereira.jupiter2.ui.UiController;
import com.judepereira.jupiter2.testsupport.ModelCatalogTestSupport;
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
import java.util.regex.Pattern;
import java.util.Locale;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class ChatTemplateRenderTest {

    @Test
    public void chatFragmentRendersSelectorsAndAssistantMetadata() {
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
                new UiController.ChatMessage("assistant", "Thinking…", 1L, false, "assistant-1", List.of(),
                        new ChatMessageMetadata("plan", "Plan", "openai/gpt-5.5", "HIGH"))
        ));
        context.setVariable("agents", agentService.listPrimaryAgents());
        context.setVariable("models", modelService.list());
        context.setVariable("thinkingLevels", List.of(ThinkingLevel.values()));
        context.setVariable("selectedAgent", agentService.getRequired("plan"));
        context.setVariable("selectedModel", modelService.getRequired("openai/gpt-5.5"));
        context.setVariable("selectedThinking", ThinkingLevel.HIGH);

        String html = engine.process("fragments/chat", context);

        assertThat(html).contains("id=\"chat-agent-select\"", "id=\"chat-model-select\"", "id=\"chat-thinking-select\"");
        assertThat(html).contains("class=\"chat-message-meta\"", "class=\"chat-meta-chip\"", "Plan (plan)", "Engineer", "GPT-5.5", "HIGH");
        assertThat(html).doesNotContain("Explore");
    }

    @Test
    public void chatResponseFragmentRendersOpenSubagentLinkForTaskToolTraces() {
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
        context.setVariable("reviewOob", false);
        context.setVariable("reviewPanelOpen", false);
        context.setVariable("changedFiles", List.of());
        context.setVariable("reviewSource", null);
        context.setVariable("selectedFile", null);
        context.setVariable("newChatMessages", List.of(
                new UiController.ChatMessage("assistant", "Thinking…", 1L, false, "assistant-1",
                        List.of(new UiController.ToolCallView("task", true, "{\"agentId\": \"engineer\"}", "child final", false, false,
                                42L, "engineer", "Engineer")), null)
        ));

        String html = engine.process("fragments/chat-response", context);

        assertThat(html).contains("Open subagent:", "Engineer", "hx-get=\"/ui/chat/subagent/42\"");
    }

    @Test
    public void chatResponseFragmentGroupsOnlyContiguousToolCalls() {
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
        context.setVariable("reviewOob", false);
        context.setVariable("reviewPanelOpen", false);
        context.setVariable("changedFiles", List.of());
        context.setVariable("reviewSource", null);
        context.setVariable("selectedFile", null);
        context.setVariable("newChatMessages", List.of(
                new UiController.ChatMessage("assistant", "Thinking…", 1L, false, "assistant-1", List.of(
                        new UiController.ToolCallView("read", true, "read-1 input", "read-1 output", false, false, null, null, null),
                        new UiController.ToolCallView("read", true, "read-2 input", "read-2 output", false, false, null, null, null),
                        new UiController.ToolCallView("task", true, "task input", "task output", false, false, null, null, null),
                        new UiController.ToolCallView("read", true, "read-3 input", "read-3 output", false, false, null, null, null)
                ), null)
        ));

        String html = engine.process("fragments/chat-response", context);

        assertThat(html.split("class=\"tool-call\"", -1)).hasSize(4);
        assertThat(html).contains("read (2)", "<span class=\"tool-call-name\">task</span>");
        assertThat(html.split(Pattern.quote("read (2)"), -1)).hasSize(2);
        assertThat(html.split(Pattern.quote("<span class=\"tool-call-name\">read</span>"), -1)).hasSize(2);
        assertThat(html).contains("read-1 input", "read-1 output", "read-2 input", "read-2 output", "task input", "task output", "read-3 input", "read-3 output");
    }

    @Test
    public void chatResponseFragmentDoesNotCollapseConsecutiveTaskToolCalls() {
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
        context.setVariable("reviewOob", false);
        context.setVariable("reviewPanelOpen", false);
        context.setVariable("changedFiles", List.of());
        context.setVariable("reviewSource", null);
        context.setVariable("selectedFile", null);
        context.setVariable("newChatMessages", List.of(
                new UiController.ChatMessage("assistant", "Thinking…", 1L, false, "assistant-1", List.of(
                        new UiController.ToolCallView("read", true, "read-1 input", "read-1 output", false, false, null, null, null),
                        new UiController.ToolCallView("read", true, "read-2 input", "read-2 output", false, false, null, null, null),
                        new UiController.ToolCallView("task", true, "task-1 input", "task-1 output", false, false, 41L, "engineer-1", "Engineer 1"),
                        new UiController.ToolCallView("task", true, "task-2 input", "task-2 output", false, false, 42L, "engineer-2", "Engineer 2"),
                        new UiController.ToolCallView("read", true, "read-3 input", "read-3 output", false, false, null, null, null)
                ), null)
        ));

        String html = engine.process("fragments/chat-response", context);

        long toolCallCount = html.split("class=\"tool-call\"", -1).length - 1;
        assertThat(toolCallCount).isEqualTo(4);
        assertThat(html.split(Pattern.quote("read (2)"), -1)).hasSize(2);
        assertThat(html).doesNotContain("task (2)");
        assertThat(html).contains("task-1 input", "task-1 output", "task-2 input", "task-2 output");
        assertThat(html).contains("Open subagent: <strong>Engineer 1</strong>", "Open subagent: <strong>Engineer 2</strong>");
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
                new UiController.ChatMessage("user", "Primary task:\nwrite a file", 1L, false, "user-1", List.of(), null),
                new UiController.ChatMessage("assistant", "child final", 2L, false, "assistant-1", List.of(), null)
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
