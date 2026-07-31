package com.example.kairo.perf.soak;

import java.time.Instant;

/**
 * One per-minute time-series summary captured by the M2-D soak harness (&sect;9.4: "每分钟采集
 * 时间序列摘要"). Mirrors the M2-C {@code LeakObservation} resource fields (heap, metaspace,
 * threads, file descriptors, loaded classes and the agent's bounded caches) but is a
 * retained-resource read after one best-effort full GC per minute, matching the M2-C stable-window
 * concept while keeping the pause bounded to the sampling cadence. It also carries the soak-specific
 * cumulative counters and per-window breach/drift flags the stability gate evaluates.
 *
 * <p>The {@code -1} sentinel marks an unsupported metric (metaspace / file descriptors on a
 * JVM that does not expose the relevant MXBean); the gate reports such a metric as
 * unsupported rather than fabricating a value, exactly as in the M2-C probe.
 *
 * @param minuteIndex                 1-based index of this summary window within the run
 * @param timestamp                   when the summary was captured
 * @param elapsedSeconds              wall/virtual seconds since the run start
 * @param heapUsedBytes               retained heap used after the per-minute best-effort full GC
 * @param metaspaceUsedBytes          metaspace used, or {@code -1} if unsupported
 * @param threadCount                 live thread count
 * @param openFdCount                 open file descriptors, or {@code -1} if unsupported
 * @param loadedClassCount            JVM loaded-class count
 * @param publishedRuleCount          agent rule registry size (real runtime)
 * @param snapshotCount               bytecode snapshot repository size (real runtime)
 * @param journalRecordCount         transformation journal record count (real runtime)
 * @param instrumentationTypeCount    instrumented type count (real runtime)
 * @param instrumentationMethodCount  instrumented method count (real runtime)
 * @param continuousInvocations       cumulative real enhanced-target invocations since run start
 * @param batchesRun                  cumulative enhance/update/partial-unload/full-unload batches
 * @param disconnectsRun              cumulative Agent/Platform disconnect/recovery cycles
 * @param driftDetected               whether a state drift was observed in this window
 * @param driftPersistentSeconds      how long the drift has persisted so far (0 if none)
 * @param heapBreach                   heap over budget vs baseline this window
 * @param metaspaceBreach              metaspace over budget vs baseline this window (false if unsupported)
 * @param threadBreach                 thread delta over budget vs baseline this window
 * @param fdBreach                     fd delta over budget vs baseline this window (false if unsupported)
 * @param sustainedBreach              a resource breach crossed the sustained-window threshold this window
 */
public record SoakObservation(
        int minuteIndex,
        Instant timestamp,
        long elapsedSeconds,
        long heapUsedBytes,
        long metaspaceUsedBytes,
        int threadCount,
        long openFdCount,
        int loadedClassCount,
        int publishedRuleCount,
        int snapshotCount,
        int journalRecordCount,
        int instrumentationTypeCount,
        int instrumentationMethodCount,
        long continuousInvocations,
        int batchesRun,
        int disconnectsRun,
        boolean driftDetected,
        long driftPersistentSeconds,
        boolean heapBreach,
        boolean metaspaceBreach,
        boolean threadBreach,
        boolean fdBreach,
        boolean sustainedBreach) {

        /** A copy with the per-window breach flags replaced (computed after construction vs the baseline). */
        public SoakObservation withBreaches(boolean heap, boolean metaspace, boolean thread, boolean fd) {
            return new SoakObservation(minuteIndex, timestamp, elapsedSeconds, heapUsedBytes, metaspaceUsedBytes,
                    threadCount, openFdCount, loadedClassCount, publishedRuleCount, snapshotCount,
                    journalRecordCount, instrumentationTypeCount, instrumentationMethodCount,
                    continuousInvocations, batchesRun, disconnectsRun, driftDetected, driftPersistentSeconds,
                    heap, metaspace, thread, fd, sustainedBreach);
        }

        /** A copy with the sustained-breach flag replaced (set when the gate fires for this window). */
        public SoakObservation withSustainedBreach(boolean sustained) {
            return new SoakObservation(minuteIndex, timestamp, elapsedSeconds, heapUsedBytes, metaspaceUsedBytes,
                    threadCount, openFdCount, loadedClassCount, publishedRuleCount, snapshotCount,
                    journalRecordCount, instrumentationTypeCount, instrumentationMethodCount,
                    continuousInvocations, batchesRun, disconnectsRun, driftDetected, driftPersistentSeconds,
                    heapBreach, metaspaceBreach, threadBreach, fdBreach, sustained);
        }
}
