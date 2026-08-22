package com.example.kairo.agent.core.bytecode;

import com.example.kairo.api.bytecode.BytecodeSnapshotKind;
import com.example.kairo.api.bytecode.BytecodeSnapshotMetadata;
import com.example.kairo.api.bytecode.ClassIdentity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/**
 * Bounded in-agent store of bytecode snapshots, indexed by
 * {@link BytecodeSnapshotKey} (identity + revision + kind).
 *
 * <p>Properties:
 * <ul>
 *   <li>bounded by both entry count and total bytes;</li>
 *   <li>TTL-based expiry, cleanable opportunistically on access and via
 *       {@link #evictExpired()};</li>
 *   <li>deterministic eviction: when over capacity the least-recently-accessed
 *       entry is removed, ties broken by {@link BytecodeSnapshotKey#compareTo};
 *       </li>
 *   <li>a successful {@link #store} keeps the entry just written readable;
 *       capacity eviction in that store call may only remove older entries;</li>
 *   <li>defensive byte copies on both store and read;</li>
 *   <li>thread-safe: reads are lock-free, maintenance (store/evict/clear) is
 *       guarded by a single lock;</li>
 *   <li>keys and values hold only value types; no {@code Class} or
 *       {@code ClassLoader} is ever strongly referenced.</li>
 * </ul>
 *
 * <p>Background TTL sweeping is not run by this class; callers (wired in a
 * later slice) invoke {@link #evictExpired()} periodically. Expiry is also
 * enforced lazily on read access.
 */
public final class BytecodeSnapshotRepository implements AutoCloseable {

    /**
     * @param maxEntries maximum number of entries, must be &gt; 0
     * @param maxBytes   maximum total stored bytes, must be &gt; 0
     * @param ttlMillis  time-to-live in ms; {@code 0} means never expire
     */
    public record Config(long maxEntries, long maxBytes, long ttlMillis) {
        public Config {
            if (maxEntries <= 0) {
                throw new IllegalArgumentException("maxEntries must be > 0: " + maxEntries);
            }
            if (maxBytes <= 0) {
                throw new IllegalArgumentException("maxBytes must be > 0: " + maxBytes);
            }
            if (ttlMillis < 0) {
                throw new IllegalArgumentException("ttlMillis must be >= 0: " + ttlMillis);
            }
        }
    }

    private static final long NEVER_EXPIRES = Long.MAX_VALUE;

    private final Config config;
    private final LongSupplier clock;
    private final ConcurrentHashMap<BytecodeSnapshotKey, Entry> entries = new ConcurrentHashMap<>();
    private final AtomicLong totalBytes = new AtomicLong();
    private final ReentrantLock maintenance = new ReentrantLock();

    public BytecodeSnapshotRepository(Config config) {
        this(config, System::currentTimeMillis);
    }

    BytecodeSnapshotRepository(Config config, LongSupplier clock) {
        this.config = Objects.requireNonNull(config, "config");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Config config() {
        return config;
    }

    /**
     * Store a snapshot. The bytes are defensively copied; the caller's array
     * and the returned arrays are independent of the stored copy.
     *
     * @throws IllegalArgumentException if bytes are empty, metadata does not
     *                                  match the key, {@code sizeBytes} differs
     *                                  from the array length, or a single entry
     *                                  exceeds {@code maxBytes}
     */
    public void store(BytecodeSnapshotKey key, byte[] bytes, BytecodeSnapshotMetadata metadata) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(bytes, "bytes");
        Objects.requireNonNull(metadata, "metadata");
        if (bytes.length == 0) {
            throw new IllegalArgumentException("bytes must not be empty");
        }
        if (!key.classIdentity().equals(metadata.classIdentity())
                || !key.revision().equals(metadata.revision())
                || key.kind() != metadata.kind()) {
            throw new IllegalArgumentException("metadata must match key (classIdentity/revision/kind)");
        }
        if (metadata.sizeBytes() != bytes.length) {
            throw new IllegalArgumentException("metadata.sizeBytes must equal bytes.length");
        }
        if (bytes.length > config.maxBytes()) {
            throw new IllegalArgumentException(
                    "snapshot size " + bytes.length + " exceeds repository maxBytes " + config.maxBytes());
        }

        long now = clock.getAsLong();
        long expiresAt = config.ttlMillis() > 0 ? saturatedAdd(now, config.ttlMillis()) : NEVER_EXPIRES;
        byte[] stored = bytes.clone();
        Entry entry = new Entry(metadata, stored, expiresAt, now);

        maintenance.lock();
        try {
            Entry previous = entries.put(key, entry);
            if (previous != null) {
                totalBytes.addAndGet(-previous.bytes.length);
            }
            totalBytes.addAndGet(stored.length);
            evictIfOverCapacity(key);
        } finally {
            maintenance.unlock();
        }
    }

