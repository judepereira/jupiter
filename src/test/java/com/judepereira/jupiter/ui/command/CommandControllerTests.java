package com.judepereira.jupiter.ui.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.judepereira.jupiter.command.CommandCatalogService;
import com.judepereira.jupiter.command.CommandStreamService;
import com.judepereira.jupiter.persistence.AppStateService;
import com.judepereira.jupiter.persistence.Persistence.AppStateView;
import com.judepereira.jupiter.persistence.Persistence.ChatMessageView;
import com.judepereira.jupiter.persistence.Persistence.QueuedChatTurn;
import com.judepereira.jupiter.persistence.Persistence.SessionDetailView;
import com.judepereira.jupiter.persistence.Persistence.SessionView;
import com.judepereira.jupiter.ui.ChatPresentationService;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ui.Model;

class CommandControllerTests {

    private final CommandCatalogService commandCatalogService = mock(CommandCatalogService.class);
    private final CommandStreamService commandStreamService = mock(CommandStreamService.class);
    private final AppStateService appStateService = mock(AppStateService.class);
    private final ChatPresentationService chatPresentationService = new ChatPresentationService();
    private final CommandController controller = new CommandController(commandCatalogService, commandStreamService,
            appStateService, chatPresentationService);

    @Test
    void catalogWithoutActiveSessionUsesNoWorkspace() {
        when(appStateService.loadViewData()).thenReturn(view(null, null));
        List<CommandCatalogService.CommandDefinition> commands = List.of(command("one", SCRIPT));
        when(commandCatalogService.list(null)).thenReturn(commands);

        assertThat(controller.catalog()).containsExactlyElementsOf(commands);

        verify(commandCatalogService).list(null);
    }

    @Test
    void catalogWithActiveSessionUsesActiveWorkspace() {
        when(appStateService.loadViewData()).thenReturn(view(session(), detail("/workspace")));
        when(commandCatalogService.list(Path.of("/workspace"))).thenReturn(List.of());

        assertThat(controller.catalog()).isEmpty();

        verify(commandCatalogService).list(Path.of("/workspace"));
    }

    @Test
    void executeWithoutActiveSessionDoesNotLookUpOrQueue() {
        when(appStateService.loadViewData()).thenReturn(view(null, detail("/workspace")));

        assertThatThrownBy(() -> controller.execute("one", mock(Model.class))).isInstanceOf(IllegalStateException.class)
                .hasMessage("No active session");

        verifyNoInteractions(commandCatalogService, commandStreamService);
        verify(appStateService, never()).appendUserMessageAndPendingAssistant(anyLong(), any(), any(), any(), any());
    }

    @Test
    void executeWithoutSessionDetailDoesNotLookUpOrQueue() {
        when(appStateService.loadViewData()).thenReturn(view(session(), null));

        assertThatThrownBy(() -> controller.execute("one", mock(Model.class))).isInstanceOf(IllegalStateException.class)
                .hasMessage("No active session");

        verifyNoInteractions(commandCatalogService, commandStreamService);
    }

    @Test
    void executeUnknownCommandDoesNotQueue() {
        when(appStateService.loadViewData()).thenReturn(view(session(), detail("/workspace")));
        when(commandCatalogService.list(Path.of("/workspace"))).thenReturn(List.of(command("known", SCRIPT)));

        assertThatThrownBy(() -> controller.execute("missing", mock(Model.class)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Unknown command id: missing");

        verifyNoInteractions(commandStreamService);
        verify(appStateService, never()).appendUserMessageAndPendingAssistant(anyLong(), any(), any(), any(), any());
    }

    @Test
    void executePromptCommandDoesNotQueue() {
        when(appStateService.loadViewData()).thenReturn(view(session(), detail("/workspace")));
        when(commandCatalogService.list(Path.of("/workspace"))).thenReturn(List.of(command("prompt", PROMPT)));

        assertThatThrownBy(() -> controller.execute("prompt", mock(Model.class)))
                .isInstanceOf(IllegalStateException.class).hasMessage("Command is not executable: prompt");

        verifyNoInteractions(commandStreamService);
        verify(appStateService, never()).appendUserMessageAndPendingAssistant(anyLong(), any(), any(), any(), any());
    }

    @Test
    void executeScriptQueuesTurnAndRendersResponse() {
        SessionView session = session();
        SessionDetailView detail = detail("/workspace");
        CommandCatalogService.CommandDefinition command = command("build", SCRIPT);
        when(appStateService.loadViewData()).thenReturn(view(session, detail));
        when(commandCatalogService.list(Path.of("/workspace"))).thenReturn(List.of(command));
        when(appStateService.loadSessionProjectEnvironmentVariables(42L)).thenReturn(Map.of("MODE", "test"));
        QueuedChatTurn turn = new QueuedChatTurn(message("user", "/build", false, "u"),
                message("assistant", null, true, "a"));
        when(appStateService.appendUserMessageAndPendingAssistant(eq(42L), any(), any(), eq("/build"), isNull()))
                .thenReturn(turn);
        Model model = mock(Model.class);

        assertThat(controller.execute("build", model)).isEqualTo("fragments/chat-response :: newRows");

        verify(appStateService).appendUserMessageAndPendingAssistant(eq(42L), any(), any(), eq("/build"), isNull());
        verify(commandStreamService).queue(eq(42L), any(), eq(command), eq("/workspace"), eq(Map.of("MODE", "test")));
        verify(model).addAttribute(eq("newChatMessages"), any());
        verify(model).addAttribute("pendingStreamBaseUrl", "/ui/chat/stream");
        verify(model).addAttribute("subagentView", false);
    }

    private static AppStateView view(SessionView session, SessionDetailView detail) {
        return new AppStateView(List.of(), null, List.of(), null, List.of(), session, detail, false);
    }

    private static SessionView session() {
        return new SessionView(42L, "session", false, null);
    }

    private static SessionDetailView detail(String workspace) {
        return new SessionDetailView(List.of(), List.of(), false, null, null, workspace, null);
    }

    private static ChatMessageView message(String role, String text, boolean pending, String id) {
        return new ChatMessageView(role, text, 1L, pending, id, null, List.of(), null);
    }

    private static CommandCatalogService.CommandDefinition command(String id, CommandCatalogService.CommandKind kind) {
        return new CommandCatalogService.CommandDefinition(id, id, "description", kind, "body", null, 30);
    }

    private static final CommandCatalogService.CommandKind SCRIPT = CommandCatalogService.CommandKind.SCRIPT;
    private static final CommandCatalogService.CommandKind PROMPT = CommandCatalogService.CommandKind.PROMPT;
}
