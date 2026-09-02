package com.judepereira.jupiter.ui;

import com.judepereira.jupiter.persistence.AppStateService;
import com.judepereira.jupiter.persistence.Persistence.ChatMessageView;
import com.judepereira.jupiter.persistence.Persistence.SessionDetailView;
import org.springframework.stereotype.Service;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import java.util.List;
import java.util.Set;

/** Renders incremental tool-call patches from the persisted assistant projection. */
@Service
public class ChatToolCallHtmlService {
    private final SpringTemplateEngine templateEngine;
    private final ChatPresentationService presentation;
    private final AppStateService appStateService;

    public ChatToolCallHtmlService(SpringTemplateEngine templateEngine, ChatPresentationService presentation,
                                   AppStateService appStateService) {
        this.templateEngine = templateEngine;
        this.presentation = presentation;
        this.appStateService = appStateService;
    }

    public String lazyGroup(String assistantId, String anchorToolCallId) {
        ChatPresentationService.ChatMessage model = presentation.toChatMessage(
                appStateService.loadLazyAssistantMessage(assistantId, anchorToolCallId), ignored -> null);
        ChatPresentationService.ToolCallGroupView group = model.toolCallGroups().stream()
                .filter(candidate -> candidate.calls().stream().anyMatch(call -> anchorToolCallId.equals(call.toolCallId())))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Tool call group not found: " + anchorToolCallId));
        if (group.toolName().equals("display_image")) {
            throw new IllegalStateException("Display image tool calls are eager: " + anchorToolCallId);
        }
        return renderGroup(group, assistantId);
    }

    public List<DomPatch> hostSnapshot(long sessionId, String assistantId) {
        ChatMessageView message = assistant(sessionId, assistantId);
        return List.of(render("toolCalls", message, assistantId, messageHost(message), "outerHTML"));
    }

    public List<DomPatch> toolStarted(long sessionId, String assistantId) {
        ChatMessageView message = assistant(sessionId, assistantId);
        ChatPresentationService.ChatMessage model = presentation.toChatMessage(message, ignored -> null);
        List<ChatPresentationService.ToolCallBlockView> blocks = model.toolCallBlocks();
        if (blocks.isEmpty()) {
            return hostSnapshot(sessionId, assistantId);
        }
        ChatPresentationService.ToolCallBlockView last = blocks.getLast();
        if (last.bundle() != null) {
            ChatPresentationService.ToolCallBundleView bundle = last.bundle();
            ChatPresentationService.ToolCallGroupView group = bundle.groups().getLast();
            if (bundle.groups().size() == 1 && group.calls().size() == 1) {
                return List.of(render("block", last, assistantId, model.toolCallHostId(), "beforeend"));
            }
            List<DomPatch> patches = new java.util.ArrayList<>();
            patches.add(render("bundleSummary", bundle, assistantId, bundle.summaryDomId(assistantId), "outerHTML"));
            if (group.calls().size() == 1) {
                patches.add(render("group", group, assistantId, bundle.callsDomId(assistantId), "beforeend"));
            } else {
                ChatPresentationService.ToolCallView call = group.calls().getLast();
                patches.add(renderCall(call, group.toolName(), assistantId, group.callsDomId(assistantId), "beforeend"));
                patches.add(render(group.toolName().equals("task") ? "taskSummary" : "groupSummary", group, assistantId,
                        group.summaryDomId(assistantId), "outerHTML"));
            }
            return List.copyOf(patches);
        }
        ChatPresentationService.ToolCallGroupView group = last.group();
        if (group.calls().size() == 1) {
            return List.of(render("block", last, assistantId, model.toolCallHostId(), "beforeend"));
        }
        ChatPresentationService.ToolCallView call = group.calls().getLast();
        return List.of(renderCall(call, group.toolName(), assistantId, group.callsDomId(assistantId), "beforeend"),
                render(group.toolName().equals("task") ? "taskSummary" : "groupSummary", group, assistantId,
                        group.summaryDomId(assistantId), "outerHTML"));
    }

    public List<DomPatch> subagentStarted(long sessionId, String assistantId, String toolCallId) {
        ChatPresentationService.ChatMessage model = presentation.toChatMessage(assistant(sessionId, assistantId), ignored -> null);
        ChatPresentationService.ToolCallGroupView group = model.toolCallGroups().stream()
                .filter(candidate -> candidate.calls().stream().anyMatch(call -> toolCallId.equals(call.toolCallId())))
                .findFirst().orElse(null);
        if (group == null) {
            return hostSnapshot(sessionId, assistantId);
        }

        ChatPresentationService.ToolCallBlockView block = model.toolCallBlocks().stream()
                .filter(candidate -> containsToolCall(candidate, toolCallId))
                .findFirst().orElse(null);
        if (block == null) {
            return hostSnapshot(sessionId, assistantId);
        }
        if (block.group() != null) {
            return List.of(render("group", group, assistantId, group.domId(assistantId), "outerHTML"));
        }
        return List.of(render("block", block, assistantId, block.bundle().domId(assistantId), "outerHTML"));
    }

