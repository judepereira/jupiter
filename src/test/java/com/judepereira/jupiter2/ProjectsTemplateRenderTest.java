package com.judepereira.jupiter2;

import org.junit.jupiter.api.Test;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class ProjectsTemplateRenderTest {

    @Test
    public void projectsFragmentRendersWhenNoProjectIsActive() {
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
        context.setVariable("projects", List.of());
        context.setVariable("activeProject", null);
        context.setVariable("workspaces", List.of());
        context.setVariable("activeWorkspace", null);
        context.setVariable("sessions", List.of());
        context.setVariable("activeSession", null);
        context.setVariable("selectedName", "");
        context.setVariable("selectedPath", "");
        context.setVariable("currentPath", "");
        context.setVariable("directoryEntries", List.of());
        context.setVariable("includeChatContainer", false);
        context.setVariable("reviewPanelOpen", false);
        context.setVariable("reviewOob", false);
        context.setVariable("changedFiles", List.of());
        context.setVariable("selectedFile", null);

        String html = engine.process("fragments/projects", context);

        assertThat(html).contains("No projects", "No project selected", "Add project");
    }

    @Test
    public void projectModalRendersNormalInputsWithoutOutOfBandSwaps() {
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
        context.setVariable("projects", List.of());
        context.setVariable("activeProject", null);
        context.setVariable("workspaces", List.of());
        context.setVariable("activeWorkspace", null);
        context.setVariable("sessions", List.of());
        context.setVariable("activeSession", null);
        context.setVariable("selectedName", "Home");
        context.setVariable("selectedPath", "/home/jude");
        context.setVariable("currentPath", "/home/jude");
        context.setVariable("directoryEntries", List.of());
        context.setVariable("includeChatContainer", false);
        context.setVariable("reviewPanelOpen", false);
        context.setVariable("reviewOob", false);
        context.setVariable("changedFiles", List.of());
        context.setVariable("selectedFile", null);

        String html = engine.process("fragments/projects", context);
        String nameInputHtml = inputTag(html, "project-name-input");
        String pathInputHtml = inputTag(html, "project-path-input");

        assertThat(nameInputHtml)
                .contains("id=\"project-name-input\"", "name=\"name\"", "value=\"Home\"")
                .doesNotContain("hx-swap-oob");
        assertThat(pathInputHtml)
                .contains("id=\"project-path-input\"", "name=\"path\"", "value=\"/home/jude\"")
                .doesNotContain("hx-swap-oob");
    }

    private static String inputTag(String html, String inputId) {
        int idIndex = html.indexOf("id=\"" + inputId + "\"");
        int start = html.lastIndexOf("<input", idIndex);
        int end = html.indexOf('>', idIndex);

        return html.substring(start, end + 1);
    }
}
