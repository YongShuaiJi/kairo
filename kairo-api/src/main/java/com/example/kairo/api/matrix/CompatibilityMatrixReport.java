package com.example.kairo.api.matrix;

import com.example.kairo.api.SupportLevel;

import java.util.List;
import java.util.Objects;

/**
 * The full V1.5 compatibility-matrix report (&sect;8: "完整兼容矩阵报告，含支持等级
 * 和失败原因"). Carries every evaluated entry, the runner JDK and a generated
 * summary so it can be serialized to the acceptance document and the Web.
 */
public final class CompatibilityMatrixReport {

    private final List<CompatibilityMatrixEntry> entries;
    private final String runnerJdk;
    private final long generatedAtMillis;
    private final String summary;

    public CompatibilityMatrixReport(List<CompatibilityMatrixEntry> entries, String runnerJdk,
                                     long generatedAtMillis, String summary) {
        this.entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
        this.runnerJdk = Objects.requireNonNull(runnerJdk, "runnerJdk");
        this.generatedAtMillis = generatedAtMillis;
        this.summary = summary;
    }

    public List<CompatibilityMatrixEntry> entries() {
        return entries;
    }

    public String runnerJdk() {
        return runnerJdk;
    }

    public long generatedAtMillis() {
        return generatedAtMillis;
    }

    public String summary() {
        return summary;
    }

    public int count(MatrixOutcome outcome) {
        return (int) entries.stream().filter(e -> e.outcome() == outcome).count();
    }

    public int count(SupportLevel level) {
        return (int) entries.stream().filter(e -> e.scenario().supportLevel() == level).count();
    }
}
