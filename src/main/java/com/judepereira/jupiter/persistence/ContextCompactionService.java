package com.judepereira.jupiter.persistence;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.judepereira.jupiter.agent.catalog.AgentDefinition;
import com.judepereira.jupiter.agent.catalog.ModelDefinition;
import com.judepereira.jupiter.agent.catalog.ThinkingLevel;
import com.judepereira.jupiter.agent.harness.SystemPromptComposer;
import com.judepereira.jupiter.agent.llm.AgentModelClient;
import com.judepereira.jupiter.agent.llm.AgentModelClientFactory;
import com.judepereira.jupiter.agent.llm.AgentModelOptions;
import com.judepereira.jupiter.agent.llm.dto.Message;
import com.judepereira.jupiter.agent.llm.dto.ModelResponse;
import com.judepereira.jupiter.agent.skill.SkillDiscoveryService;
import com.judepereira.jupiter.persistence.Persistence.ChatMessageView;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class ContextCompactionService {

    private static final int SAFETY_MARGIN_TOKENS = 512;
    private static final int MESSAGE_OVERHEAD_TOKENS = 16;
    private static final int TOOL_SCHEMA_TOKENS = 200;
    private static final ObjectMapper TOOL_CALLS_MAPPER = new ObjectMapper();

    private static final String SUMMARY_SYSTEM_PROMPT = "Summarize the compacted conversation for future model context. "
            + "Keep it concise, preserve decisions, file paths, tool results, and open tasks, and do not mention that the summary was compacted.";

    private final AppStateService appStateService;
    private final AgentModelClientFactory modelClientFactory;
    private final TokenUsageService tokenUsageService;
    private final SystemPromptComposer systemPromptComposer;
    private final SkillDiscoveryService skillDiscoveryService;

    @Autowired
    public ContextCompactionService(AppStateService appStateService, AgentModelClientFactory modelClientFactory,
            TokenUsageService tokenUsageService, SystemPromptComposer systemPromptComposer,
            SkillDiscoveryService skillDiscoveryService) {
        this.appStateService = appStateService;
        this.modelClientFactory = modelClientFactory;
        this.tokenUsageService = tokenUsageService;
        this.systemPromptComposer = systemPromptComposer;
        this.skillDiscoveryService = skillDiscoveryService;
    }

    public Optional<ChatMessageView> compactIfNeeded(long sessionId, AgentDefinition agent, ModelDefinition model,
            ThinkingLevel thinkingLevel, String workspaceRoot, String upcomingUserText) {
        List<AppStateRepository.ConversationMessageRow> rows = appStateService
                .listIncludedCompletedConversationMessages(sessionId);
        int budget = availableInputBudget(model);
        PromptTokenCosts tokenCosts = precomputeTokenCosts(agent, model, workspaceRoot, upcomingUserText);
        int estimatedBefore = estimateTurnTokens(tokenCosts, rows);

        if (estimatedBefore <= compactThreshold(budget)) {
            return Optional.empty();
        }

        List<AppStateRepository.ConversationMessageRow> compactableRows = selectCompactionPrefix(rows, tokenCosts,
                budget);
        if (compactableRows.isEmpty()) {
            throw new IllegalStateException("Conversation is too large for " + model.id()
                    + " even before compaction: estimated " + estimatedBefore + " tokens for budget " + budget);
        }

        int summaryRequestTokens = estimateSummaryRequestTokens(model, compactableRows);
        if (summaryRequestTokens > budget) {
            throw new IllegalStateException("Conversation chunk is too large to summarize with " + model.id()
                    + ": estimated " + summaryRequestTokens + " tokens for budget " + budget);
        }

        String transcript = buildTranscript(compactableRows);
        AgentModelClient client = modelClientFactory.getClient(model.provider());
        AgentModelOptions options = new AgentModelOptions(model.id(), model.apiModelId(), thinkingLevel,
                model.supportsReasoning(), agent.textVerbosity());
        StringBuilder streamedSummary = new StringBuilder();
        ModelResponse summaryResult = client
                .chatStreaming(
                        List.of(new Message(Message.Role.SYSTEM, SUMMARY_SYSTEM_PROMPT, null, null, null),
                                new Message(Message.Role.USER, transcript, null, null, null)),
                        List.of(), options, delta -> {
                            if (delta != null) {
                                streamedSummary.append(delta);
                            }
                        });
        if (tokenUsageService != null) {
            tokenUsageService.recordModelResponse(sessionId, model.id(), "compaction", summaryResult);
        }

        String summary = summaryResult.getAssistantText();
        if (summary == null || summary.isBlank()) {
            summary = streamedSummary.toString();
        }
        summary = summary == null ? "" : summary.trim();
        if (summary.isBlank()) {
            throw new IllegalStateException("Context compaction produced an empty summary for model " + model.id());
        }

        List<Long> compactedMessageIds = compactableRows.stream().map(AppStateRepository.ConversationMessageRow::id)
                .toList();
        ChatMessageView summaryMessage = appStateService.compactConversationAtomically(sessionId, rows,
                compactedMessageIds, summary, afterRows -> {
                    int estimatedAfter = estimateTurnTokens(tokenCosts, afterRows);
                    if (estimatedAfter > budget) {
                        throw new IllegalStateException("Conversation still does not fit after compaction for "
                                + model.id() + ": estimated " + estimatedAfter + " tokens for budget " + budget);
                    }
                });

        return Optional.of(summaryMessage);
    }

    private List<AppStateRepository.ConversationMessageRow> selectCompactionPrefix(
            List<AppStateRepository.ConversationMessageRow> rows, PromptTokenCosts costs, int budget) {
        List<AppStateRepository.ConversationMessageRow> selected = new ArrayList<>();
        int retainedTokens = estimateRowsTokens(rows);
        int target = compactThreshold(budget) - costs.promptTokens() - costs.userTextTokens() - costs.toolSchemaTokens()
                - costs.outputTokens() - summaryReserveTokens(costs.outputTokens());
        int compactedUnits = 0;
        for (int index = 0; index < rows.size(); index++) {
            var row = rows.get(index);
            if (row.pending()) {
                throw new IllegalStateException("Cannot compact an unfinished conversation message");
            }
            int end = index + 1;
            boolean oldSummary = "system".equals(row.role()) && row.compactedThroughTurnId() != null;
            if (oldSummary) {
                selected.add(row);
                retainedTokens -= estimateRowTokens(row);
                continue;
            }
            if (retainedTokens <= target && compactedUnits > 0) {
                break;
            }
            if ("assistant".equals(row.role()) && row.toolCallsJson() != null && !row.toolCallsJson().isBlank()) {
                List<String> callIds = parseCallIds(row.toolCallsJson());
                Set<String> found = new HashSet<>();
                while (end < rows.size() && rows.get(end).turnId() == row.turnId()
                        && "tool".equals(rows.get(end).role())) {
                    var result = rows.get(end);
                    if (!callIds.contains(result.toolCallId()) || !found.add(result.toolCallId())) {
                        throw new IllegalStateException("Mismatched tool exchange in conversation");
                    }
                    end++;
                    if (found.size() == callIds.size()) {
                        break;
                    }
                }
                if (found.size() != callIds.size()) {
                    throw new IllegalStateException("Incomplete tool exchange in conversation");
                }
            } else if ("tool".equals(row.role())) {
                throw new IllegalStateException("Orphan tool result in conversation");
            }
            List<AppStateRepository.ConversationMessageRow> unit = rows.subList(index, end);
            selected.addAll(unit);
            retainedTokens -= estimateRowsTokens(unit);
            compactedUnits++;
            index = end - 1;
        }
        return compactedUnits == 0 ? List.of() : List.copyOf(selected);
    }

    private int summaryReserveTokens(int responseReserveTokens) {
        return responseReserveTokens + MESSAGE_OVERHEAD_TOKENS
                + estimateTextTokens("Previous conversation summary:\n\n");
    }

    private List<String> parseCallIds(String json) {
        try {
            var calls = TOOL_CALLS_MAPPER.readValue(json, new TypeReference<List<ToolCallPayload>>() {
            });
            List<String> ids = calls.stream().map(ToolCallPayload::toolCallId).toList();
            if (ids.isEmpty() || ids.stream().anyMatch(id -> id == null || id.isBlank())
                    || ids.stream().distinct().count() != ids.size()) {
                throw new IllegalStateException("Invalid tool call IDs in conversation");
            }
            return ids;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read tool call JSON", e);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ToolCallPayload(String toolCallId) {
    }

    private String buildTranscript(List<AppStateRepository.ConversationMessageRow> rows) {
        StringBuilder transcript = new StringBuilder();
        long previousTurn = Long.MIN_VALUE;
        for (var row : rows) {
            if (row.turnId() != previousTurn) {
                transcript.append("[turn ").append(row.turnId()).append("]\n");
                previousTurn = row.turnId();
            }
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
        return transcript.toString();
    }

    private int estimateSummaryRequestTokens(ModelDefinition model,
            List<AppStateRepository.ConversationMessageRow> rows) {
        return estimatePromptTokens(SUMMARY_SYSTEM_PROMPT) + estimateTranscriptTokens(rows) + model.outputTokens();
    }

    private PromptTokenCosts precomputeTokenCosts(AgentDefinition agent, ModelDefinition model, String workspaceRoot,
            String userText) {
        int promptTokens = estimatePromptTokens(systemPromptComposer.composeForAgent(agent, workspaceRoot,
                skillDiscoveryService.discover(Path.of(workspaceRoot))));
        return new PromptTokenCosts(promptTokens, estimateTextTokens(userText), toolSchemaTokens(agent),
                model.outputTokens());
    }

    private int estimateTurnTokens(PromptTokenCosts costs, List<AppStateRepository.ConversationMessageRow> rows) {
        return costs.promptTokens() + estimateRowsTokens(rows) + costs.userTextTokens() + costs.toolSchemaTokens()
                + costs.outputTokens();
    }

    private int estimateRowsTokens(List<AppStateRepository.ConversationMessageRow> rows) {
        return rows.stream().mapToInt(this::estimateRowTokens).sum();
    }

    private int estimateTranscriptTokens(List<AppStateRepository.ConversationMessageRow> rows) {
        return estimateTextTokens(buildTranscript(rows)) + rows.size() * MESSAGE_OVERHEAD_TOKENS;
    }

    private int estimateRowTokens(AppStateRepository.ConversationMessageRow row) {
        int tokens = MESSAGE_OVERHEAD_TOKENS + estimateTextTokens(row.content())
                + estimateTextTokens(row.toolCallsJson()) + estimateTextTokens(row.toolCallId());
        if (row.agentId() != null) {
            tokens += estimateTextTokens(row.agentId()) + estimateTextTokens(row.agentName())
                    + estimateTextTokens(row.modelId()) + estimateTextTokens(row.thinkingLevel());
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
        // 250k hardcoded, due to quality degradation. let compaction take care of this.
        return Math.min(250_000, budget - Math.max(512, budget / 10));
    }

    private record PromptTokenCosts(int promptTokens, int userTextTokens, int toolSchemaTokens, int outputTokens) {
    }

}
