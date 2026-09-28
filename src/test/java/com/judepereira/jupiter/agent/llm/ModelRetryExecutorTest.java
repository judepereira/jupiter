package com.judepereira.jupiter.agent.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.judepereira.jupiter.agent.harness.StreamCancelledException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ModelRetryExecutorTest {
    @Test
    void retriesOrdinaryFailuresAndPreservesTerminalCause() {
        ModelRetryExecutor executor = new ModelRetryExecutor(2, Duration.ZERO, Duration.ZERO);
        AtomicInteger attempts = new AtomicInteger();
        RuntimeException failure = new RuntimeException("provider");

        IllegalStateException wrapped = assertThrows(IllegalStateException.class, () -> executor.execute(() -> {
            attempts.incrementAndGet();
            throw failure;
        }, ignored -> true, "test", "request", null, "request failed"));

        assertEquals(3, attempts.get());
        assertEquals("request failed", wrapped.getMessage());
        assertSame(failure, wrapped.getCause());
    }

    @Test
    void propagatesNestedLifecycleFailuresWithoutRetry() {
        ModelRetryExecutor executor = new ModelRetryExecutor(3, Duration.ZERO, Duration.ZERO);
        StreamCancelledException cancellation = new StreamCancelledException();
        AtomicInteger attempts = new AtomicInteger();

        assertSame(cancellation, assertThrows(StreamCancelledException.class, () -> executor.execute(() -> {
            attempts.incrementAndGet();
            throw new RuntimeException(cancellation);
        }, ignored -> true, "test", "request", null, "request failed")));
        assertEquals(1, attempts.get());
    }

    @Test
    void calculatesSaturatingBackoffAndSupportsZero() {
        assertEquals(0, ModelRetryExecutor.backoffMillis(Duration.ZERO, Duration.ZERO, Integer.MAX_VALUE));
        assertEquals(8, ModelRetryExecutor.backoffMillis(Duration.ofMillis(1), Duration.ofMillis(8), 4));
        assertEquals(8,
                ModelRetryExecutor.backoffMillis(Duration.ofMillis(1), Duration.ofMillis(8), Integer.MAX_VALUE));
        assertEquals(Long.MAX_VALUE, ModelRetryExecutor.backoffMillis(Duration.ofMillis(Long.MAX_VALUE),
                Duration.ofMillis(Long.MAX_VALUE), Integer.MAX_VALUE));
    }

    @Test
    void rejectsInvalidBackoffDurations() {
        assertThrows(IllegalArgumentException.class, () -> new ModelRetryExecutor(1, null, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new ModelRetryExecutor(1, Duration.ZERO, null));
        assertThrows(IllegalArgumentException.class,
                () -> new ModelRetryExecutor(1, Duration.ofMillis(-1), Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> new ModelRetryExecutor(1, Duration.ZERO, Duration.ofMillis(-1)));
        assertThrows(IllegalArgumentException.class,
                () -> new ModelRetryExecutor(1, Duration.ofMillis(2), Duration.ofMillis(1)));
    }

    @Test
    void formatsStreamingCauseDetail() {
        ModelRetryExecutor executor = new ModelRetryExecutor(0, Duration.ZERO, Duration.ZERO);
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> executor.executeStreaming(
                        () -> new ModelRetryExecutor.StreamingAttemptResult<>(null,
                                new RuntimeException("provider detail"), false),
                        ignored -> true, "test", "stream", "model", "stream failed"));

        assertEquals("stream failed: provider detail", failure.getMessage());
    }
}
