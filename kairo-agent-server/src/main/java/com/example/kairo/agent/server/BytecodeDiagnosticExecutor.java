package com.example.kairo.agent.server;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bounded executor for slow bytecode diagnostic work (preview, capture, diff).
 *
 * <p>These operations must never run on a business thread or on the agent's
 * rule-dispatch path. The HTTP layer dispatches each request to this fixed-size
 * daemon pool and awaits the result with a hard timeout, so a slow or runaway
 * diagnostic cannot pin a business thread or grow the HTTP pool unbounded.
 *
 * <p>Threads are daemon, minimum-priority and named {@code kairo-bytecode-diagnostic-N}
 * so they are easy to identify in a thread dump of the target JVM.
 */
final class BytecodeDiagnosticExecutor implements AutoCloseable {

    private final ExecutorService executor;
    private final long timeoutMillis;

    BytecodeDiagnosticExecutor(long timeoutMillis, int concurrency) {
        if (timeoutMillis <= 0) {
            throw new IllegalArgumentException("timeoutMillis must be > 0: " + timeoutMillis);
        }
        if (concurrency <= 0) {
            throw new IllegalArgumentException("concurrency must be > 0: " + concurrency);
        }
        this.timeoutMillis = timeoutMillis;
        this.executor = new ThreadPoolExecutor(
                concurrency, concurrency, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(concurrency), namedFactory(),
                new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * Submit {@code task} and block until it completes or the timeout elapses.
     *
     * @throws DiagnosticTimeoutException if the task does not finish in time, or the
     *         awaiting thread is interrupted while waiting
     * @throws DiagnosticFailedException  if the task itself threw
     */
    <T> T submitAndAwait(Callable<T> task) {
        Objects.requireNonNull(task, "task");
        Future<T> future;
        try {
            future = executor.submit(task);
        } catch (RejectedExecutionException e) {
            throw new DiagnosticBusyException();
        }
        try {
            return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new DiagnosticTimeoutException(timeoutMillis);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                throw new DiagnosticTimeoutException(timeoutMillis);
            }
            throw new DiagnosticFailedException(cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            throw new DiagnosticTimeoutException(timeoutMillis);
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(1, TimeUnit.SECONDS)) {
                // best effort: remaining tasks were interrupted on shutdownNow
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static ThreadFactory namedFactory() {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, "kairo-bytecode-diagnostic-" + counter.incrementAndGet());
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
        };
    }

    /** The diagnostic operation did not finish within the configured timeout. */
    static final class DiagnosticTimeoutException extends RuntimeException {
        DiagnosticTimeoutException(long timeoutMillis) {
            super("diagnostic operation timed out after " + timeoutMillis + "ms");
        }
    }

    /** The diagnostic operation itself threw; the cause is the original failure. */
    static final class DiagnosticFailedException extends RuntimeException {
        DiagnosticFailedException(Throwable cause) {
            super(cause.getMessage(), cause);
        }
    }

    /** All workers and bounded queue slots are occupied. */
    static final class DiagnosticBusyException extends RuntimeException {
        DiagnosticBusyException() {
            super("diagnostic executor is busy");
        }
    }
}
