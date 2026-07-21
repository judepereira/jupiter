package com.judepereira.jupiter2.persistence;

import com.judepereira.jupiter2.agent.catalog.AgentDefinition;
import com.judepereira.jupiter2.agent.catalog.ModelDefinition;
import com.judepereira.jupiter2.agent.catalog.ThinkingLevel;
import com.judepereira.jupiter2.agent.harness.SystemPromptComposer;
import com.judepereira.jupiter2.agent.llm.AgentModelClient;
import com.judepereira.jupiter2.agent.llm.AgentModelClientFactory;
import com.judepereira.jupiter2.agent.llm.AgentModelOptions;
import com.judepereira.jupiter2.agent.llm.dto.Message;
import com.judepereira.jupiter2.agent.llm.dto.ModelResponse;
import com.judepereira.jupiter2.persistence.Persistence.ChatMessageView;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class ContextCompactionService {

    private static final int MIN_RECENT_COMPLETED_TURNS = 2;
    private static final int SAFETY_MARGIN_TOKENS = 512;
    private static final int MESSAGE_OVERHEAD_TOKENS = 16;
    private static final int TOOL_SCHEMA_TOKENS = 200;

    private static final String SUMMARY_SYSTEM_PROMPT = "Summarize the compacted conversation for future model context. " +
            "Keep it concise, preserve decisions, file paths, tool results, and open tasks, and do not mention that the summary was compacted.";

    private final AppStateService appStateService;
    private final AgentModelClientFactory modelClientFactory;
    private final SystemPromptComposer systemPromptComposer;

    public ContextCompactionService(AppStateService appStateService, AgentModelClientFactory modelClientFactory) {
        this(appStateService, modelClientFactory, new SystemPromptComposer());
    }

    @Autowired
    public ContextCompactionService(AppStateService appStateService, AgentModelClientFactory modelClientFactory,
                                    SystemPromptComposer systemPromptComposer) {
        this.appStateService = appStateService;
        this.modelClientFactory = modelClientFactory;
        this.systemPromptComposer = systemPromptComposer;
    }

    @Transactional
    public Optional<ChatMessageView> compactIfNeeded(long sessionId, AgentDefinition agent, ModelDefinition model,
                                                      ThinkingLevel thinkingLevel, String workspaceRoot, String upcomingUserText) {
        List<AppStateRepository.ConversationMessageRow> rows = includedCompletedRows(sessionId);
        int budget = availableInputBudget(model);
        int estimatedBefore = estimateTurnTokens(agent, model, rows, upcomingUserText, workspaceRoot);

        if (estimatedBefore <= compactThreshold(budget)) {
            return Optional.empty();
        }

        List<TurnGroup> groups = groupByTurn(rows);
        if (groups.size() <= MIN_RECENT_COMPLETED_TURNS) {
            throw new IllegalStateException("Conversation is too large for " + model.id() + " even before compaction: estimated "
                    + estimatedBefore + " tokens for budget " + budget);
        }

        List<TurnGroup> compactableGroups = groups.subList(0, groups.size() - MIN_RECENT_COMPLETED_TURNS);
        List<AppStateRepository.ConversationMessageRow> compactableRows = compactableGroups.stream().flatMap(group -> group.rows().stream()).toList();

        int summaryRequestTokens = estimateSummaryRequestTokens(model, compactableRows);
        if (summaryRequestTokens > budget) {
            throw new IllegalStateException("Conversation chunk is too large to summarize with " + model.id()
                    + ": estimated " + summaryRequestTokens + " tokens for budget " + budget);
        }

        String transcript = buildTranscript(compactableGroups);
        AgentModelClient client = modelClientFactory.getClient();
        AgentModelOptions options = new AgentModelOptions(model.id(), model.apiModelId(), thinkingLevel, model.supportsReasoning(), agent.textVerbosity());
        ModelResponse summaryResult = client.chat(List.of(
                new Message(Message.Role.SYSTEM, SUMMARY_SYSTEM_PROMPT),
                new Message(Message.Role.USER, transcript)
        ), List.of(), options);

        String summary = summaryResult.getAssistantText() == null ? "" : summaryResult.getAssistantText().trim();
        if (summary.isBlank()) {
            throw new IllegalStateException("Context compaction produced an empty summary for model " + model.id());
        }

        long compactedThroughTurnId = compactableGroups.getLast().turnId();
        appStateService.markTurnsIncludeInModelFalse(sessionId, compactedThroughTurnId);
        ChatMessageView summaryMessage = appStateService.appendVisibleSystemMessage(sessionId, summary, compactedThroughTurnId);

        int estimatedAfter = estimateTurnTokens(agent, model, includedCompletedRows(sessionId), upcomingUserText, workspaceRoot);
        if (estimatedAfter > budget) {
            throw new IllegalStateException("Conversation still does not fit after compaction for " + model.id()
                    + ": estimated " + estimatedAfter + " tokens for budget " + budget);
        }

        return Optional.of(summaryMessage);
    }

    private List<AppStateRepository.ConversationMessageRow> includedCompletedRows(long sessionId) {
        return appStateService.listConversationMessages(sessionId).stream()
                .filter(message -> message.includeInModel() && !message.pending())
                .toList();
    }

    private List<TurnGroup> groupByTurn(List<AppStateRepository.ConversationMessageRow> rows) {
        Map<Long, List<AppStateRepository.ConversationMessageRow>> grouped = new LinkedHashMap<>();
        for (var row : rows) {
            grouped.computeIfAbsent(row.turnId(), turnId -> new ArrayList<>()).add(row);
        }
        return grouped.entrySet().stream()
                .map(entry -> new TurnGroup(entry.getKey(), List.copyOf(entry.getValue())))
                .toList();
    }

    private String buildTranscript(List<TurnGroup> groups) {
        StringBuilder transcript = new StringBuilder();
        for (TurnGroup group : groups) {
            transcript.append("[turn ").append(group.turnId()).append("]\n");
            for (var row : group.rows()) {
                transcript.append(row.role()).append(": ");
                String content = row.content() == null ? "" : row.content();
                transcript.append(content.isBlank() ? "(empty)" : content);
                if (row.toolCallId() != null && !row.toolCallId().isBlank()) {
                    transcript.append("\n  tool_call_id: ").append(row.toolCallId());
                }
                if (row.toolCallsJson() != null && !row.toolCallsJson().isBlank()) {
                    transcript.append("\n  tool_calls_json: ").append(row.toolCallsJson());
                }
                transcript.append('\n');
            }
            transcript.append('\n');
        }
        return transcript.toString();
    }

    private int estimateSummaryRequestTokens(ModelDefinition model, List<AppStateRepository.ConversationMessageRow> rows) {
        return estimatePromptTokens(SUMMARY_SYSTEM_PROMPT)
                + estimateRowsTokens(rows)
                + model.outputTokens();
    }

    private int estimateTurnTokens(AgentDefinition agent, ModelDefinition model, List<AppStateRepository.ConversationMessageRow> rows, String userText, String workspaceRoot) {
        return estimatePromptTokens(systemPromptComposer.composeForAgent(agent, workspaceRoot))
                + estimateRowsTokens(rows)
                + estimateTextTokens(userText)
                + toolSchemaTokens(agent)
                + model.outputTokens();
    }

    private int estimateRowsTokens(List<AppStateRepository.ConversationMessageRow> rows) {
        return rows.stream().mapToInt(this::estimateRowTokens).sum();
    }

    private int estimateRowTokens(AppStateRepository.ConversationMessageRow row) {
        int tokens = MESSAGE_OVERHEAD_TOKENS + estimateTextTokens(row.content()) + estimateTextTokens(row.toolCallsJson()) + estimateTextTokens(row.toolCallId());
        if (row.agentId() != null) {
            tokens += estimateTextTokens(row.agentId()) + estimateTextTokens(row.agentName()) + estimateTextTokens(row.modelId()) + estimateTextTokens(row.thinkingLevel());
        }
        return tokens;
    }

    private int estimatePromptTokens(String prompt) {
        return estimateTextTokens(prompt) + MESSAGE_OVERHEAD_TOKENS;
    }

    private int estimateTextTokens(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return (text.length() + 3) / 4;
    }

    private int toolSchemaTokens(AgentDefinition agent) {
        return agent.allowedTools().size() * TOOL_SCHEMA_TOKENS;
    }

    private int availableInputBudget(ModelDefinition model) {
        return Math.max(1, model.contextTokens() - SAFETY_MARGIN_TOKENS);
    }

    private int compactThreshold(int budget) {
        return Math.max(1, budget - Math.max(512, budget / 10));
    }

    private record TurnGroup(long turnId, List<AppStateRepository.ConversationMessageRow> rows) {}
}
