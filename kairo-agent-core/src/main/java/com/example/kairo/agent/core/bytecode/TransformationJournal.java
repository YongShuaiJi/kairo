package com.example.kairo.agent.core.bytecode;

import com.example.kairo.api.bytecode.ClassIdentity;
import com.example.kairo.api.bytecode.TransformationDiagnostic;
import com.example.kairo.api.bytecode.TransformationResult;
import com.example.kairo.api.bytecode.TransformationRevision;
import com.example.kairo.api.bytecode.TransformationStatus;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Bounded in-agent journal of transformation lifecycle events.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>assigns a monotonic per-class {@link TransformationRevision} via a
 *       lock-free {@link AtomicLong} per {@link ClassIdentity};</li>
 *   <li>appends immutable {@link TransformationResult} records for each
 *       lifecycle phase (start / success / failure / verification / recovery /
 *       skip);</li>
 *   <li>keeps bounded FIFO history both per class and globally, dropping the
 *       oldest entries first;</li>
 *   <li>never strongly references a {@code ClassLoader}; keys are
 *       {@link ClassIdentity} value types.</li>
 * </ul>
 *
 * <p>Clearing history never resets the revision counter, so revisions stay
 * monotonic for the agent's lifetime. A full reset is achieved by discarding
 * the journal instance.
 */
public final class TransformationJournal {

    /**
     * @param perClassLimit maximum history entries kept per class, must be &gt; 0
     * @param globalLimit   maximum history entries kept globally, must be
     *                      &gt;= {@code perClassLimit}
     */
    public record Config(int perClassLimit, int globalLimit) {
        public Config {
            if (perClassLimit <= 0) {
                throw new IllegalArgumentException("perClassLimit must be > 0: " + perClassLimit);
            }
            if (globalLimit <= 0) {
                throw new IllegalArgumentException("globalLimit must be > 0: " + globalLimit);
            }
            if (globalLimit < perClassLimit) {
                throw new IllegalArgumentException("globalLimit must be >= perClassLimit");
            }
        }
    }

    private final Config config;
    private final ConcurrentHashMap<ClassIdentity, AtomicLong> revisions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ClassIdentity, Deque<TransformationResult>> perClass = new ConcurrentHashMap<>();
    private final Deque<TransformationResult> global = new ArrayDeque<>();
    private final ReentrantLock lock = new ReentrantLock();