    public Optional<byte[]> bytes(BytecodeSnapshotKey key) {
        Entry entry = entries.get(Objects.requireNonNull(key, "key"));
        if (entry == null) {
            return Optional.empty();
        }
        long now = clock.getAsLong();
        if (isExpired(entry, now)) {
            removeIfPresent(key, entry);
            return Optional.empty();
        }
        entry.lastAccessMillis.set(now);
        return Optional.of(entry.bytes.clone());
    }

    public Optional<BytecodeSnapshotMetadata> metadata(BytecodeSnapshotKey key) {
        Entry entry = entries.get(Objects.requireNonNull(key, "key"));
        if (entry == null) {
            return Optional.empty();
        }
        long now = clock.getAsLong();
        if (isExpired(entry, now)) {
            removeIfPresent(key, entry);
            return Optional.empty();
        }
        entry.lastAccessMillis.set(now);
        return Optional.of(entry.metadata);
    }

    public boolean contains(BytecodeSnapshotKey key) {
        Entry entry = entries.get(Objects.requireNonNull(key, "key"));
        if (entry == null) {
            return false;
        }
        if (isExpired(entry, clock.getAsLong())) {
            removeIfPresent(key, entry);
            return false;
        }
        return true;
    }

    /**
     * All non-expired snapshot metadata for a class, sorted by revision then
     * kind. Expired entries are skipped but not removed here.
     */
    public List<BytecodeSnapshotMetadata> metadataFor(ClassIdentity classIdentity) {
        Objects.requireNonNull(classIdentity, "classIdentity");
        long now = clock.getAsLong();
        List<BytecodeSnapshotMetadata> result = new ArrayList<>();
        for (var e : entries.entrySet()) {
            if (!classIdentity.equals(e.getKey().classIdentity())) {
                continue;
            }
            Entry entry = e.getValue();
            if (isExpired(entry, now)) {
                continue;
            }
            result.add(entry.metadata);
        }
        result.sort(Comparator
                .comparing(BytecodeSnapshotMetadata::revision)
                .thenComparing(BytecodeSnapshotMetadata::kind));
        return List.copyOf(result);
    }

    /**
     * Remove all expired entries. Returns the number removed.
     */
    public int evictExpired() {
        long now = clock.getAsLong();
        int removed = 0;
        maintenance.lock();
        try {
            var it = entries.entrySet().iterator();
            while (it.hasNext()) {
                var e = it.next();
                if (isExpired(e.getValue(), now)) {
                    totalBytes.addAndGet(-e.getValue().bytes.length);
                    it.remove();
                    removed++;
                }
            }
        } finally {
            maintenance.unlock();
        }
        return removed;
    }

    public int size() {
        return entries.size();
    }

    public long totalBytes() {
        return totalBytes.get();
    }

    public void clear() {
        maintenance.lock();
        try {
            entries.clear();
            totalBytes.set(0L);
        } finally {
            maintenance.unlock();
        }
    }

