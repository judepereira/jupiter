package com.judepereira.jupiter.ui.command;

import com.judepereira.jupiter.command.CommandCatalogService;
import com.judepereira.jupiter.command.CommandStreamService;
import com.judepereira.jupiter.persistence.AppStateService;
import com.judepereira.jupiter.persistence.Persistence.AppStateView;
import com.judepereira.jupiter.ui.ChatPresentationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.List;

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
        return commandCatalogService.list();
    }

    @PostMapping(value = "/{commandId}/execute")
    public String execute(@PathVariable String commandId, Model model) {
        CommandCatalogService.CommandDefinition command = commandCatalogService.getRequired(commandId);
        if (command.type() != CommandCatalogService.CommandKind.SCRIPT) {
            throw new IllegalStateException("Command is not executable: " + commandId);
        }

        AppStateView view = appStateService.loadViewData();
        if (view.activeSession() == null || view.activeSessionDetail() == null) {
            throw new IllegalStateException("No active session");
        }

        String userId = java.util.UUID.randomUUID().toString();
        String assistantId = java.util.UUID.randomUUID().toString();
        var queuedTurn = appStateService.appendUserMessageAndPendingAssistant(view.activeSession().id(), userId, assistantId, "/" + command.id(), null);
        commandStreamService.queue(view.activeSession().id(), assistantId, command.id(), view.activeSessionDetail().workspaceRoot(),
                appStateService.loadSessionProjectEnvironmentVariables(view.activeSession().id()));

        model.addAttribute("newChatMessages", List.of(
                chatPresentationService.toChatMessage(queuedTurn.userMessage(), ignored -> null),
                chatPresentationService.toChatMessage(queuedTurn.assistantMessage(), ignored -> null)));
        model.addAttribute("pendingStreamBaseUrl", "/ui/chat/stream");
        model.addAttribute("subagentView", false);
        return "fragments/chat-response :: newRows";
    }
}
