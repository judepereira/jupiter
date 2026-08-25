package com.judepereira.jupiter.command;

import com.judepereira.jupiter.agent.tools.ToolExecutionContext;
import com.judepereira.jupiter.agent.tools.ToolExecutionResult;
import com.judepereira.jupiter.agent.tools.impl.RunCommandTool;
import com.judepereira.jupiter.persistence.AppStateService;
import com.judepereira.jupiter.persistence.Persistence.ChatMessageView;
import com.judepereira.jupiter.persistence.Persistence.ToolCallTraceInput;
import com.judepereira.jupiter.persistence.Persistence.ToolCallView;
import com.judepereira.jupiter.ui.ActiveStreamRegistryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CommandStreamServiceTests {

    private static final long SESSION_ID = 42L;
    private static final String ASSISTANT_ID = "assistant-1";
    private static final String COMMAND_ID = "status";

    @Test
    void completed_command_output_is_wrapped_in_a_fenced_code_block(@TempDir Path workspaceRoot) throws Exception {
        assertThat(completeAssistantMessageText(workspaceRoot, "hello\nworld")).isEqualTo("```\nhello\nworld\n```");
    }

    @Test
    void command_output_with_triple_backticks_uses_a_longer_fence(@TempDir Path workspaceRoot) throws Exception {
        String finalText = completeAssistantMessageText(workspaceRoot, "before\n```\nafter");

        assertThat(finalText).startsWith("````\n");
        assertThat(finalText).endsWith("\n````");
        assertThat(finalText).contains("\n```\n");
    }

    @Test
    void no_output_command_result_stays_as_plain_prose(@TempDir Path workspaceRoot) throws Exception {
        assertThat(completeAssistantMessageText(workspaceRoot, null)).isEqualTo("command completed with no output");
    }

    @Test
    void output_exactly_matching_the_no_output_message_is_still_fenced(@TempDir Path workspaceRoot) throws Exception {
        assertThat(completeAssistantMessageText(workspaceRoot, "command completed with no output"))
                .isEqualTo("```\ncommand completed with no output\n```");
    }

    private String completeAssistantMessageText(Path workspaceRoot, String commandOutput) throws Exception {
        CommandCatalogService commandCatalogService = mock(CommandCatalogService.class);
        AppStateService appStateService = mock(AppStateService.class);
        RunCommandTool runCommandTool = mock(RunCommandTool.class);
        ActiveStreamRegistryService activeStreamRegistryService = new ActiveStreamRegistryService();
        CommandStreamService service = new CommandStreamService(commandCatalogService, appStateService, runCommandTool, activeStreamRegistryService);

        when(commandCatalogService.getRequiredScript(COMMAND_ID)).thenReturn(new CommandCatalogService.CommandDefinition(
                COMMAND_ID,
                "Status",
                null,
                CommandCatalogService.CommandKind.SCRIPT,
                "echo status",
                null,
                null
        ));
        when(runCommandTool.execute(anyMap(), any(ToolExecutionContext.class))).thenReturn(new ToolExecutionResult(true, commandOutput, Map.of()));
        when(appStateService.appendToolCallTrace(eq(SESSION_ID), eq(ASSISTANT_ID), any(ToolCallTraceInput.class)))
                .thenReturn(new ToolCallView(ASSISTANT_ID, "run_command", true, "", "", false, false, null, null, null));

        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<String> finalText = new AtomicReference<>();
        doAnswer(invocation -> {
            String text = invocation.getArgument(2, String.class);
            finalText.set(text);
            completed.countDown();
            return new ChatMessageView("assistant", text, System.currentTimeMillis(), false, ASSISTANT_ID, System.currentTimeMillis(), List.of(), null);
        }).when(appStateService).completeAssistantMessage(eq(SESSION_ID), eq(ASSISTANT_ID), any(), anyList());

        service.queue(SESSION_ID, ASSISTANT_ID, COMMAND_ID, workspaceRoot.toString(), Map.of());
        assertThat(service.tryConnect(ASSISTANT_ID)).isNotNull();
        assertThat(completed.await(2, TimeUnit.SECONDS)).isTrue();

        return finalText.get();
    }
}
