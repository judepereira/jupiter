package com.judepereira.jupiter.ui;

import com.judepereira.jupiter.persistence.Persistence.ChatMessageMetadata;
import com.judepereira.jupiter.persistence.Persistence.ChatMessageView;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/** Builds the view models shared by full-page and incremental chat rendering. */
@Service
public class ChatPresentationService {

    private static final Set<String> EXPLORATORY_TOOL_NAMES = Set.of("list_files", "read_file", "search_code");
    private static final Set<String> SPECIAL_TOOL_NAMES = Set.of("task", "display_image");

    public ChatMessage toChatMessage(ChatMessageView view, Function<String, String> modelLabelResolver) {
        String modelLabel = view.metadata() == null ? null : modelLabelResolver.apply(view.metadata().modelId());
        return new ChatMessage(view.role(), view.text(), view.ts(), view.pending(), view.id(), view.completedTs(),
                view.toolCalls().stream().map(this::toToolCallView).toList(), view.metadata(), modelLabel);
    }

    public ToolCallView toToolCallView(com.judepereira.jupiter.persistence.Persistence.ToolCallView view) {
        return new ToolCallView(view.toolCallId(), view.toolName(), view.success(), view.inputPreview(), view.outputPreview(),
                view.inputTruncated(), view.outputTruncated(), view.subagentSessionId(), view.subagentAgentId(), view.subagentAgentName(),
                view.status(), view.imageUrl(), view.imageAlt(), view.imagePath(), view.imageMediaType(), view.taskBody());
    }

    static List<ToolCallGroupView> toolCallGroups(List<ToolCallView> toolCalls) {
        if (toolCalls.isEmpty()) {
            return List.of();
        }

        List<ToolCallGroupView> groups = new ArrayList<>();
        List<ToolCallView> currentCalls = new ArrayList<>();
        for (ToolCallView call : toolCalls) {
            if (currentCalls.isEmpty() || startsNewGroup(currentCalls.get(currentCalls.size() - 1), call)) {
                if (!currentCalls.isEmpty()) {
                    groups.add(toGroup(currentCalls));
                }
                currentCalls = new ArrayList<>();
            }
            currentCalls.add(call);
        }
        if (!currentCalls.isEmpty()) {
            groups.add(toGroup(currentCalls));
        }
        return List.copyOf(groups);
    }

    static List<ToolCallBlockView> toolCallBlocks(List<ToolCallView> toolCalls) {
        if (toolCalls.isEmpty()) {
            return List.of();
        }

        List<ToolCallBlockView> blocks = new ArrayList<>();
        List<ToolCallGroupView> currentBundleGroups = new ArrayList<>();
        for (ToolCallGroupView group : toolCallGroups(toolCalls)) {
            if (isSpecialStandalone(group.toolName())) {
                if (!currentBundleGroups.isEmpty()) {
                    blocks.add(ToolCallBlockView.bundle(toBundle(currentBundleGroups)));
                    currentBundleGroups = new ArrayList<>();
                }
                blocks.add(ToolCallBlockView.group(group));
            } else {
                currentBundleGroups.add(group);
            }
        }
        if (!currentBundleGroups.isEmpty()) {
            blocks.add(ToolCallBlockView.bundle(toBundle(currentBundleGroups)));
        }
        return List.copyOf(blocks);
    }