    /**
     * V1.5 &sect;3.2: remove every snapshot whose {@link ClassIdentity} carries the
     * collected loader's id. Called by the {@code ClassLoaderRepository} cleaner
     * after the ReferenceQueue observes the loader has been garbage-collected, so
     * residual snapshots do not outlive the loader that produced them.
     *
     * @return the number of entries removed
     */
    public int clearForLoader(String classLoaderId) {
        if (classLoaderId == null) {
            return 0;
        }
        int removed = 0;
        maintenance.lock();
        try {
            var it = entries.entrySet().iterator();
            while (it.hasNext()) {
                var entry = it.next();
                if (classLoaderId.equals(entry.getKey().classIdentity().classLoaderId())) {
                    Entry removedEntry = entry.getValue();
                    it.remove();
                    totalBytes.addAndGet(-removedEntry.bytes.length);
                    removed++;
                }
            }
        } finally {
            maintenance.unlock();
        }
        return removed;
    }

    @Override
    public void close() {
        clear();
    }

    private void evictIfOverCapacity(BytecodeSnapshotKey protectedKey) {
        while (entries.size() > config.maxEntries() || totalBytes.get() > config.maxBytes()) {
            if (entries.isEmpty()) {
                break;
            }
            BytecodeSnapshotKey victimKey = null;
            Entry victim = null;
            for (var e : entries.entrySet()) {
                // A successful store must make the value it just accepted immediately readable.
                // Without this guard, a coarse or fixed clock can give every entry the same
                // access timestamp; the key tie-break may then choose the newly inserted value
                // itself. The caller observes a successful store followed by an impossible
                // miss (the P7D warm-up exposed this at a high ClassLoader count). A single
                // entry can never be larger than maxBytes (validated above), so older entries
                // can always be removed to satisfy both bounds while protecting this key.
                if (e.getKey().equals(protectedKey)) {
                    continue;
                }
                Entry candidate = e.getValue();
                if (victim == null) {
                    victimKey = e.getKey();
                    victim = candidate;
                    continue;
                }
                int byAccess = Long.compare(victim.lastAccessMillis.get(), candidate.lastAccessMillis.get());
                if (byAccess > 0 || (byAccess == 0 && e.getKey().compareTo(victimKey) < 0)) {
                    victimKey = e.getKey();
                    victim = candidate;
                }
            }
            if (victimKey == null) {
                break;
            }
            Entry removed = entries.remove(victimKey);
            if (removed != null) {
                totalBytes.addAndGet(-removed.bytes.length);
            }
        }
    }

    private void removeIfPresent(BytecodeSnapshotKey key, Entry expected) {
        maintenance.lock();
        try {
            Entry current = entries.get(key);
            if (current == expected && isExpired(current, clock.getAsLong())) {
                entries.remove(key);
                totalBytes.addAndGet(-current.bytes.length);
            }
        } finally {
            maintenance.unlock();
        }
    }

    private static boolean isExpired(Entry entry, long now) {
        return entry.expiresAtMillis != NEVER_EXPIRES && now >= entry.expiresAtMillis;
    }

    /**
     * Add two longs, clamping to {@link Long#MAX_VALUE} on overflow instead of wrapping to a
     * negative number. A negative {@code expiresAt} would make {@link #isExpired} return true on
     * the next access, expiring a freshly stored snapshot immediately; saturating keeps the entry
     * alive (it collapses onto {@link #NEVER_EXPIRES}) until the clock genuinely reaches the max.
     */
    private static long saturatedAdd(long a, long b) {
        try {
            return Math.addExact(a, b);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    private static final class Entry {
        private final BytecodeSnapshotMetadata metadata;
        private final byte[] bytes;
        private final long expiresAtMillis;
        private final AtomicLong lastAccessMillis;

        Entry(BytecodeSnapshotMetadata metadata, byte[] bytes, long expiresAtMillis, long lastAccessMillis) {
            this.metadata = metadata;
            this.bytes = bytes;
            this.expiresAtMillis = expiresAtMillis;
            this.lastAccessMillis = new AtomicLong(lastAccessMillis);
        }
    }
}
