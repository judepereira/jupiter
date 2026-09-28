package com.judepereira.jupiter.agent.llm;

import com.judepereira.jupiter.agent.harness.StreamCancelledException;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.function.Function;
import java.util.function.Predicate;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ModelRetryExecutor {
    private final int maxRetries;
    private final Duration initialBackoff;
    private final Duration maxBackoff;

    public ModelRetryExecutor(int maxRetries, Duration initialBackoff, Duration maxBackoff) {
        if (maxRetries < 0) {
            throw new IllegalArgumentException("max retries must not be negative");
        }
        this.maxRetries = maxRetries;
        this.initialBackoff = requireDuration(initialBackoff, "initial backoff");
        this.maxBackoff = requireDuration(maxBackoff, "max backoff");
        if (maxBackoff.compareTo(initialBackoff) < 0) {
            throw new IllegalArgumentException("max backoff must not be smaller than initial backoff");
        }
    }

    public <T> T execute(Callable<T> operation, Predicate<Throwable> retryable, String provider, String operationName,
            String modelName, String failureMessage) {
        return execute(operation, retryable, provider, operationName, modelName, failure -> failureMessage);
    }

    public <T> T execute(Callable<T> operation, Predicate<Throwable> retryable, String provider, String operationName,
            String modelName, Function<Throwable, String> failureMessage) {
        int retries = 0;
        while (true) {
            try {
                return operation.call();
            } catch (Throwable failure) {
                String message = failureMessage.apply(failure);
                propagateSpecialCause(failure, message);
                if (!retryable.test(failure) || retries >= maxRetries) {
                    throw new IllegalStateException(message, failure);
                }
                sleepAndRetry(++retries, provider, operationName, modelName, message, failure);
            }
        }
    }

    public <T> T executeStreaming(StreamingAttempt<T> attempt, Predicate<Throwable> retryable, String provider,
            String operationName, String modelName, String failureMessage) {
        return executeStreaming(attempt, retryable, provider, operationName, modelName,
                failure -> withCauseMessage(failureMessage, failure));
    }

    public <T> T executeStreaming(StreamingAttempt<T> attempt, Predicate<Throwable> retryable, String provider,
            String operationName, String modelName, Function<Throwable, String> failureMessage) {
        int retries = 0;
        while (true) {
            StreamingAttemptResult<T> result;
            try {
                result = attempt.run();
            } catch (Throwable failure) {
                propagateSpecialCause(failure, failureMessage.apply(failure));
                if (!retryable.test(failure) || retries >= maxRetries) {
                    throw new IllegalStateException(failureMessage.apply(failure), failure);
                }
                sleepAndRetry(++retries, provider, operationName, modelName, failureMessage.apply(failure), failure);
                continue;
            }
            if (result.failure() == null) {
                return result.value();
            }
            propagateSpecialCause(result.failure(), failureMessage.apply(result.failure()));
            if (result.externallyVisible() || !retryable.test(result.failure()) || retries >= maxRetries) {
                throw new IllegalStateException(failureMessage.apply(result.failure()), result.failure());
            }
            sleepAndRetry(++retries, provider, operationName, modelName, failureMessage.apply(result.failure()),
                    result.failure());
        }
    }

    private void sleepAndRetry(int retry, String provider, String operation, String model, String failureMessage,
            Throwable failure) {
        long delay = backoffMillis(retry);
        log.warn("{} {} retry attempt={}/{} delay={}ms model={} exception={}", provider, operation, retry, maxRetries,
                delay, model == null ? "-" : model, failure.getClass().getName());
        try {
            Thread.sleep(delay);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failureMessage, interrupted);
        }
    }

    private static void propagateSpecialCause(Throwable failure, String message) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof Error error) {
                throw error;
            }
            if (current instanceof StreamCancelledException cancelled) {
                throw cancelled;
            }
            if (current instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(message, failure);
            }
        }
    }

    private static String withCauseMessage(String prefix, Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof InterruptedException) {
                return prefix.replace(" failed", " interrupted");
            }
        }
        String detail = failure.getMessage();
        if (detail == null || detail.isBlank()) {
            Throwable deepest = failure;
            while (deepest.getCause() != null) {
                deepest = deepest.getCause();
            }
            detail = deepest.toString();
        }
        return detail.isBlank() ? prefix : prefix + ": " + detail;
    }

    static long backoffMillis(Duration initial, Duration maximum, int retry) {
        long initialMillis = durationMillis(initial);
        long maxMillis = durationMillis(maximum);
        if (initialMillis == 0 || retry <= 1) {
            return Math.min(initialMillis, maxMillis);
        }
        long delay = initialMillis;
        for (int i = 1; i < retry && delay < maxMillis; i++) {
            if (delay > maxMillis / 2) {
                return maxMillis;
            }
            delay *= 2;
        }
        return Math.min(delay, maxMillis);
    }

    private long backoffMillis(int retry) {
        return backoffMillis(initialBackoff, maxBackoff, retry);
    }

    private static long durationMillis(Duration duration) {
        try {
            return duration.toMillis();
        } catch (ArithmeticException e) {
            return Long.MAX_VALUE;
        }
    }

    private static Duration requireDuration(Duration duration, String name) {
        if (duration == null || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must not be null or negative");
        }
        return duration;
    }

    @FunctionalInterface
    public interface StreamingAttempt<T> {
        StreamingAttemptResult<T> run() throws Exception;
    }

    public record StreamingAttemptResult<T>(T value, Throwable failure, boolean externallyVisible) {
    }
}
