package com.judepereira.jupiter.persistence;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Service
@RequiredArgsConstructor
public class TokenUsageRetentionService {

    static final long RETENTION_DAYS = 60;

    private final AppStateRepository repository;

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void purgeAtStartup() {
        purgeExpiredTokenUsage(Instant.now());
    }

    @Scheduled(fixedDelayString = "PT6H")
    @Transactional
    public void purgeOnSchedule() {
        purgeExpiredTokenUsage(Instant.now());
    }

    @Transactional
    RetentionResult purgeExpiredTokenUsage(Instant now) {
        Instant cutoff = now.minus(RETENTION_DAYS, ChronoUnit.DAYS);
        int factsDeleted = repository.deleteTokenUsageFactsBefore(cutoff);
        Instant cutoffHour = cutoff.truncatedTo(ChronoUnit.HOURS);
        int hourlyDeleted = repository.deleteTokenUsageHourlyBefore(cutoffHour);
        repository.rebuildTokenUsageHourlyAt(cutoff, cutoffHour);
        return new RetentionResult(cutoff, factsDeleted, hourlyDeleted);
    }

    record RetentionResult(Instant cutoff, int factsDeleted, int hourlyDeleted) {}
}