    public TransformationJournal(Config config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    public Config config() {
        return config;
    }

    /**
     * Allocate the next monotonic revision for a class. First call returns
     * {@code r1}; thread-safe and never returns a duplicate for the same class.
     */
    public TransformationRevision nextRevision(ClassIdentity classIdentity) {
        Objects.requireNonNull(classIdentity, "classIdentity");
        AtomicLong counter = revisions.computeIfAbsent(classIdentity, ignored -> new AtomicLong(0L));
        return TransformationRevision.of(counter.incrementAndGet());
    }

    /**
     * The last revision allocated for a class, or {@link TransformationRevision#INITIAL}
     * if none has been allocated yet.
     */
    public TransformationRevision currentRevision(ClassIdentity classIdentity) {
        Objects.requireNonNull(classIdentity, "classIdentity");
        AtomicLong counter = revisions.get(classIdentity);
        return counter == null ? TransformationRevision.INITIAL : TransformationRevision.of(counter.get());
    }

    public void record(TransformationResult result) {
        Objects.requireNonNull(result, "result");
        lock.lock();
        try {
            append(perClass.computeIfAbsent(result.classIdentity(), ignored -> new ArrayDeque<>()),
                    result, config.perClassLimit());
            append(global, result, config.globalLimit());
        } finally {
            lock.unlock();
        }
    }

    public void recordStart(ClassIdentity classIdentity, TransformationRevision revision,
                            String inputHash, long attemptedAtMillis) {
        record(build(classIdentity, revision, TransformationStatus.STARTED, inputHash, null,
                List.of(), attemptedAtMillis, 0L));
    }

    public void recordSuccess(ClassIdentity classIdentity, TransformationRevision revision,
                              String inputHash, String outputHash,
                              long attemptedAtMillis, long durationMillis) {
        record(build(classIdentity, revision, TransformationStatus.SUCCEEDED, inputHash, outputHash,
                List.of(), attemptedAtMillis, durationMillis));
    }

    public void recordFailure(ClassIdentity classIdentity, TransformationRevision revision,
                              String inputHash, List<TransformationDiagnostic> diagnostics,
                              long attemptedAtMillis, long durationMillis) {
        record(build(classIdentity, revision, TransformationStatus.FAILED, inputHash, null,
                diagnostics, attemptedAtMillis, durationMillis));
    }

    /**
     * @param passed true records {@link TransformationStatus#VERIFIED}; false
     *               records {@link TransformationStatus#FAILED} with the
     *               supplied diagnostics
     */
    public void recordVerification(ClassIdentity classIdentity, TransformationRevision revision,
                                   boolean passed, List<TransformationDiagnostic> diagnostics,
                                   long attemptedAtMillis, long durationMillis) {
        TransformationStatus status = passed ? TransformationStatus.VERIFIED : TransformationStatus.FAILED;
        record(build(classIdentity, revision, status, null, null, diagnostics, attemptedAtMillis, durationMillis));
    }

    public void recordRecovery(ClassIdentity classIdentity, TransformationRevision revision,
                               String message, long attemptedAtMillis) {
        TransformationDiagnostic diagnostic = TransformationDiagnostic.info("RECOVERY", message);
        record(build(classIdentity, revision, TransformationStatus.RECOVERED, null, null,
                List.of(diagnostic), attemptedAtMillis, 0L));
    }

    public void recordSkip(ClassIdentity classIdentity, TransformationRevision revision,
                           String reason, long attemptedAtMillis) {
        TransformationDiagnostic diagnostic = TransformationDiagnostic.info("SKIPPED", reason);
        record(build(classIdentity, revision, TransformationStatus.SKIPPED, null, null,
                List.of(diagnostic), attemptedAtMillis, 0L));
    }

    /**
     * Immutable chronological copy of a class's bounded history (oldest first).
     */
    public List<TransformationResult> history(ClassIdentity classIdentity) {
        Objects.requireNonNull(classIdentity, "classIdentity");
        lock.lock();
        try {
            Deque<TransformationResult> deque = perClass.get(classIdentity);
            if (deque == null || deque.isEmpty()) {
                return List.of();
            }
            return List.copyOf(deque);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Immutable chronological copy of the bounded global history (oldest first).
     */
    public List<TransformationResult> history() {
        lock.lock();
        try {
            return List.copyOf(global);
        } finally {
            lock.unlock();
        }
    }

    public int recordCount(ClassIdentity classIdentity) {
        Objects.requireNonNull(classIdentity, "classIdentity");
        lock.lock();
        try {
            Deque<TransformationResult> deque = perClass.get(classIdentity);
            return deque == null ? 0 : deque.size();
        } finally {
            lock.unlock();
        }
    }

    public int recordCount() {
        lock.lock();
        try {
            return global.size();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Remove the history of one class (globally and per-class). The revision
     * counter is intentionally preserved so revisions stay monotonic.
     *
     * <p>The global deque may still hold entries for this class that were already
     * evicted from the per-class deque (when {@code perClassLimit < globalLimit}),
     * so the global deque is filtered by identity rather than via {@code removeAll}
     * of the per-class view, which would leave stale entries behind.
     */
    public void clear(ClassIdentity classIdentity) {
        Objects.requireNonNull(classIdentity, "classIdentity");
        lock.lock();
        try {
            perClass.remove(classIdentity);
            global.removeIf(result -> result.classIdentity().equals(classIdentity));
        } finally {
            lock.unlock();
        }
    }

    public void clear() {
        lock.lock();
        try {
            perClass.clear();
            global.clear();
        } finally {
            lock.unlock();
        }
    }

    private static void append(Deque<TransformationResult> deque, TransformationResult result, int limit) {
        deque.addLast(result);
        while (deque.size() > limit) {
            deque.pollFirst();
        }
    }

    private static TransformationResult build(ClassIdentity classIdentity, TransformationRevision revision,
                                              TransformationStatus status, String inputHash, String outputHash,
                                              List<TransformationDiagnostic> diagnostics,
                                              long attemptedAtMillis, long durationMillis) {
        return new TransformationResult(classIdentity, revision, status, inputHash, outputHash,
                diagnostics, attemptedAtMillis, durationMillis);
    }
}
