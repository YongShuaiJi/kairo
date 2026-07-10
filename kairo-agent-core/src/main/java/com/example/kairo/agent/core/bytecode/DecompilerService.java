package com.example.kairo.agent.core.bytecode;

import com.example.kairo.api.bytecode.BytecodeDecompiler;
import com.example.kairo.api.bytecode.ClassIdentity;
import com.example.kairo.api.bytecode.DecompilationResult;
import com.example.kairo.api.bytecode.DecompilationStatus;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Isolates a {@link BytecodeDecompiler} behind a dedicated bounded diagnostic
 * executor with a hard timeout and an input-size cap. Decompilation never runs on a
 * business thread or the agent control pool: callers always receive a
 * {@link DecompilationResult} describing success, unavailability, timeout or failure,
 * and the structured bytecode diff remains usable when decompilation is unavailable.
 */
public final class DecompilerService implements AutoCloseable {

    private final BytecodeDecompiler decompiler;
    private final ExecutorService executor;
    private final int maxBytes;
    private final long timeoutMillis;

    public DecompilerService(BytecodeDecompiler decompiler, int maxBytes, long timeoutMillis) {
        this(decompiler, maxBytes, timeoutMillis, 1);
    }

    public DecompilerService(BytecodeDecompiler decompiler, int maxBytes, long timeoutMillis, int maxConcurrency) {
        this.decompiler = Objects.requireNonNull(decompiler, "decompiler");
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be > 0");
        }
        if (timeoutMillis <= 0) {
            throw new IllegalArgumentException("timeoutMillis must be > 0");
        }
        if (maxConcurrency <= 0) {
            throw new IllegalArgumentException("maxConcurrency must be > 0");
        }
        this.maxBytes = maxBytes;
        this.timeoutMillis = timeoutMillis;
        this.executor = Executors.newFixedThreadPool(maxConcurrency, runnable -> {
            Thread thread = new Thread(runnable, "kairo-decompiler");
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
        });
    }

    public DecompilationResult decompile(ClassIdentity classIdentity, byte[] bytes) {
        Objects.requireNonNull(classIdentity, "classIdentity");
        Objects.requireNonNull(bytes, "bytes");
        long started = System.currentTimeMillis();
        if (bytes.length > maxBytes) {
            return failed("input too large: " + bytes.length + " > " + maxBytes, started);
        }
        Future<DecompilationResult> future = executor.submit(
                new DecompileTask(decompiler, classIdentity, bytes));
        try {
            return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            return failed("decompilation timed out after " + timeoutMillis + "ms", started);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            return failed("decompiler threw " + cause.getClass().getSimpleName()
                    + ": " + cause.getMessage(), started);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return failed("decompilation interrupted", started);
        }
    }

    public String decompilerName() {
        return decompiler.name();
    }

    @Override
    public void close() {
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(1, TimeUnit.SECONDS)) {
                // best effort; remaining tasks are interrupted
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private DecompilationResult failed(String message, long started) {
        return new DecompilationResult(DecompilationStatus.FAILED, decompiler.name(), null,
                List.of(message), System.currentTimeMillis() - started);
    }

    private record DecompileTask(BytecodeDecompiler decompiler, ClassIdentity classIdentity, byte[] bytes)
            implements Callable<DecompilationResult> {
        @Override
        public DecompilationResult call() {
            return decompiler.decompile(classIdentity, bytes);
        }
    }
}
