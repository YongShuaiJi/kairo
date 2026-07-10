package com.example.kairo.agent.core.bytecode;

import com.example.kairo.api.bytecode.ClassIdentity;
import com.example.kairo.api.bytecode.TransformationDiagnostic;
import com.example.kairo.api.bytecode.TransformationResult;
import com.example.kairo.api.bytecode.TransformationRevision;
import com.example.kairo.api.bytecode.TransformationStatus;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransformationJournalTest {

    private static ClassIdentity ci(String name) {
        return new ClassIdentity(name, "loader-1");
    }

    @Test
    void nextRevisionIsMonotonicAndStartsAtOne() {
        TransformationJournal journal = new TransformationJournal(new TransformationJournal.Config(8, 32));
        ClassIdentity c = ci("com.example.Foo");

        assertThat(journal.currentRevision(c).value()).isZero();
        assertThat(journal.nextRevision(c).value()).isEqualTo(1L);
        assertThat(journal.nextRevision(c).value()).isEqualTo(2L);
        assertThat(journal.currentRevision(c).value()).isEqualTo(2L);
    }

    @Test
    void revisionsAreIndependentPerClass() {
        TransformationJournal journal = new TransformationJournal(new TransformationJournal.Config(8, 32));
        ClassIdentity a = ci("com.example.A");
        ClassIdentity b = ci("com.example.B");

        journal.nextRevision(a);
        journal.nextRevision(a);
        assertThat(journal.nextRevision(a).value()).isEqualTo(3L);
        assertThat(journal.nextRevision(b).value()).isEqualTo(1L);
    }

    @Test
    void sameClassNameDifferentLoaderIsDistinctIdentity() {
        TransformationJournal journal = new TransformationJournal(new TransformationJournal.Config(8, 32));
        ClassIdentity a = new ClassIdentity("com.example.Same", "loader-1");
        ClassIdentity b = new ClassIdentity("com.example.Same", "loader-2");

        assertThat(journal.nextRevision(a).value()).isEqualTo(1L);
        assertThat(journal.nextRevision(b).value()).isEqualTo(1L);
        assertThat(journal.nextRevision(a).value()).isEqualTo(2L);
    }

    @Test
    void lifecycleRecordsAppendInOrder() {
        TransformationJournal journal = new TransformationJournal(new TransformationJournal.Config(8, 32));
        ClassIdentity c = ci("com.example.Foo");
        TransformationRevision r1 = journal.nextRevision(c);
        journal.recordStart(c, r1, "input-hash", 100L);
        journal.recordSuccess(c, r1, "input-hash", "output-hash", 100L, 5L);
        TransformationRevision r2 = journal.nextRevision(c);
        journal.recordFailure(c, r2, "input-hash-2",
                List.of(TransformationDiagnostic.error("RETRANSFORM_FAILED", "boom")), 200L, 7L);

        List<TransformationResult> history = journal.history(c);
        assertThat(history).hasSize(3);
        assertThat(history.get(0).status()).isEqualTo(TransformationStatus.STARTED);
        assertThat(history.get(0).attemptedAtMillis()).isEqualTo(100L);
        assertThat(history.get(1).status()).isEqualTo(TransformationStatus.SUCCEEDED);
        assertThat(history.get(1).outputHash()).isEqualTo("output-hash");
        assertThat(history.get(1).durationMillis()).isEqualTo(5L);
        assertThat(history.get(2).status()).isEqualTo(TransformationStatus.FAILED);
        assertThat(history.get(2).diagnostics()).hasSize(1);
        assertThat(history.get(2).revision().value()).isEqualTo(2L);

        assertThat(journal.recordCount(c)).isEqualTo(3);
        assertThat(journal.recordCount()).isEqualTo(3);
    }

    @Test
    void verificationRecoveryAndSkipRecordDistinctStatuses() {
        TransformationJournal journal = new TransformationJournal(new TransformationJournal.Config(8, 32));
        ClassIdentity c = ci("com.example.Foo");
        TransformationRevision r = journal.nextRevision(c);

        journal.recordVerification(c, r, true, List.of(), 10L, 1L);
        journal.recordVerification(c, r, false,
                List.of(TransformationDiagnostic.error("VERIFY", "mismatch")), 20L, 1L);
        journal.recordRecovery(c, r, "reset transformer", 30L);
        journal.recordSkip(c, r, "no rules", 40L);

        List<TransformationResult> history = journal.history(c);
        assertThat(history).extracting(TransformationResult::status)
                .containsExactly(TransformationStatus.VERIFIED, TransformationStatus.FAILED,
                        TransformationStatus.RECOVERED, TransformationStatus.SKIPPED);
        assertThat(history.get(2).diagnostics().get(0).code()).isEqualTo("RECOVERY");
        assertThat(history.get(3).diagnostics().get(0).message()).isEqualTo("no rules");
    }

    @Test
    void perClassHistoryIsBoundedAndFifo() {
        TransformationJournal journal = new TransformationJournal(new TransformationJournal.Config(3, 64));
        ClassIdentity c = ci("com.example.Foo");
        for (int i = 1; i <= 5; i++) {
            TransformationRevision r = journal.nextRevision(c);
            journal.recordStart(c, r, "h" + i, 100L + i);
        }

        List<TransformationResult> history = journal.history(c);
        assertThat(history).hasSize(3);
        assertThat(history.get(0).revision().value()).isEqualTo(3L); // oldest two dropped
        assertThat(history.get(2).revision().value()).isEqualTo(5L);
    }

    @Test
    void globalHistoryIsBoundedAcrossClasses() {
        TransformationJournal journal = new TransformationJournal(new TransformationJournal.Config(2, 4));
        ClassIdentity a = ci("com.example.A");
        ClassIdentity b = ci("com.example.B");
        ClassIdentity c = ci("com.example.C");

        journal.recordStart(a, journal.nextRevision(a), "a1", 1L);
        journal.recordStart(b, journal.nextRevision(b), "b1", 2L);
        journal.recordStart(c, journal.nextRevision(c), "c1", 3L);
        journal.recordStart(a, journal.nextRevision(a), "a2", 4L);
        journal.recordStart(b, journal.nextRevision(b), "b2", 5L);

        assertThat(journal.recordCount()).isEqualTo(4); // globalLimit
        List<TransformationResult> global = journal.history();
        assertThat(global.get(0).attemptedAtMillis()).isEqualTo(2L); // a1 dropped (oldest)
        assertThat(global.get(3).attemptedAtMillis()).isEqualTo(5L);
    }

    @Test
    void historyReturnsDefensiveImmutableCopy() {
        TransformationJournal journal = new TransformationJournal(new TransformationJournal.Config(8, 32));
        ClassIdentity c = ci("com.example.Foo");
        journal.recordStart(c, journal.nextRevision(c), "h", 1L);

        List<TransformationResult> history = journal.history(c);
        assertThatThrownBy(() -> history.add(new TransformationResult(c, TransformationRevision.of(99),
                TransformationStatus.STARTED, null, null, List.of(), 1L, 0L)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void diagnosticsAreDefensivelyCopied() {
        TransformationJournal journal = new TransformationJournal(new TransformationJournal.Config(8, 32));
        ClassIdentity c = ci("com.example.Foo");
        TransformationRevision r = journal.nextRevision(c);
        List<TransformationDiagnostic> diagnostics = new ArrayList<>();
        diagnostics.add(TransformationDiagnostic.error("E1", "one"));
        journal.recordFailure(c, r, "h", diagnostics, 1L, 1L);

        diagnostics.add(TransformationDiagnostic.error("E2", "two")); // mutate source list

        List<TransformationResult> history = journal.history(c);
        assertThat(history.get(0).diagnostics()).hasSize(1);
        assertThatThrownBy(() -> history.get(0).diagnostics().add(TransformationDiagnostic.error("E3", "three")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void clearClassRemovesOnlyThatClassHistoryButKeepsRevisions() {
        TransformationJournal journal = new TransformationJournal(new TransformationJournal.Config(8, 32));
        ClassIdentity a = ci("com.example.A");
        ClassIdentity b = ci("com.example.B");
        TransformationRevision ra = journal.nextRevision(a);
        journal.recordStart(a, ra, "h", 1L);
        journal.recordStart(b, journal.nextRevision(b), "h", 2L);

        journal.clear(a);
        assertThat(journal.history(a)).isEmpty();
        assertThat(journal.recordCount(a)).isZero();
        assertThat(journal.recordCount()).isEqualTo(1); // only b remains globally
        assertThat(journal.nextRevision(a).value()).isEqualTo(2L); // counter preserved
    }

    @Test
    void clearClassRemovesAllGlobalRecordsWhenPerClassHistoryAlreadyEvicted() {
        // perClassLimit < globalLimit: older A records are evicted from the per-class deque but
        // still live in the global deque. clear(A) must purge every A record from global, not
        // merely the ones still held in the per-class view.
        TransformationJournal journal = new TransformationJournal(new TransformationJournal.Config(2, 64));
        ClassIdentity a = ci("com.example.A");
        ClassIdentity b = ci("com.example.B");

        for (int i = 1; i <= 5; i++) {
            journal.recordStart(a, journal.nextRevision(a), "a" + i, 100L + i);
        }
        journal.recordStart(b, journal.nextRevision(b), "b1", 200L);

        // sanity: per-class A keeps only the last two, global keeps all six
        assertThat(journal.history(a)).hasSize(2);
        assertThat(journal.history(a)).extracting(TransformationResult::attemptedAtMillis)
                .containsExactly(104L, 105L);
        assertThat(journal.recordCount()).isEqualTo(6);

        journal.clear(a);

        assertThat(journal.history(a)).isEmpty();
        assertThat(journal.recordCount(a)).isZero();
        // every A record must be gone from global; only B remains
        assertThat(journal.recordCount()).isEqualTo(1);
        assertThat(journal.history()).extracting(TransformationResult::attemptedAtMillis)
                .containsExactly(200L);
        assertThat(journal.history()).extracting(TransformationResult::classIdentity)
                .allSatisfy(identity -> assertThat(identity).isEqualTo(b));
        // revision counter preserved (monotonic), unaffected by the history clear
        assertThat(journal.nextRevision(a).value()).isEqualTo(6L);
        assertThat(journal.nextRevision(b).value()).isEqualTo(2L);
    }

    @Test
    void clearAllDropsHistoryButKeepsRevisions() {
        TransformationJournal journal = new TransformationJournal(new TransformationJournal.Config(8, 32));
        ClassIdentity a = ci("com.example.A");
        journal.recordStart(a, journal.nextRevision(a), "h", 1L);

        journal.clear();
        assertThat(journal.recordCount()).isZero();
        assertThat(journal.nextRevision(a).value()).isEqualTo(2L);
    }

    @Test
    void concurrentNextRevisionProducesContiguousMonotonicSequence() throws Exception {
        TransformationJournal journal = new TransformationJournal(new TransformationJournal.Config(64, 1024));
        ClassIdentity c = ci("com.example.Foo");
        int threads = 16;
        int perThread = 100;
        Set<Long> seen = java.util.concurrent.ConcurrentHashMap.newKeySet();
        CountDownLatch latch = new CountDownLatch(1);
        var exec = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                futures.add(exec.submit(() -> {
                    latch.await();
                    for (int i = 0; i < perThread; i++) {
                        seen.add(journal.nextRevision(c).value());
                    }
                    return null;
                }));
            }
            latch.countDown();
            for (Future<?> f : futures) {
                f.get();
            }
        } finally {
            exec.shutdownNow();
        }

        int expected = threads * perThread;
        assertThat(seen).hasSize(expected); // no duplicate revisions
        assertThat(journal.currentRevision(c).value()).isEqualTo(expected);
        for (long v = 1; v <= expected; v++) {
            assertThat(seen).contains(v); // contiguous 1..expected
        }
    }

    @Test
    void configRejectsInvalidLimits() {
        assertThatThrownBy(() -> new TransformationJournal.Config(0, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TransformationJournal.Config(10, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TransformationJournal.Config(10, 5))
                .isInstanceOf(IllegalArgumentException.class); // global < perClass
    }
}
