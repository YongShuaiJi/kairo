package com.example.kairo.agent.core.bytecode;

import com.example.kairo.api.bytecode.BytecodeDecompiler;
import com.example.kairo.api.bytecode.ClassIdentity;
import com.example.kairo.api.bytecode.DecompilationResult;
import com.example.kairo.api.bytecode.DecompilationStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Coverage for the {@link DecompilerService} guard rail: a real decompiler (here a
 * stub) is isolated on a bounded daemon pool with a hard timeout and an input-size cap,
 * and any failure it raises is converted to a {@link DecompilationStatus#FAILED} result.
 */
class DecompilerServiceTest {

    private static final ClassIdentity ID = new ClassIdentity("x.Y", "loader");

    @Test
    void returnsSuccessWhenDecompilerSucceeds() {
        BytecodeDecompiler stub = new StubDecompiler(DecompilationStatus.SUCCESS, "source", null);
        try (DecompilerService service = new DecompilerService(stub, 1024, 2000L)) {
            DecompilationResult result = service.decompile(ID, new byte[]{1});
            assertThat(result.status()).isEqualTo(DecompilationStatus.SUCCESS);
            assertThat(result.sourceCode()).isEqualTo("source");
        }
    }

    @Test
    void timesOutWhenDecompilerIsSlow() {
        BytecodeDecompiler stub = new StubDecompiler(DecompilationStatus.SUCCESS, "source",
                500L); // sleeps 500ms inside decompile()
        try (DecompilerService service = new DecompilerService(stub, 1024, 50L)) {
            DecompilationResult result = service.decompile(ID, new byte[]{1});
            assertThat(result.status()).isEqualTo(DecompilationStatus.FAILED);
            assertThat(result.sourceCode()).isNull();
            assertThat(result.diagnostics()).anyMatch(s -> s.contains("timed out"));
        }
    }

    @Test
    void rejectsInputAboveMaxBytesWithoutInvokingDecompiler() {
        CountingDecompiler stub = new CountingDecompiler();
        try (DecompilerService service = new DecompilerService(stub, 4, 2000L)) {
            DecompilationResult result = service.decompile(ID, new byte[]{1, 2, 3, 4, 5});
            assertThat(result.status()).isEqualTo(DecompilationStatus.FAILED);
            assertThat(result.diagnostics()).anyMatch(s -> s.contains("too large"));
            assertThat(stub.invocations.get()).isZero();
        }
    }

    @Test
    void convertsDecompilerExceptionToFailedResult() {
        BytecodeDecompiler stub = new BytecodeDecompiler() {
            @Override
            public DecompilationResult decompile(ClassIdentity identity, byte[] bytes) {
                throw new IllegalStateException("boom");
            }

            @Override
            public String name() {
                return "throwing";
            }
        };
        try (DecompilerService service = new DecompilerService(stub, 1024, 2000L)) {
            DecompilationResult result = service.decompile(ID, new byte[]{1});
            assertThat(result.status()).isEqualTo(DecompilationStatus.FAILED);
            assertThat(result.diagnostics()).anyMatch(s -> s.contains("IllegalStateException") && s.contains("boom"));
        }
    }

    @Test
    void rejectsWhenWorkerAndBoundedQueueAreFull() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        BytecodeDecompiler blocking = new BytecodeDecompiler() {
            public DecompilationResult decompile(ClassIdentity identity, byte[] bytes) {
                entered.countDown();
                try { release.await(2, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return new DecompilationResult(DecompilationStatus.SUCCESS, name(), "source", List.of(), 0L);
            }
            public String name() { return "blocking"; }
        };
        try (DecompilerService service = new DecompilerService(blocking, 1024, 3000L, 1)) {
            CompletableFuture<DecompilationResult> first = CompletableFuture.supplyAsync(
                    () -> service.decompile(ID, new byte[]{1}));
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            CompletableFuture<DecompilationResult> queued = CompletableFuture.supplyAsync(
                    () -> service.decompile(ID, new byte[]{2}));
            Thread.sleep(25L);
            DecompilationResult busy = service.decompile(ID, new byte[]{3});
            assertThat(busy.status()).isEqualTo(DecompilationStatus.FAILED);
            assertThat(busy.diagnostics()).anyMatch(message -> message.contains("bounded queue is full"));
            release.countDown();
            first.get(2, TimeUnit.SECONDS);
            queued.get(2, TimeUnit.SECONDS);
        }
    }

    /** A stub that can sleep, succeed, fail, or count invocations. */
    private static final class StubDecompiler implements BytecodeDecompiler {
        private final DecompilationStatus status;
        private final String source;
        private final long sleepMillis;

        StubDecompiler(DecompilationStatus status, String source, Long sleepMillis) {
            this.status = status;
            this.source = source;
            this.sleepMillis = sleepMillis == null ? 0L : sleepMillis;
        }

        @Override
        public DecompilationResult decompile(ClassIdentity identity, byte[] bytes) {
            if (sleepMillis > 0) {
                try {
                    Thread.sleep(sleepMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return new DecompilationResult(status, name(),
                    status == DecompilationStatus.SUCCESS ? source : null,
                    List.of(), 0L);
        }

        @Override
        public String name() {
            return "stub";
        }
    }

    private static final class CountingDecompiler implements BytecodeDecompiler {
        final AtomicInteger invocations = new AtomicInteger();

        @Override
        public DecompilationResult decompile(ClassIdentity identity, byte[] bytes) {
            invocations.incrementAndGet();
            return new DecompilationResult(DecompilationStatus.SUCCESS, name(), "x", List.of(), 0L);
        }

        @Override
        public String name() {
            return "counting";
        }
    }
}
