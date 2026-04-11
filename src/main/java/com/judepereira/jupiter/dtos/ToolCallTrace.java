package com.judepereira.jupiter.dtos;

import java.time.Instant;

public record ToolCallTrace(
        String toolName,
        String toolArgsPayload,
        String toolResultPayload,
        String toolErrorPayload,
        Instant startedAt,
        Long durationMillis) {
}