    public List<DomPatch> toolCompleted(long sessionId, String assistantId, String toolCallId) {
        ChatMessageView message = assistant(sessionId, assistantId);
        ChatPresentationService.ChatMessage model = presentation.toChatMessage(message, ignored -> null);
        ChatPresentationService.ToolCallView call = model.toolCalls().stream()
                .filter(candidate -> candidate.toolCallId().equals(toolCallId)).findFirst().orElse(null);
        ChatPresentationService.ToolCallGroupView group = model.toolCallGroups().stream()
                .filter(candidate -> candidate.calls().stream().anyMatch(c -> c.toolCallId().equals(toolCallId))).findFirst().orElse(null);
        if (call == null || group == null) {
            return hostSnapshot(sessionId, assistantId);
        }
        List<DomPatch> patches = new java.util.ArrayList<>();
        patches.add(renderCall(call, group.toolName(), assistantId, call.domId(assistantId), "outerHTML"));
        patches.add(render(group.toolName().equals("task") ? "taskSummary" : "groupSummary", group, assistantId,
                group.summaryDomId(assistantId), "outerHTML"));
        ChatPresentationService.ToolCallBlockView block = model.toolCallBlocks().stream()
                .filter(candidate -> candidate.bundle() != null && candidate.bundle().groups().contains(group)).findFirst().orElse(null);
        if (block != null) {
            patches.add(render("bundleSummary", block.bundle(), assistantId, block.bundle().summaryDomId(assistantId), "outerHTML"));
        }
        return List.copyOf(patches);
    }

    private static boolean containsToolCall(ChatPresentationService.ToolCallBlockView block, String toolCallId) {
        if (block.group() != null) {
            return block.group().calls().stream().anyMatch(call -> toolCallId.equals(call.toolCallId()));
        }
        return block.bundle().groups().stream().anyMatch(group -> group.calls().stream().anyMatch(call -> toolCallId.equals(call.toolCallId())));
    }

    private ChatMessageView assistant(long sessionId, String assistantId) {
        return appStateService.loadFullSessionDetail(sessionId).chatMessages().stream()
                .filter(candidate -> assistantId.equals(candidate.id()) && "assistant".equals(candidate.role()))
                .findFirst().orElseThrow(() -> new IllegalStateException("Assistant message not found: " + assistantId));
    }

    private String renderGroup(ChatPresentationService.ToolCallGroupView group, String assistantId) {
        Context context = new Context();
        context.setVariable("group", group);
        context.setVariable("assistantId", assistantId);
        context.setVariable("fullMode", true);
        context.setVariable("openGroup", true);
        context.setVariable("lazyGroup", false);
        return templateEngine.process("fragments/chat-tool-calls", Set.of("group"), context);
    }

    private DomPatch render(String fragment, Object value, String assistantId, String targetId, String swapMode) {
        Context context = new Context();
        if ("toolCalls".equals(fragment)) {
            context.setVariable("message", presentation.toChatMessage((ChatMessageView) value, ignored -> null));
            context.setVariable("fullMode", true);
        } else if ("block".equals(fragment)) {
            context.setVariable("block", value);
            context.setVariable("assistantId", assistantId);
            context.setVariable("fullMode", true);
        } else if ("call".equals(fragment)) {
            context.setVariable("call", value);
            context.setVariable("parentToolName", "");
            context.setVariable("assistantId", assistantId);
        } else if ("group".equals(fragment) || "groupSummary".equals(fragment) || "taskSummary".equals(fragment)) {
            context.setVariable("group", value);
            context.setVariable("assistantId", assistantId);
            context.setVariable("fullMode", true);
        } else {
            context.setVariable("bundle", value);
            context.setVariable("assistantId", assistantId);
        }
        String html = templateEngine.process("fragments/chat-tool-calls", Set.of(fragment), context);
        return new DomPatch(html, targetId, swapMode);
    }

    private DomPatch renderCall(ChatPresentationService.ToolCallView call, String parentToolName, String assistantId,
                                String targetId, String swapMode) {
        Context context = new Context();
        context.setVariable("fullMode", true);
        context.setVariable("call", call);
        context.setVariable("parentToolName", parentToolName);
        context.setVariable("assistantId", assistantId);
        String html = templateEngine.process("fragments/chat-tool-calls", Set.of("call"), context);
        return new DomPatch(html, targetId, swapMode);
    }

    private static String messageHost(ChatMessageView message) {
        return "assistant-tool-calls-" + ChatPresentationService.domToken(message.id());
    }
}
