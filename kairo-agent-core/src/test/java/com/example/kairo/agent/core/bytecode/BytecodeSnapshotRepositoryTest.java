package com.example.kairo.agent.core.bytecode;

import com.example.kairo.api.bytecode.BytecodeSnapshotKind;
import com.example.kairo.api.bytecode.BytecodeSnapshotMetadata;
import com.example.kairo.api.bytecode.ClassIdentity;
import com.example.kairo.api.bytecode.TransformationRevision;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BytecodeSnapshotRepositoryTest {

    private static ClassIdentity ci(String name) {
        return new ClassIdentity(name, "loader-1");
    }

    private static BytecodeSnapshotKey key(ClassIdentity ci, long rev, BytecodeSnapshotKind kind) {
        return new BytecodeSnapshotKey(ci, TransformationRevision.of(rev), kind);
    }

    private static BytecodeSnapshotMetadata meta(ClassIdentity ci, long rev, BytecodeSnapshotKind kind, byte[] bytes) {
        return new BytecodeSnapshotMetadata(ci, TransformationRevision.of(rev), kind,
                "hash-" + rev + "-" + kind, bytes.length, 0L, "test", null);
    }

    @Test
    void storesAndRetrievesBytesAndMetadata() {
        BytecodeSnapshotRepository repo = new BytecodeSnapshotRepository(
                new BytecodeSnapshotRepository.Config(10, 1024, 0));
        ClassIdentity c = ci("com.example.Foo");
        byte[] bytes = {1, 2, 3, 4};

        repo.store(key(c, 1, BytecodeSnapshotKind.INPUT), bytes, meta(c, 1, BytecodeSnapshotKind.INPUT, bytes));

        Optional<byte[]> got = repo.bytes(key(c, 1, BytecodeSnapshotKind.INPUT));
        assertThat(got).isPresent();
        assertThat(got.get()).containsExactly(1, 2, 3, 4);

        assertThat(repo.metadata(key(c, 1, BytecodeSnapshotKind.INPUT))).isPresent()
                .get().extracting(BytecodeSnapshotMetadata::hash).isEqualTo("hash-1-INPUT");
        assertThat(repo.size()).isEqualTo(1);
        assertThat(repo.totalBytes()).isEqualTo(4);
        assertThat(repo.contains(key(c, 1, BytecodeSnapshotKind.INPUT))).isTrue();
        assertThat(repo.contains(key(c, 9, BytecodeSnapshotKind.INPUT))).isFalse();
    }

    @Test
    void defensiveCopiesPreventCallerMutation() {
        BytecodeSnapshotRepository repo = new BytecodeSnapshotRepository(
                new BytecodeSnapshotRepository.Config(10, 1024, 0));
        ClassIdentity c = ci("com.example.Foo");
        byte[] bytes = {1, 2, 3};
        repo.store(key(c, 1, BytecodeSnapshotKind.INPUT), bytes, meta(c, 1, BytecodeSnapshotKind.INPUT, bytes));

        bytes[0] = 99; // mutate input after store
        byte[] firstRead = repo.bytes(key(c, 1, BytecodeSnapshotKind.INPUT)).orElseThrow();
        assertThat(firstRead).containsExactly(1, 2, 3);

        firstRead[0] = 99; // mutate returned array
        byte[] secondRead = repo.bytes(key(c, 1, BytecodeSnapshotKind.INPUT)).orElseThrow();
        assertThat(secondRead).containsExactly(1, 2, 3);
    }

    @Test
    void rejectsEmptyMismatchedAndOversizedEntries() {
        BytecodeSnapshotRepository repo = new BytecodeSnapshotRepository(
                new BytecodeSnapshotRepository.Config(10, 1024, 0));
        ClassIdentity c = ci("com.example.Foo");
        byte[] bytes = {1, 2};

        assertThatThrownBy(() -> repo.store(key(c, 1, BytecodeSnapshotKind.INPUT), new byte[0],
                meta(c, 1, BytecodeSnapshotKind.INPUT, new byte[0])))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repo.store(key(c, 1, BytecodeSnapshotKind.INPUT), bytes,
                meta(c, 1, BytecodeSnapshotKind.APPLIED, bytes)))
                .isInstanceOf(IllegalArgumentException.class);
        BytecodeSnapshotMetadata badSize = new BytecodeSnapshotMetadata(c, TransformationRevision.of(1),
                BytecodeSnapshotKind.INPUT, "h", 999, 0L, "test", null);
        assertThatThrownBy(() -> repo.store(key(c, 1, BytecodeSnapshotKind.INPUT), bytes, badSize))
                .isInstanceOf(IllegalArgumentException.class);

        BytecodeSnapshotRepository small = new BytecodeSnapshotRepository(
                new BytecodeSnapshotRepository.Config(10, 4, 0));
        byte[] tooBig = {1, 2, 3, 4, 5};
        assertThatThrownBy(() -> small.store(key(c, 1, BytecodeSnapshotKind.INPUT), tooBig,
                meta(c, 1, BytecodeSnapshotKind.INPUT, tooBig)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds repository maxBytes");
    }

    @Test
    void configRejectsInvalidValues() {
        assertThatThrownBy(() -> new BytecodeSnapshotRepository.Config(0, 1024, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BytecodeSnapshotRepository.Config(10, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BytecodeSnapshotRepository.Config(10, 1024, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void evictsByEntryCountUsingLruAccess() {
        AtomicLong clock = new AtomicLong(0L);
        BytecodeSnapshotRepository repo = new BytecodeSnapshotRepository(
                new BytecodeSnapshotRepository.Config(2, 1024, 0), clock::get);
        ClassIdentity a = ci("com.example.A");
        ClassIdentity b = ci("com.example.B");
        ClassIdentity c = ci("com.example.C");

        clock.set(100L);
        repo.store(key(a, 1, BytecodeSnapshotKind.INPUT), new byte[]{1}, meta(a, 1, BytecodeSnapshotKind.INPUT, new byte[]{1}));
        clock.set(200L);
        repo.store(key(b, 1, BytecodeSnapshotKind.INPUT), new byte[]{2}, meta(b, 1, BytecodeSnapshotKind.INPUT, new byte[]{2}));
        clock.set(300L);
        repo.bytes(key(a, 1, BytecodeSnapshotKind.INPUT)); // touch A -> B is least-recently-used
        clock.set(400L);
        repo.store(key(c, 1, BytecodeSnapshotKind.INPUT), new byte[]{3}, meta(c, 1, BytecodeSnapshotKind.INPUT, new byte[]{3}));

        assertThat(repo.contains(key(b, 1, BytecodeSnapshotKind.INPUT))).isFalse();
        assertThat(repo.contains(key(a, 1, BytecodeSnapshotKind.INPUT))).isTrue();
        assertThat(repo.contains(key(c, 1, BytecodeSnapshotKind.INPUT))).isTrue();
        assertThat(repo.size()).isEqualTo(2);
    }

    @Test
    void evictsByTotalBytes() {
        AtomicLong clock = new AtomicLong(0L);
        BytecodeSnapshotRepository repo = new BytecodeSnapshotRepository(
                new BytecodeSnapshotRepository.Config(10, 3, 0), clock::get);
        ClassIdentity a = ci("com.example.A");

        clock.set(100L);
        repo.store(key(a, 1, BytecodeSnapshotKind.INPUT), new byte[]{1}, meta(a, 1, BytecodeSnapshotKind.INPUT, new byte[]{1}));
        clock.set(200L);
        repo.store(key(a, 2, BytecodeSnapshotKind.INPUT), new byte[]{1}, meta(a, 2, BytecodeSnapshotKind.INPUT, new byte[]{1}));
        clock.set(300L);
        repo.store(key(a, 3, BytecodeSnapshotKind.INPUT), new byte[]{1}, meta(a, 3, BytecodeSnapshotKind.INPUT, new byte[]{1}));
        assertThat(repo.size()).isEqualTo(3);
        assertThat(repo.totalBytes()).isEqualTo(3);

        clock.set(400L);
        repo.store(key(a, 4, BytecodeSnapshotKind.INPUT), new byte[]{1}, meta(a, 4, BytecodeSnapshotKind.INPUT, new byte[]{1}));
        assertThat(repo.totalBytes()).isLessThanOrEqualTo(3);
        assertThat(repo.size()).isLessThanOrEqualTo(3);
        assertThat(repo.contains(key(a, 1, BytecodeSnapshotKind.INPUT))).isFalse(); // oldest evicted
        assertThat(repo.contains(key(a, 4, BytecodeSnapshotKind.INPUT))).isTrue();
    }

    @Test
    void ttlExpiryRemovesEntriesLazilyAndExplicitly() {
        AtomicLong clock = new AtomicLong(1_000_000L);
        BytecodeSnapshotRepository repo = new BytecodeSnapshotRepository(
                new BytecodeSnapshotRepository.Config(10, 1024, 1000L), clock::get);
        ClassIdentity a = ci("com.example.A");
        repo.store(key(a, 1, BytecodeSnapshotKind.INPUT), new byte[]{1}, meta(a, 1, BytecodeSnapshotKind.INPUT, new byte[]{1}));

        clock.set(1_000_000L + 999L);
        assertThat(repo.contains(key(a, 1, BytecodeSnapshotKind.INPUT))).isTrue();

        clock.set(1_000_000L + 1000L);
        assertThat(repo.contains(key(a, 1, BytecodeSnapshotKind.INPUT))).isFalse(); // lazy removal
        assertThat(repo.size()).isZero();
        assertThat(repo.totalBytes()).isZero();
    }

    @Test
    void evictExpiredDropsOnlyExpired() {
        AtomicLong clock = new AtomicLong(0L);
        BytecodeSnapshotRepository repo = new BytecodeSnapshotRepository(
                new BytecodeSnapshotRepository.Config(10, 1024, 100L), clock::get);
        ClassIdentity a = ci("com.example.A");
        clock.set(0L);
        repo.store(key(a, 1, BytecodeSnapshotKind.INPUT), new byte[]{1}, meta(a, 1, BytecodeSnapshotKind.INPUT, new byte[]{1}));
        clock.set(50L);
        repo.store(key(a, 2, BytecodeSnapshotKind.INPUT), new byte[]{1}, meta(a, 2, BytecodeSnapshotKind.INPUT, new byte[]{1}));

        clock.set(100L); // first expired, second not
        int removed = repo.evictExpired();
        assertThat(removed).isEqualTo(1);
        assertThat(repo.contains(key(a, 1, BytecodeSnapshotKind.INPUT))).isFalse();
        assertThat(repo.contains(key(a, 2, BytecodeSnapshotKind.INPUT))).isTrue();
    }

    @Test
    void metadataForClassReturnsSortedMatchingOnly() {
        BytecodeSnapshotRepository repo = new BytecodeSnapshotRepository(
                new BytecodeSnapshotRepository.Config(10, 1024, 0));
        ClassIdentity a = ci("com.example.A");
        ClassIdentity b = ci("com.example.B");
        repo.store(key(a, 1, BytecodeSnapshotKind.APPLIED), new byte[]{1}, meta(a, 1, BytecodeSnapshotKind.APPLIED, new byte[]{1}));
        repo.store(key(a, 1, BytecodeSnapshotKind.INPUT), new byte[]{1}, meta(a, 1, BytecodeSnapshotKind.INPUT, new byte[]{1}));
        repo.store(key(a, 2, BytecodeSnapshotKind.INPUT), new byte[]{1}, meta(a, 2, BytecodeSnapshotKind.INPUT, new byte[]{1}));
        repo.store(key(b, 1, BytecodeSnapshotKind.INPUT), new byte[]{1}, meta(b, 1, BytecodeSnapshotKind.INPUT, new byte[]{1}));

        var list = repo.metadataFor(a);
        assertThat(list).hasSize(3);
        assertThat(list.get(0).revision().value()).isEqualTo(1L);
        assertThat(list.get(0).kind()).isEqualTo(BytecodeSnapshotKind.INPUT);
        assertThat(list.get(1).revision().value()).isEqualTo(1L);
        assertThat(list.get(1).kind()).isEqualTo(BytecodeSnapshotKind.APPLIED);
        assertThat(list.get(2).revision().value()).isEqualTo(2L);
    }

    @Test
    void ttlExpirySaturatesAtLongMaxValueInsteadOfOverflowing() {
        // now + ttl overflows a signed long; without saturation expiresAt would wrap negative and
        // the freshly stored entry would be treated as already expired on the next access.
        AtomicLong clock = new AtomicLong(Long.MAX_VALUE - 10L);
        BytecodeSnapshotRepository repo = new BytecodeSnapshotRepository(
                new BytecodeSnapshotRepository.Config(10, 1024, 1000L), clock::get);
        ClassIdentity a = ci("com.example.A");

        repo.store(key(a, 1, BytecodeSnapshotKind.INPUT), new byte[]{1},
                meta(a, 1, BytecodeSnapshotKind.INPUT, new byte[]{1}));

        assertThat(repo.contains(key(a, 1, BytecodeSnapshotKind.INPUT))).isTrue();
        assertThat(repo.size()).isEqualTo(1);
        assertThat(repo.bytes(key(a, 1, BytecodeSnapshotKind.INPUT))).isPresent()
                .get().satisfies(b -> assertThat(b).containsExactly(1));

        // a wrapping expiresAt would already be expired; the saturated entry survives an
        // explicit sweep as well as lazy access checks.
        assertThat(repo.evictExpired()).isZero();
        assertThat(repo.size()).isEqualTo(1);
    }

    @Test
    void clearDropsEverything() {
        BytecodeSnapshotRepository repo = new BytecodeSnapshotRepository(
                new BytecodeSnapshotRepository.Config(10, 1024, 0));
        ClassIdentity a = ci("com.example.A");
        repo.store(key(a, 1, BytecodeSnapshotKind.INPUT), new byte[]{1}, meta(a, 1, BytecodeSnapshotKind.INPUT, new byte[]{1}));
        repo.clear();
        assertThat(repo.size()).isZero();
        assertThat(repo.totalBytes()).isZero();
        assertThat(repo.contains(key(a, 1, BytecodeSnapshotKind.INPUT))).isFalse();
    }

    @Test
    void concurrentStoresRespectCapacityBounds() throws Exception {
        BytecodeSnapshotRepository repo = new BytecodeSnapshotRepository(
                new BytecodeSnapshotRepository.Config(50, 256, 0));
        int threads = 8;
        int perThread = 50;
        var exec = java.util.concurrent.Executors.newFixedThreadPool(threads);
        try {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int t = 0; t < threads; t++) {
                final int ti = t;
                futures.add(exec.submit(() -> {
                    for (int i = 0; i < perThread; i++) {
                        ClassIdentity c = ci("com.example.T" + ti + "C" + i);
                        byte[] bytes = new byte[4];
                        repo.store(key(c, 1, BytecodeSnapshotKind.INPUT), bytes,
                                meta(c, 1, BytecodeSnapshotKind.INPUT, bytes));
                    }
                }));
            }
            for (var f : futures) {
                f.get();
            }
        } finally {
            exec.shutdownNow();
        }
        assertThat(repo.size()).isLessThanOrEqualTo(50);
        assertThat(repo.totalBytes()).isLessThanOrEqualTo(256);
    }
}
