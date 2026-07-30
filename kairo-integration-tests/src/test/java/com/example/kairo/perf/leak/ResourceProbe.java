package com.example.kairo.perf.leak;

import com.example.kairo.agent.core.AgentRuntime;
import com.example.kairo.core.ClassLoaderIdentity;
import com.sun.management.UnixOperatingSystemMXBean;

import java.lang.management.ClassLoadingMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.ThreadMXBean;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The M2-C observation engine (&sect;9.3). Owns three responsibilities:
 *
 * <ol>
 *   <li><b>ClassLoader weak-reference tracking</b>: every unloadable ClassLoader the
 *       harness explicitly creates - every business {@code URLClassLoader} <em>and</em>
 *       every real {@code KairoGroovyClassLoader} the compiled rules use - is registered
 *       with a {@link WeakReference} enqueued on a shared {@link ReferenceQueue}, tagged
 *       with a {@link LoaderKind} (business vs Groovy) and a {@link LoaderPhase}
 *       (warm-up vs measured). After a bounded GC sequence the probe drains the queue and
 *       counts, per bucket, how many were actually collected vs. how many remain live
 *       (residual = still strongly reachable somewhere). This is the primary leak signal:
 *       a discarded loader still pinned (e.g. by an invoked Groovy rule) shows up as a
 *       non-collected residual in its bucket.</li>
 *   <li><b>Bounded GC</b>: a deterministic, bounded sequence of
 *       {@code System.gc()} + {@code MemoryMXBean.gc()} passes interleaved with short
 *       reference-queue drains and tiny settles. This is NOT a sleep-only check: every
 *       pass performs a real full GC and drains the queue, and the loop exits early once
 *       every tracked loader is collected or the attempt budget is exhausted.</li>
 *   <li><b>Resource observation</b>: heap used, metaspace used, live thread count, open
 *       file-descriptor count, loaded-class count, the agent's bounded cache sizes
 *       (rule registry, bytecode snapshot, transformation journal, instrumentation
 *       registry) read from the <em>real</em> runtime, and the real Groovy compile-cache
 *       / generation diagnostics, captured per window for trend and budget evaluation.</li>
 * </ol>
 *
 * <p><b>Never fabricate.</b> {@link #observe(String, boolean, AgentRuntime)} requires a
 * non-null runtime - including the post-close window, which is measured against the real
 * <em>closed</em> {@code AgentRuntime} so the cleared/retained repository counts are
 * genuine, never a null-synthesized zero. File-descriptor and metaspace observation
 * degrade gracefully: if the JVM does not expose {@link UnixOperatingSystemMXBean} or a
 * Metaspace memory pool, the probe records {@code -1} and the budget checker reports the
 * gate as unsupported rather than fabricating a value (&sect;9.3: "文件描述符（平台支持时）").
 * Groovy diagnostics are fail-closed: if the production layout cannot be reflected, the
 * measurement throws and the harness fails rather than recording a fabricated zero.
 */
public final class ResourceProbe {

    private static final MemoryMXBean MEMORY_MX = ManagementFactory.getMemoryMXBean();
    private static final ThreadMXBean THREAD_MX = ManagementFactory.getThreadMXBean();
    private static final ClassLoadingMXBean CLASSLOAD_MX = ManagementFactory.getClassLoadingMXBean();
    private static final MemoryPoolMXBean METASPACE_POOL = findMetaspacePool();
    private static final UnixOperatingSystemMXBean UNIX_OS = findUnixOs();

    /** Kind of unloadable ClassLoader tracked, so business and Groovy counts split honestly. */
    public enum LoaderKind { BUSINESS, GROOVY }

    /** Phase in which the loader was created, so warm-up counts stay separate from measured cycles. */
    public enum LoaderPhase { WARMUP, MEASURED }

    private final ReferenceQueue<ClassLoader> queue = new ReferenceQueue<>();
    private final List<TrackedLoader> tracked = new ArrayList<>();
    private final AtomicInteger totalRegistered = new AtomicInteger();

    /**
     * Run-scoped, monotonic high-water of {@code classesInGeneration} across <em>every</em>
     * successful {@code GroovyCompilerDiagnostics.measure} call in the run (warm-up + measured
     * cycles + observations). Survives a bounded GC and {@code compiler.close()} - both of which
     * clear the weakly-held generation holders, collapsing the point-in-time
     * {@code maxClassesInGeneration} to 0 - so the generation-class budget gate observes real
     * compilation instead of a fabricated zero (&sect;9.3). Single-threaded by harness contract.
     */
    private int generationHighWater = 0;

    /**
     * Register an unloadable ClassLoader for weak-reference survival tracking, de-duplicated
     * by identity (the same Groovy loader discovered across cycles is registered once). The
     * probe holds only a {@link WeakReference}; it never retains a strong reference, so a
     * loader that is otherwise unreachable becomes reclaimable.
     *
     * @return true if newly registered, false if already tracked (identity de-dup)
     */
    public boolean register(ClassLoader loader, String loaderId, String label,
                            LoaderKind kind, LoaderPhase phase) {
        if (loader == null) {
            return false;
        }
        synchronized (tracked) {
            for (TrackedLoader t : tracked) {
                if (t.get() == loader) {
                    return false; // already tracked by identity
                }
            }
            tracked.add(new TrackedLoader(loader, loaderId, label, kind, phase, queue));
        }
        totalRegistered.incrementAndGet();
        return true;
    }

    /**
     * Discover the real {@code KairoGroovyClassLoader} instances the compiled rules
     * currently use (via fail-closed reflection), and weak-track any not yet registered,
     * de-duplicated by identity, in the given phase. Called after each cycle's invoke so the
     * Groovy loaders are tracked alongside the business loaders.
     */
    public void registerGroovyLoaders(AgentRuntime runtime, LoaderPhase phase) {
        GroovyCompilerDiagnostics.GroovyDiagnostics gd = GroovyCompilerDiagnostics.measure(runtime);
        recordGenerationHighWater(gd);
        for (ClassLoader groovyLoader : gd.liveGroovyLoaders()) {
            register(groovyLoader, ClassLoaderIdentity.idOf(groovyLoader),
                    "groovy-" + phase.name().toLowerCase(), LoaderKind.GROOVY, phase);
        }
    }

    /** How many loaders have been registered so far (cumulative, grand total). */
    public int totalRegistered() {
        return totalRegistered.get();
    }

    /**
     * The run-scoped, monotonic high-water of {@code classesInGeneration} across every
     * successful {@code GroovyCompilerDiagnostics.measure} call in the run. Real compilation
     * makes this strictly positive (bounded by the product's
     * {@code MAX_CLASSES_PER_GENERATION}); it is carried in every observation's
     * {@link LeakObservation.GroovyState} so the generation-class budget gate uses the real
     * peak across the run rather than a point-in-time value that GC collapses to zero.
     */
    public int generationHighWater() {
        return generationHighWater;
    }

    /** Fold a successful measurement's point-in-time max into the run-scoped high-water. */
    private void recordGenerationHighWater(GroovyCompilerDiagnostics.GroovyDiagnostics gd) {
        int observed = gd.maxClassesInGeneration();
        if (observed > generationHighWater) {
            generationHighWater = observed;
        }
    }

    /**
     * Run a bounded GC sequence and drain the reference queue. Each pass performs a
     * real full GC and drains newly-enqueued references; the loop exits early once
     * every tracked loader is collected. {@code settleMillis} is a tiny per-pass yield
     * that lets the JVM enqueue cleared references (bounded, not sleep-only).
     *
     * @return the number of loaders newly collected during this sequence
     */
    public int boundedGc(int maxAttempts, long settleMillis) {
        int newlyCollected = 0;
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            System.gc();
            try {
                MEMORY_MX.gc();
            } catch (RuntimeException ignored) {
                // MemoryMXBean.gc() is best-effort on some JVMs; System.gc() already ran.
            }
            if (settleMillis > 0) {
                try {
                    Thread.sleep(settleMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            newlyCollected += drainQueue();
            if (allCollected()) {
                break;
            }
        }
        // Final drain after the last settle so the queue is fully consumed.
        newlyCollected += drainQueue();
        return newlyCollected;
    }

    /**
     * Capture a resource-observation window. When {@code postFullGc} is true a bounded
     * GC is performed first so heap/metaspace reflect reclaimable memory. {@code runtime}
     * must be non-null: the post-close window is measured against the real <em>closed</em>
     * runtime so the repository counts are genuine, never a fabricated zero. Groovy
     * diagnostics are measured from the real compiler (fail-closed).
     */
    public LeakObservation observe(String label, boolean postFullGc, AgentRuntime runtime) {
        if (runtime == null) {
            // The single source of the fabrication bug: a null runtime yielded zero for every
            // repository/registry. Refuse it outright so a closed runtime's real (possibly
            // non-zero) state can never be hidden by a null/default path.
            throw new IllegalArgumentException(
                    "observe() requires a non-null AgentRuntime; a null runtime would fabricate zero evidence");
        }
        if (postFullGc) {
            boundedGc(3, 30L);
        } else {
            drainQueue();
        }
        // Fail-closed: throws GroovyDiagnosticUnavailableException if the layout changed.
        GroovyCompilerDiagnostics.GroovyDiagnostics gd = GroovyCompilerDiagnostics.measure(runtime);
        recordGenerationHighWater(gd);
        LeakObservation.LoaderCounts measuredBusiness = count(LoaderKind.BUSINESS, LoaderPhase.MEASURED);
        LeakObservation.LoaderCounts measuredGroovy = count(LoaderKind.GROOVY, LoaderPhase.MEASURED);
        LeakObservation.LoaderCounts warmupBusiness = count(LoaderKind.BUSINESS, LoaderPhase.WARMUP);
        LeakObservation.LoaderCounts warmupGroovy = count(LoaderKind.GROOVY, LoaderPhase.WARMUP);
        int totalTracked = measuredBusiness.tracked() + measuredGroovy.tracked()
                + warmupBusiness.tracked() + warmupGroovy.tracked();
        int totalLive = measuredBusiness.live() + measuredGroovy.live()
                + warmupBusiness.live() + warmupGroovy.live();
        int totalCollected = measuredBusiness.collected() + measuredGroovy.collected()
                + warmupBusiness.collected() + warmupGroovy.collected();
        long heap = MEMORY_MX.getHeapMemoryUsage().getUsed();
        long metaspace = METASPACE_POOL == null ? -1L : METASPACE_POOL.getUsage().getUsed();
        long fd = UNIX_OS == null ? -1L : UNIX_OS.getOpenFileDescriptorCount();
        return new LeakObservation(
                label, postFullGc, Instant.now(),
                heap, metaspace,
                THREAD_MX.getThreadCount(), fd,
                CLASSLOAD_MX.getLoadedClassCount(),
                runtime.rules().size(),
                runtime.snapshotRepository().size(),
                runtime.transformationJournal().recordCount(),
                runtime.instrumentationRegistry().typeCount(),
                runtime.instrumentationRegistry().methodCount(),
                measuredBusiness, measuredGroovy, warmupBusiness, warmupGroovy,
                new LeakObservation.LoaderCounts(totalTracked, totalLive, totalCollected),
                new LeakObservation.GroovyState(gd.cacheEntries(), gd.generationCount(),
                        gd.maxClassesInGeneration(), gd.liveGroovyLoaders().size(),
                        generationHighWater));
    }

    // -------------------------------------------------------- internal tracking

    private int drainQueue() {
        int n = 0;
        Reference<? extends ClassLoader> ref;
        while ((ref = queue.poll()) != null) {
            if (ref instanceof TrackedLoader t) {
                if (t.collected.compareAndSet(false, true)) {
                    n++;
                }
            }
            ref.clear();
        }
        return n;
    }

    private boolean allCollected() {
        synchronized (tracked) {
            for (TrackedLoader t : tracked) {
                if (!t.collected.get() && t.get() != null) {
                    return false;
                }
            }
        }
        return true;
    }

    private LeakObservation.LoaderCounts count(LoaderKind kind, LoaderPhase phase) {
        int trackedCount = 0;
        int live = 0;
        int collected = 0;
        synchronized (tracked) {
            for (TrackedLoader t : tracked) {
                if (t.kind != kind || t.phase != phase) {
                    continue;
                }
                trackedCount++;
                if (t.collected.get()) {
                    collected++;
                } else if (t.get() != null) {
                    live++;
                }
            }
        }
        return new LeakObservation.LoaderCounts(trackedCount, live, collected);
    }

    private static MemoryPoolMXBean findMetaspacePool() {
        // HotSpot exposes a NON_HEAP pool named "Metaspace"; prefer it. "Compressed
        // Class Space" is reported separately and is not the primary metaspace signal.
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() == MemoryType.NON_HEAP && pool.getName().contains("Metaspace")) {
                return pool;
            }
        }
        return null;
    }

    private static UnixOperatingSystemMXBean findUnixOs() {
        OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
        return os instanceof UnixOperatingSystemMXBean unix ? unix : null;
    }

    /**
     * A weak reference to a tracked ClassLoader that remembers its id, label, kind, phase
     * and collection state, so the reference queue can mark it collected in O(1) and the
     * per-bucket counts can split business/Groovy &times; warm-up/measured.
     */
    private static final class TrackedLoader extends WeakReference<ClassLoader> {
        final String loaderId;
        final String label;
        final LoaderKind kind;
        final LoaderPhase phase;
        final AtomicBoolean collected = new AtomicBoolean();

        TrackedLoader(ClassLoader referent, String loaderId, String label,
                      LoaderKind kind, LoaderPhase phase, ReferenceQueue<ClassLoader> queue) {
            super(referent, queue);
            this.loaderId = loaderId;
            this.label = label;
            this.kind = kind;
            this.phase = phase;
        }
    }
}
