package com.judepereira.jupiter2;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.judepereira.jupiter2.agent.catalog.AgentDefinitionService;
import com.judepereira.jupiter2.agent.catalog.ThinkingLevel;
import com.judepereira.jupiter2.persistence.Persistence.ChatMessageMetadata;
import com.judepereira.jupiter2.ui.UiController;
import com.judepereira.jupiter2.testsupport.ModelCatalogTestSupport;
import org.junit.jupiter.api.Test;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.nio.charset.StandardCharsets;
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

        Context context = new Context();
        context.setVariable("shellRefresh", false);
        context.setVariable("hasPending", false);
        context.setVariable("reviewOob", false);
        context.setVariable("chatMessages", List.of(
                new UiController.ChatMessage("assistant", "Thinking…", 1L, false, "assistant-1", List.of(),
                        new ChatMessageMetadata("plan", "Plan", "openai/gpt-5.5", "HIGH"))
        ));
        context.setVariable("agents", agentService.list());
        context.setVariable("models", modelService.list());
        context.setVariable("thinkingLevels", List.of(ThinkingLevel.values()));
        context.setVariable("selectedAgent", agentService.getRequired("plan"));
        context.setVariable("selectedModel", modelService.getRequired("openai/gpt-5.5"));
        context.setVariable("selectedThinking", ThinkingLevel.HIGH);

        String html = engine.process("fragments/chat", context);

        assertThat(html).contains("id=\"chat-agent-select\"", "id=\"chat-model-select\"", "id=\"chat-thinking-select\"");
        assertThat(html).contains("class=\"chat-message-meta\"", "class=\"chat-meta-chip\"", "Plan (plan)", "GPT-5.5", "HIGH");
    }
}
