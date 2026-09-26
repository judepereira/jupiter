package com.judepereira.jupiter.ui.command;

import com.judepereira.jupiter.command.CommandCatalogService;
import com.judepereira.jupiter.command.CommandStreamService;
import com.judepereira.jupiter.persistence.AppStateService;
import com.judepereira.jupiter.persistence.Persistence.AppStateView;
import com.judepereira.jupiter.ui.ChatPresentationService;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
@RequiredArgsConstructor
@RequestMapping("/ui/commands")
public class CommandController {

    private final CommandCatalogService commandCatalogService;
    private final CommandStreamService commandStreamService;
    private final AppStateService appStateService;
    private final ChatPresentationService chatPresentationService;

    @GetMapping(value = "/catalog", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public List<CommandCatalogService.CommandDefinition> catalog() {
        AppStateView view = appStateService.loadViewData();
        return commandCatalogService
                .list(view.activeSessionDetail() == null ? null : Path.of(view.activeSessionDetail().workspaceRoot()));
    }

    @PostMapping(value = "/{commandId}/execute")
    public String execute(@PathVariable String commandId, Model model) {
        AppStateView view = appStateService.loadViewData();
        if (view.activeSession() == null || view.activeSessionDetail() == null) {
            throw new IllegalStateException("No active session");
        }
        CommandCatalogService.CommandDefinition command = commandCatalogService
                .list(Path.of(view.activeSessionDetail().workspaceRoot())).stream()
                .filter(candidate -> candidate.id().equals(commandId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown command id: " + commandId));
        if (command.type() != CommandCatalogService.CommandKind.SCRIPT) {
            throw new IllegalStateException("Command is not executable: " + commandId);
        }

        String userId = UUID.randomUUID().toString();
        String assistantId = UUID.randomUUID().toString();
        var queuedTurn = appStateService.appendUserMessageAndPendingAssistant(view.activeSession().id(), userId,
                assistantId, "/" + command.id(), null);
        commandStreamService.queue(view.activeSession().id(), assistantId, command,
                view.activeSessionDetail().workspaceRoot(),
                appStateService.loadSessionProjectEnvironmentVariables(view.activeSession().id()));

        model.addAttribute("newChatMessages",
                List.of(chatPresentationService.toChatMessage(queuedTurn.userMessage(), ignored -> null),
                        chatPresentationService.toChatMessage(queuedTurn.assistantMessage(), ignored -> null)));
        model.addAttribute("pendingStreamBaseUrl", "/ui/chat/stream");
        model.addAttribute("subagentView", false);
        return "fragments/chat-response :: newRows";
    }
}