    /** Encodes arbitrary persisted IDs into deterministic, CSS/HTML-safe DOM ID components. */
    public static String domToken(String value) {
        if (value == null || value.isEmpty()) {
            return "unknown";
        }
        StringBuilder token = new StringBuilder();
        for (byte character : value.getBytes(StandardCharsets.UTF_8)) {
            int unsigned = character & 0xff;
            char c = (char) unsigned;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_' || c == '-') {
                token.append(c);
            } else {
                token.append('-').append(hex(unsigned));
            }
        }
        return token.isEmpty() ? "unknown" : token.toString();
    }

    private static String hex(int value) {
        char[] digits = "0123456789abcdef".toCharArray();
        return "" + digits[value >>> 4] + digits[value & 0x0f];
    }

    private static boolean startsNewGroup(ToolCallView previous, ToolCallView current) {
        if (isSpecialStandalone(previous.toolName()) || isSpecialStandalone(current.toolName())) {
            return true;
        }
        if (EXPLORATORY_TOOL_NAMES.contains(previous.toolName()) && EXPLORATORY_TOOL_NAMES.contains(current.toolName())) {
            return false;
        }
        return !previous.toolName().equals(current.toolName());
    }

    private static boolean isSpecialStandalone(String toolName) {
        return SPECIAL_TOOL_NAMES.contains(toolName);
    }

    private static ToolCallBundleView toBundle(List<ToolCallGroupView> groups) {
        return new ToolCallBundleView("Used: " + toolUsageLabel(groups), List.copyOf(groups));
    }

    private static ToolCallGroupView toGroup(List<ToolCallView> calls) {
        ToolCallView first = calls.get(0);
        String status = calls.stream().anyMatch(call -> "running".equals(call.status()))
                ? "running" : calls.stream().allMatch(ToolCallView::success) ? "success" : "failure";
        return new ToolCallGroupView(first.toolName(), displayLabel(calls), status,
                calls.stream().allMatch(ToolCallView::success), calls.size(), List.copyOf(calls));
    }

    private static String toolUsageLabel(List<ToolCallGroupView> groups) {
        LinkedHashMap<String, Integer> counts = new LinkedHashMap<>();
        for (ToolCallGroupView group : groups) {
            for (ToolCallView call : group.calls()) {
                counts.merge(call.toolName(), 1, Integer::sum);
            }
        }
        StringBuilder label = new StringBuilder();
        for (var entry : counts.entrySet()) {
            if (!label.isEmpty()) {
                label.append(", ");
            }
            label.append(entry.getKey());
            if (entry.getValue() > 1) {
                label.append(" (").append(entry.getValue()).append(")");
            }
        }
        return label.toString();
    }

    private static String displayLabel(List<ToolCallView> calls) {
        StringBuilder label = new StringBuilder();
        String currentToolName = calls.get(0).toolName();
        int currentCount = 1;
        for (int i = 1; i < calls.size(); i++) {
            String nextToolName = calls.get(i).toolName();
            if (currentToolName.equals(nextToolName)) {
                currentCount++;
                continue;
            }
            appendDisplaySegment(label, currentToolName, currentCount);
            currentToolName = nextToolName;
            currentCount = 1;
        }
        appendDisplaySegment(label, currentToolName, currentCount);
        return label.toString();
    }

    private static void appendDisplaySegment(StringBuilder label, String toolName, int count) {
        if (!label.isEmpty()) {
            label.append(", ");
        }
        label.append(toolName);
        if (count > 1) {
            label.append(" (").append(count).append(")");
        }
    }

    public record ToolCallView(String toolCallId, String toolName, boolean success, String inputPreview, String outputPreview,
                               boolean inputTruncated, boolean outputTruncated, Long subagentSessionId,
                               String subagentAgentId, String subagentAgentName, String status, String imageUrl,
                               String imageAlt, String imagePath, String imageMediaType, String taskBody) {
        public ToolCallView(String toolCallId, String toolName, boolean success, String inputPreview, String outputPreview,
                            boolean inputTruncated, boolean outputTruncated, Long subagentSessionId, String subagentAgentId,
                            String subagentAgentName, String status) {
            this(toolCallId, toolName, success, inputPreview, outputPreview, inputTruncated, outputTruncated,
                    subagentSessionId, subagentAgentId, subagentAgentName, status, null, null, null, null, null);
        }

        public ToolCallView(String toolCallId, String toolName, boolean success, String inputPreview, String outputPreview,
                            boolean inputTruncated, boolean outputTruncated, Long subagentSessionId, String subagentAgentId,
                            String subagentAgentName) {
            this(toolCallId, toolName, success, inputPreview, outputPreview, inputTruncated, outputTruncated,
                    subagentSessionId, subagentAgentId, subagentAgentName, null, null, null, null, null, null);
        }

        public ToolCallView(String toolCallId, String toolName, boolean success, String inputPreview, String outputPreview,
                            boolean inputTruncated, boolean outputTruncated, Long subagentSessionId, String subagentAgentId,
                            String subagentAgentName, String status, String taskBody) {
            this(toolCallId, toolName, success, inputPreview, outputPreview, inputTruncated, outputTruncated,
                    subagentSessionId, subagentAgentId, subagentAgentName, status, null, null, null, null, taskBody);
        }

        public ToolCallView(String toolCallId, String toolName, boolean success, String inputPreview, String outputPreview,
                            boolean inputTruncated, boolean outputTruncated, Long subagentSessionId, String subagentAgentId,
                            String subagentAgentName, String status, String imageUrl, String imageAlt, String imagePath,
                            String imageMediaType) {
            this(toolCallId, toolName, success, inputPreview, outputPreview, inputTruncated, outputTruncated,
                    subagentSessionId, subagentAgentId, subagentAgentName, status, imageUrl, imageAlt, imagePath,
                    imageMediaType, null);
        }

        public String domId(String assistantId) {
            return "assistant-tool-call-" + domToken(assistantId) + "-" + domToken(toolCallId);
        }
    }

    public record ToolCallGroupView(String toolName, String displayLabel, String status, boolean success, int count,
                                    List<ToolCallView> calls) {
        public String domId(String assistantId) {
            return "assistant-tool-group-" + domToken(assistantId) + "-" + domToken(calls.get(0).toolCallId());
        }

        public String summaryDomId(String assistantId) {
            return domId(assistantId) + "-summary";
        }

        public String callsDomId(String assistantId) {
            return domId(assistantId) + "-calls";
        }
    }

    public record ToolCallBundleView(String summaryLabel, List<ToolCallGroupView> groups) {
        public String domId(String assistantId) {
            return "assistant-tool-bundle-" + domToken(assistantId) + "-" + domToken(groups.get(0).calls().get(0).toolCallId());
        }

        public String summaryDomId(String assistantId) {
            return domId(assistantId) + "-summary";
        }

        public String callsDomId(String assistantId) {
            return domId(assistantId) + "-calls";
        }
    }

    public record ToolCallBlockView(ToolCallBundleView bundle, ToolCallGroupView group) {
        public static ToolCallBlockView bundle(ToolCallBundleView bundle) {
            return new ToolCallBlockView(bundle, null);
        }

        public static ToolCallBlockView group(ToolCallGroupView group) {
            return new ToolCallBlockView(null, group);
        }
    }

    public record ChatMessage(String role, String text, long ts, boolean pending, String id, Long completedTs,
                              List<ToolCallView> toolCalls, ChatMessageMetadata metadata, String modelLabel) {
        public ChatMessage(String role, String text, long ts, boolean pending, String id, Long completedTs,
                           List<ToolCallView> toolCalls, ChatMessageMetadata metadata) {
            this(role, text, ts, pending, id, completedTs, toolCalls, metadata, null);
        }

        public String toolCallHostId() {
            return "assistant-tool-calls-" + domToken(id);
        }

        public List<ToolCallGroupView> toolCallGroups() {
            return ChatPresentationService.toolCallGroups(toolCalls);
        }

        public List<ToolCallBlockView> toolCallBlocks() {
            return ChatPresentationService.toolCallBlocks(toolCalls);
        }
    }
}
