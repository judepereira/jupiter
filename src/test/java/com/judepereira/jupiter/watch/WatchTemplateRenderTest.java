package com.judepereira.jupiter.watch;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.thymeleaf.TemplateSpec;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

class WatchTemplateRenderTest {
    @Test
    void watchPanelRendersHistoryAndExplicitInactiveState() {
        var engine = new SpringTemplateEngine();
        var resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding(StandardCharsets.UTF_8.name());
        resolver.setCacheable(false);
        engine.setTemplateResolver(resolver);

        var context = new Context();
        context.setVariable("activeSession", null);
        context.setVariable("watchRuns", List.of());
        context.setVariable("bottomPanelHeight", 320);

        var html = engine.process(new TemplateSpec("fragments/watches", Set.of("panel"), TemplateMode.HTML, null),
                context);

        assertThat(html).contains("id=\"bottom-panel\"", "Select a primary session to view watch history.",
                "hx-post=\"/ui/panel/watches\"", "terminal-panel-divider");
    }
}
