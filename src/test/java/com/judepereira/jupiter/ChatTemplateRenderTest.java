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
        SpringTemplateEngine engine = engine();
        WebContext context = webContext();
        context.setVariable("shellRefresh", false);
        context.setVariable("reviewOob", false);
        context.setVariable("reviewPanelOpen", false);
        context.setVariable("changedFiles", List.of());
        context.setVariable("reviewSource", null);
        context.setVariable("selectedFile", null);
        context.setVariable("newChatMessages", List.of(
                new UiController.ChatMessage("assistant", "Thinking…", 1L, false, "assistant-1",
                        List.of(new UiController.ToolCallView("tool-call-1", "task", true, "{\"agentId\": \"engineer\"}", "child final", false, false,
                                42L, "engineer", "Engineer")), null)
        ));

        String html = engine.process("fragments/chat-response", context);

        assertThat(html).contains("Open subagent:", "Engineer", "hx-get=\"/ui/chat/subagent/42\"");
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
                        List.of(new UiController.ToolCallView("tool-call-1", "display_image", true, "{\"path\": \"images/cat.png\"}", "Displayed image: images/cat.png", false, false,
                                null, null, null, null, "/ui/chat/image/1/tool-call-1", "Cat", "images/cat.png", "image/png")), null)
        ));

        String html = engine.process("fragments/chat-response", context);

        assertThat(html).contains("<details", "open", "<img", "src=\"/ui/chat/image/1/tool-call-1\"", "alt=\"Cat\"", "images/cat.png", "tool-call-image-caption", ">Cat<");
        assertThat(html).doesNotContain("Displayed image: images/cat.png");
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
