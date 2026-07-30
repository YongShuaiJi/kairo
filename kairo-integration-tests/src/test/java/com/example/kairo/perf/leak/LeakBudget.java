package com.example.kairo.perf.leak;

/**
 * The documented initial M2-C leak budgets (&sect;9.3). These are the fixed
 * thresholds the harness evaluates against; they are recorded verbatim in
 * {@code leak-result.json} under {@code budgets} so the evidence is self-describing.
 *
 * <p>The values are the roadmap's documented initial budgets. They must NOT be
 * weakened by the harness: if a gate fails, the harness records the failing gate and
 * exits non-zero rather than relaxing a threshold. A budget adjustment requires a
 * documented review with raw samples (&sect;9.3: "如果平台、GC 或测试噪声证明某数字
 * 不适用，必须先提交包含原始样本的预算调整，不允许测试自行放宽").
 *
 * <p>The cache budgets ({@link #snapshotMaxEntries} / {@link #journalMaxRecords}) are
 * the product's own bounded-cache limits (BytecodeSnapshotRepository.Config maxEntries
 * and TransformationJournal.Config globalLimit); "return to budget" means the observed
 * count stays at or below these bounds at every observation window and is cleared on
 * agent close.
 */
public record LeakBudget(
        int maxResidualClassLoaders,
        int maxThreadDelta,
        int maxFdDelta,
        int maxHeapGrowthPct,
        int maxMetaspaceGrowthPct,
        int snapshotMaxEntries,
        int journalMaxRecords,
        int groovyCacheMaxEntries,
        int generationMaxClasses) {

    /** The documented initial M2-C budgets (&sect;9.3) plus the product's cache bounds. */
    public static final LeakBudget DOCUMENTED = new LeakBudget(
            2,    // residual unloadable ClassLoaders after final strong-reference analysis
            2,    // running thread delta vs the first stable window
            5,    // file-descriptor delta vs the first stable window
            15,   // post-full-GC heap growth (last vs first stable window), percent
            10,   // metaspace growth (last vs first stable window), percent
            256,  // BytecodeSnapshotRepository maxEntries (product Config)
            4096, // TransformationJournal globalLimit (product Config)
            1024, // GroovyScriptCompiler MAX_CACHE_ENTRIES (product constant)
            256   // GroovyScriptCompiler MAX_CLASSES_PER_GENERATION (product constant)
    );
}
