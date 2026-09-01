package com.judepereira.jupiter.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.judepereira.jupiter.agent.llm.dto.ModelResponse;
import com.judepereira.jupiter.agent.llm.dto.ModelResponseMetadata;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
@RequiredArgsConstructor
public class TokenUsageService {

    private final AppStateRepository repository;
    private final ObjectMapper objectMapper;

    /** Records only completed responses for a real persisted session. */
    @Transactional
    public void recordModelResponse(Long sessionId, String modelKey, String operation, ModelResponse response) {
        if (sessionId == null || modelKey == null || modelKey.isBlank() || response == null) {
            return;
        }
        var contextOptional = repository.findSessionUsageContext(sessionId);
        if (contextOptional.isEmpty()) {
            return;
        }
        AppStateRepository.SessionUsageContext context = contextOptional.get();
        Instant occurredAt = Instant.now();
        ModelResponseMetadata metadata = response.getMetadata();
        Instant hour = occurredAt.truncatedTo(ChronoUnit.HOURS);
        Persistence.TokenUsageFact fact = new Persistence.TokenUsageFact(
                context.sessionUsageKey(), context.sessionId(), context.workspaceId(), context.projectId(),
                context.sessionName(), context.workspaceName(), context.projectName(), context.workspacePath(), context.projectPath(),
                occurredAt, hour, modelKey, operation == null || operation.isBlank() ? "harness" : operation,
                metadata.inputTokenCount(), metadata.outputTokenCount(), metadata.totalTokenCount(), metadata.cachedInputTokenCount(),
                metadata.cacheWriteTokenCount(), metadata.reasoningTokenCount(), metadata.responseId(), metadata.modelId(), metadata.finishReason(),
                metadata.providerMetadata());
        String providerMetadataJson;
        try {
            providerMetadataJson = objectMapper.writeValueAsString(fact.providerMetadata());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize model provider metadata", e);
        }
        repository.insertTokenUsageFact(fact, providerMetadataJson);
        repository.upsertTokenUsageHourly(fact);
    }

    public List<Persistence.TokenUsageHourly> findHourlyUsage(String sessionUsageKey, Instant fromInclusive, Instant toExclusive) {
        return repository.findHourlyTokenUsage(sessionUsageKey, fromInclusive, toExclusive);
    }

    public List<Persistence.ProjectTokenUsageHourly> findProjectHourlyUsage(long projectId, Instant fromInclusive, Instant toExclusive) {
        return repository.findProjectHourlyTokenUsage(projectId, fromInclusive, toExclusive);
    }

    List<Persistence.TokenUsageFact> findFacts(String sessionUsageKey) {
        return repository.findTokenUsageFacts(sessionUsageKey);
    }
}
