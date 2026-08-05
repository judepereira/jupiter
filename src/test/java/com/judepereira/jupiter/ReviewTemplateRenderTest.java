package com.judepereira.jupiter;

import com.judepereira.jupiter.persistence.Persistence.ChangedFileView;
import com.judepereira.jupiter.persistence.Persistence.ReviewSource;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class ReviewTemplateRenderTest {

    @Test
    public void reviewPanelRendersSelectedFileDiffOnlyForSelectedRow() {
        SpringTemplateEngine engine = engine();

        WebContext context = webContext();
        context.setVariable("reviewPanelOpen", true);
        context.setVariable("reviewOob", false);
        context.setVariable("reviewSource", ReviewSource.GIT);
        context.setVariable("changedFiles", List.of(
                new ChangedFileView("git:alpha.txt", ReviewSource.GIT, null, "alpha.txt", "diff alpha"),
                new ChangedFileView("git:beta.txt", ReviewSource.GIT, null, "beta.txt", "diff beta")
        ));
        context.setVariable("selectedFile", new ChangedFileView("git:alpha.txt", ReviewSource.GIT, null, "alpha.txt", "diff alpha"));

        String html = engine.process("fragments/review", context);

        assertThat(html).contains("hx-target=\"#review\"", "hx-swap=\"outerHTML\"");
        assertThat(html).contains("close=true", "close=false");
        assertThat(html).contains("alpha.txt", "diff alpha", "beta.txt");
        assertThat(html).doesNotContain("No file selected");
        assertThat(html).contains("class=\"file-diff-container\"");
        assertThat(html.split("class=\"file-diff-container\"", -1).length - 1).isEqualTo(1);
        assertThat(html.indexOf("diff alpha")).isGreaterThan(html.indexOf("alpha.txt"));
        assertThat(html.indexOf("diff alpha")).isLessThan(html.indexOf("beta.txt"));
    }

    @Test
    public void reviewPanelDoesNotRenderInlineDiffWhenNothingIsSelected() {
        SpringTemplateEngine engine = engine();

        WebContext context = webContext();
        context.setVariable("reviewPanelOpen", true);
        context.setVariable("reviewOob", false);
        context.setVariable("reviewSource", ReviewSource.GIT);
        context.setVariable("changedFiles", List.of(
                new ChangedFileView("git:alpha.txt", ReviewSource.GIT, null, "alpha.txt", "diff alpha"),
                new ChangedFileView("git:beta.txt", ReviewSource.GIT, null, "beta.txt", "diff beta")
        ));

        String html = engine.process("fragments/review", context);

        assertThat(html).doesNotContain("No file selected", "class=\"file-diff-container\"");
    }

    @Test
    public void reviewAndChatPanelsShareTheConversationBackgroundVariable() throws Exception {
        String css = Files.readString(Path.of("src/main/resources/static/app.css"));

        assertThat(css).contains(".review-panel", "background: var(--conversation-panel-bg);");
        assertThat(css).contains("#chat-history.messages", "background: var(--conversation-panel-bg);");
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
}
