package com.judepereira.jupiter.persistence;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TokenUsageRetentionAndQueryTests {

    @Test
    void retentionDeletesOnlyRowsOlderThanSixtyDays() {
        AppStateRepository repository = TestAppStateSupport.appStateContext(event -> {}).repository();
        TokenUsageRetentionService retention = new TokenUsageRetentionService(repository);
        Instant now = Instant.parse("2026-08-31T12:34:56Z");
        Instant cutoff = now.minus(60, ChronoUnit.DAYS);

        Instant cutoffHour = cutoff.truncatedTo(ChronoUnit.HOURS);
        insert(repository, "old", cutoffHour.minusSeconds(1), cutoffHour.minus(1, ChronoUnit.HOURS), "model", "chat", 1);
        insert(repository, "boundary", cutoff, cutoffHour, "model", "chat", 2);

        TokenUsageRetentionService.RetentionResult result = retention.purgeExpiredTokenUsage(now);

        assertThat(result.cutoff()).isEqualTo(cutoff);
        assertThat(repository.findHourlyTokenUsage("old", cutoff.minusSeconds(1), now.plusSeconds(1))).isEmpty();
        List<Persistence.TokenUsageHourly> boundaryRows = repository.findHourlyTokenUsage("boundary", cutoff.truncatedTo(ChronoUnit.HOURS), now.plusSeconds(1));
        assertThat(boundaryRows).hasSize(1);
        assertThat(boundaryRows.getFirst().requestCount()).isEqualTo(1);
    }

    @Test
    void retentionRebuildsBoundaryHourFromFactsAtAnArbitraryCutoff() {
        AppStateRepository repository = TestAppStateSupport.appStateContext(event -> {}).repository();
        Instant now = Instant.parse("2026-08-31T12:34:56Z");
        Instant cutoff = now.minus(60, ChronoUnit.DAYS);
        Instant hour = cutoff.truncatedTo(ChronoUnit.HOURS);

        insert(repository, "boundary", cutoff.minusSeconds(1), hour, "model", "chat", 1);
        insert(repository, "boundary", cutoff.plusSeconds(1), hour, "model", "compaction", 2);

        retention(repository).purgeExpiredTokenUsage(now);

        Persistence.TokenUsageHourly row = repository.findHourlyTokenUsage("boundary", hour, hour.plus(1, ChronoUnit.HOURS)).getFirst();
        assertThat(row.requestCount()).isEqualTo(1);
        assertThat(row.totalTokenCount()).isEqualTo(2L);
    }

    private static TokenUsageRetentionService retention(AppStateRepository repository) {
        return new TokenUsageRetentionService(repository);
    }

    @Test
    void hourlyAggregationCombinesOperationsAndPreservesReportedMetricsWhenLaterRequestOmitsThem() {
        AppStateRepository repository = TestAppStateSupport.appStateContext(event -> {}).repository();
        Instant hour = Instant.parse("2026-08-30T10:00:00Z");
        insertFact(repository, new Persistence.TokenUsageFact(
                "mixed", 1, 1, 1, "session", "workspace", "project", "/workspace", "/project",
                hour.plusSeconds(10), hour, "model", "chat", 10, 5, 15, 4, null, null,
                null, null, null, Map.of()));
        insertFact(repository, new Persistence.TokenUsageFact(
                "mixed", 1, 1, 1, "session", "workspace", "project", "/workspace", "/project",
                hour.plusSeconds(20), hour, "model", "chat", null, null, null, null, 0, 7,
                null, null, null, Map.of()));

        Persistence.TokenUsageHourly row = repository.findHourlyTokenUsage(
                "mixed", hour, hour.plus(1, ChronoUnit.HOURS)).getFirst();

        assertThat(row.requestCount()).isEqualTo(2);
        assertThat(row.inputTokenCount()).isEqualTo(10L);
        assertThat(row.outputTokenCount()).isEqualTo(5L);
        assertThat(row.totalTokenCount()).isEqualTo(15L);
        assertThat(row.cachedInputTokenCount()).isEqualTo(4L);
        assertThat(row.cacheWriteTokenCount()).isEqualTo(0L);
        assertThat(row.reasoningTokenCount()).isEqualTo(7L);
    }

    @Test
    void projectHourlyQueryAggregatesAllSessionRowsAndUsesHalfOpenWindow() {
        AppStateRepository repository = TestAppStateSupport.appStateContext(event -> {}).repository();
        Instant hour = Instant.parse("2026-08-30T10:00:00Z");
        insertProjectRow(repository, "session-a", 1, hour, "model-a", 4, 2, 6);
        insertProjectRow(repository, "session-b", 1, hour, "model-a", 3, 1, 4);
        insertProjectRow(repository, "session-c", 1, hour, "model-b", 8, 4, 12);
        insertProjectRow(repository, "other-project", 2, hour, "model-a", 99, 99, 198);
        insertProjectRow(repository, "session-d", 1, hour.plus(1, ChronoUnit.HOURS), "model-a", 99, 99, 198);

        List<Persistence.ProjectTokenUsageHourly> rows = repository.findProjectHourlyTokenUsage(
                1, hour, hour.plus(1, ChronoUnit.HOURS));

        assertThat(rows).extracting(Persistence.ProjectTokenUsageHourly::modelKey,
                        Persistence.ProjectTokenUsageHourly::requestCount,
                        Persistence.ProjectTokenUsageHourly::inputTokenCount,
                        Persistence.ProjectTokenUsageHourly::outputTokenCount,
                        Persistence.ProjectTokenUsageHourly::totalTokenCount)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("model-a", 2L, 7L, 3L, 10L),
                        org.assertj.core.groups.Tuple.tuple("model-b", 1L, 8L, 4L, 12L));
    }

    @Test
    void hourlyQueryReturnsStoredModelAndOperationAggregatesInWindow() {
        AppStateRepository repository = TestAppStateSupport.appStateContext(event -> {}).repository();
        Instant hour = Instant.parse("2026-08-30T10:00:00Z");
        insert(repository, "session", hour.plusSeconds(10), hour, "model-a", "chat", 10);
        insert(repository, "session", hour.plusSeconds(20), hour, "model-a", "chat", 5);
        insert(repository, "session", hour.plusSeconds(30), hour, "model-b", "tool", 7);
        insert(repository, "session", hour.plusSeconds(10), hour.plus(1, ChronoUnit.HOURS), "model-a", "chat", 99);

        List<Persistence.TokenUsageHourly> rows = repository.findHourlyTokenUsage(
                "session", hour, hour.plus(1, ChronoUnit.HOURS));

        assertThat(rows).extracting(Persistence.TokenUsageHourly::modelKey,
                        Persistence.TokenUsageHourly::requestCount,
                        Persistence.TokenUsageHourly::totalTokenCount)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("model-a", 2L, 15L),
                        org.assertj.core.groups.Tuple.tuple("model-b", 1L, 7L));
    }

    private static void insertProjectRow(AppStateRepository repository, String sessionKey, long projectId, Instant hour, String model,
                                          int input, int output, int total) {
        insertFact(repository, new Persistence.TokenUsageFact(
                sessionKey, 1, 1, projectId, "session", "workspace", "project", "/workspace", "/project",
                hour.plusSeconds(10), hour, model, "chat", input, output, total, null, null, null,
                null, null, null, Map.of()));
    }

    private static void insert(AppStateRepository repository, String sessionKey, Instant occurredAt,
                               Instant hour, String model, String operation, int totalTokens) {
        Persistence.TokenUsageFact fact = new Persistence.TokenUsageFact(
                sessionKey, 1, 1, 1, "session", "workspace", "project", "/workspace", "/project",
                occurredAt, hour, model, operation, 10, 5, totalTokens, null, null, null,
                null, null, null, Map.of());
        insertFact(repository, fact);
    }

    private static void insertFact(AppStateRepository repository, Persistence.TokenUsageFact fact) {
        repository.insertTokenUsageFact(fact, "{}");
        repository.upsertTokenUsageHourly(fact);
    }
}
