package com.judepereira.jupiter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.judepereira.jupiter.agent.catalog.AgentDefinition;
import com.judepereira.jupiter.agent.catalog.AgentMode;
import com.judepereira.jupiter.agent.catalog.ThinkingLevel;
import com.judepereira.jupiter.persistence.AppStateService;
import com.judepereira.jupiter.persistence.Persistence.QueuedChatTurn;
import com.judepereira.jupiter.persistence.Persistence.ToolCallTraceInput;
import com.judepereira.jupiter.persistence.TestAppStateSupport;
import com.judepereira.jupiter.ui.ChatPresentationService;
import com.judepereira.jupiter.ui.ChatToolCallHtmlService;
import com.judepereira.jupiter.ui.DomPatch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ChatToolCallHtmlServiceTest {

    @Test
    void subagentStartedReplacesGenericTaskGroupAndCompletionRefreshesItsSummary(@TempDir Path projectPath) {
        TestAppStateSupport.AppStateTestContext context = TestAppStateSupport.appStateContext(event -> {});
        AppStateService appStateService = context.service();
        appStateService.addOrReopenProject("Alpha", projectPath.toString());
        long sessionId = appStateService.loadViewData().activeSession().id();
        QueuedChatTurn turn = appStateService.appendUserMessageAndPendingAssistant(sessionId, "use a task");
        Map<String, Object> args = Map.of(
                "agentId", "explore",
                "requestSummary", "Inspect the task flow and report back.",
                "task", "Inspect the task flow and report back.",
                "expectedOutput", "finished");
        appStateService.startToolCallTrace(sessionId, turn.assistantMessage().id(),
                new ToolCallTraceInput("task-1", "task", args, false, "", Map.of()));

        AgentDefinition subagent = new AgentDefinition("explore", "Explore", "", "Explore files", AgentMode.SUBAGENT,
                "openai/gpt-5.5", ThinkingLevel.LOW, null, true, true, java.util.List.of());
        appStateService.createHiddenSubagentSession(sessionId, "task-1", subagent);

        ChatToolCallHtmlService htmlService = new ChatToolCallHtmlService(templateEngine(), new ChatPresentationService(), appStateService);
        DomPatch started = htmlService.subagentStarted(sessionId, turn.assistantMessage().id(), "task-1").getFirst();

        assertThat(started.swapMode()).isEqualTo("outerHTML");
        assertThat(started.html()).contains("Explore", "Inspect the task flow and report back.");
        assertThat(started.html()).doesNotContain(">task<");

        appStateService.appendToolCallTrace(sessionId, turn.assistantMessage().id(),
                new ToolCallTraceInput("task-1", "task", args, true, "finished", Map.of(
                        "subagentSessionId", 42L,
                        "subagentAgentId", "explore",
                        "subagentAgentName", "Explore")));
        var completion = htmlService.toolCompleted(sessionId, turn.assistantMessage().id(), "task-1");

        assertThat(completion).anySatisfy(patch -> assertThat(patch.html()).contains("Explore", "success"));
        assertThat(completion).anySatisfy(patch -> assertThat(patch.targetId()).endsWith("-summary"));
    }

    private static SpringTemplateEngine templateEngine() {
        SpringTemplateEngine engine = new SpringTemplateEngine();
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCacheable(false);
        engine.setTemplateResolver(resolver);
        return engine;
    }
}
