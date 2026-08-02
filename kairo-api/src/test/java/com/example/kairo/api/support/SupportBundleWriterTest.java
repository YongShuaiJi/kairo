package com.example.kairo.api.support;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V1.7 M4-C &sect;11.3 unit tests for the safety invariants of {@link SupportBundleWriter}: entry-name
 * validation (traversal/absolute/control-char/backslash/duplicate/conflicting), secret scrubbing,
 * streaming compressed-size budget enforcement (bounded sink aborts during serialisation), oversized-
 * manifest failure, deadline handling, and atomic temp-file cleanup on failure.
 */
class SupportBundleWriterTest {

    private static final SupportBundleWriter.ManifestSupplier FIXED_MANIFEST =
            (kept, dropped, truncated) -> ("{\"entries\":" + kept + ",\"dropped\":" + dropped
                    + ",\"truncated\":" + truncated + "}").getBytes(StandardCharsets.UTF_8);

    @Test
    void validEntryNamesAreAccepted() {
        SupportBundleWriter.validateEntryName("manifest.json");
        SupportBundleWriter.validateEntryName("actuator/health.json");
        SupportBundleWriter.validateEntryName("metrics/kairo_operation_total.json");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "", "   ", "/leading-slash.json",
            "a/../b.json", "../secret", "a/../../c", "a\\b.json", "dir/",
            "a//b.json", "a/./b", "control" + (char) 0x01 + ".json",
            "tab" + (char) 0x09 + ".json", "del" + (char) 0x7f + ".json",
            "back\\slash.json"
    })
    void invalidEntryNamesAreRejected(String name) {
        assertThatThrownBy(() -> SupportBundleWriter.validateEntryName(name))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SupportBundleWriter.Entry(name, new byte[0]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullEntryNameIsRejected() {
        assertThatThrownBy(() -> SupportBundleWriter.validateEntryName(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void duplicateAndConflictingEntriesAreRejected() {
        assertThatThrownBy(() -> SupportBundleWriter.assertNoDuplicates(
                List.of("a/b.json", "a/b.json")))
                .isInstanceOf(IllegalArgumentException.class);
        // "metrics" as a file entry conflicts with "metrics/kairo_x.json" (directory/file conflict)
        assertThatThrownBy(() -> SupportBundleWriter.assertNoDuplicates(
                List.of("metrics", "metrics/kairo_x.json")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void assertNoDuplicatesAcceptsDistinctNonConflictingNames() {
        SupportBundleWriter.assertNoDuplicates(List.of(
                "manifest.json", "actuator/health.json", "actuator/info.json",
                "metrics/index.json", "metrics/kairo_x.json", "operations/recent.json", "config.json"));
    }

    @Test
    void scrubReplacesRegisteredSecrets() {
        byte[] content = "{\"token\":\"s3cr3t-abc\",\"other\":\"value\"}".getBytes(StandardCharsets.UTF_8);
        byte[] scrubbed = SupportBundleWriter.scrub(content, Set.of("s3cr3t-abc"));
        assertThat(new String(scrubbed, StandardCharsets.UTF_8))
                .contains("\"token\":\"***\"")
                .doesNotContain("s3cr3t-abc");
    }

    @Test
    void scrubIsNoOpWithoutSecrets() {
        byte[] content = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);
        assertThat(SupportBundleWriter.scrub(content, Set.of())).isSameAs(content);
        assertThat(SupportBundleWriter.scrub(content, null)).isSameAs(content);
    }

    @Test
    void buildBundleFitsAllEntriesUnderLargeBudget() throws IOException {
        List<SupportBundleWriter.Entry> data = List.of(
                new SupportBundleWriter.Entry("actuator/health.json", "{\"status\":\"UP\"}".getBytes(StandardCharsets.UTF_8)),
                new SupportBundleWriter.Entry("config.json", "{}".getBytes(StandardCharsets.UTF_8)));
        byte[] zip = SupportBundleWriter.buildBundle(data, FIXED_MANIFEST, Set.of(),
                SupportBundleWriter.DEFAULT_SIZE_BUDGET_BYTES,
                SupportBundleWriter.deadlineNanos(SupportBundleWriter.DEFAULT_TIMEOUT_MILLIS));
        assertThat(zip.length).isLessThanOrEqualTo((int) SupportBundleWriter.DEFAULT_SIZE_BUDGET_BYTES);
        try (ZipFile zf = new ZipFile(createTempZip(zip).toFile())) {
            assertThat(entries(zf)).containsExactlyInAnyOrder(
                    "manifest.json", "actuator/health.json", "config.json");
            assertThat(new String(zf.getInputStream(zf.getEntry("actuator/health.json")).readAllBytes(),
                    StandardCharsets.UTF_8)).contains("\"status\":\"UP\"");
        }
    }

    @Test
    void buildBundleDropsOversizedEntriesAndMarksTruncated() throws IOException {
        // Pseudo-random bytes do not compress, so the entry's compressed size still exceeds the budget.
        byte[] big = incompressible(8 * 1024);
        List<SupportBundleWriter.Entry> data = List.of(
                new SupportBundleWriter.Entry("small.json", "{}".getBytes(StandardCharsets.UTF_8)),
                new SupportBundleWriter.Entry("big.json", big));
        // Tiny budget: the manifest + small entry fit, the big entry is dropped.
        byte[] zip = SupportBundleWriter.buildBundle(data, FIXED_MANIFEST, Set.of(),
                2048, SupportBundleWriter.deadlineNanos(10_000));
        assertThat(zip.length).isLessThanOrEqualTo(2048);
        try (ZipFile zf = new ZipFile(createTempZip(zip).toFile())) {
            Set<String> names = entries(zf);
            assertThat(names).contains("manifest.json", "small.json").doesNotContain("big.json");
            String manifest = new String(zf.getInputStream(zf.getEntry("manifest.json")).readAllBytes(),
                    StandardCharsets.UTF_8);
            assertThat(manifest).contains("\"truncated\":true").contains("big.json");
        }
    }

    @Test
    void buildBundleAbortsLargeIncompressibleArchiveWithoutBufferingIt() throws IOException {
        // A 1 MiB incompressible entry with a 4 KiB budget: a post-hoc implementation would buffer the
        // full ~1 MiB compressed archive before checking; the bounded sink aborts at 4 KiB and the big
        // entry is dropped. The final archive never exceeds the budget.
        byte[] big = incompressible(1024 * 1024);
        List<SupportBundleWriter.Entry> data = List.of(
                new SupportBundleWriter.Entry("small.json", "{\"ok\":true}".getBytes(StandardCharsets.UTF_8)),
                new SupportBundleWriter.Entry("big.json", big));
        byte[] zip = SupportBundleWriter.buildBundle(data, FIXED_MANIFEST, Set.of(),
                4096, SupportBundleWriter.deadlineNanos(10_000));
        assertThat(zip.length).isLessThanOrEqualTo(4096);
        try (ZipFile zf = new ZipFile(createTempZip(zip).toFile())) {
            assertThat(entries(zf)).contains("manifest.json", "small.json").doesNotContain("big.json");
        }
    }

    @Test
    void buildBundleFailsWhenManifestAloneExceedsBudget() {
        // The manifest alone is far larger than the tiny budget; kept is empty, so the writer must fail
        // safely and never return an over-budget archive.
        SupportBundleWriter.ManifestSupplier hugeManifest =
                (kept, dropped, truncated) -> incompressible(10 * 1024);
        assertThatThrownBy(() -> SupportBundleWriter.buildBundle(List.of(), hugeManifest, Set.of(),
                1024, SupportBundleWriter.deadlineNanos(10_000)))
                .isInstanceOf(SupportBundleWriter.BundleBudgetExceededException.class);
    }

    @Test
    void buildBundleReturnsManifestOnlyWhenAllDataDroppedAndManifestFits() throws IOException {
        // When every data entry is dropped but the manifest fits, a manifest-only (truncated) archive is
        // returned; it is never over-budget.
        byte[] big = incompressible(8 * 1024);
        List<SupportBundleWriter.Entry> data = List.of(
                new SupportBundleWriter.Entry("big.json", big));
        byte[] zip = SupportBundleWriter.buildBundle(data, FIXED_MANIFEST, Set.of(),
                2048, SupportBundleWriter.deadlineNanos(10_000));
        assertThat(zip.length).isLessThanOrEqualTo(2048);
        try (ZipFile zf = new ZipFile(createTempZip(zip).toFile())) {
            assertThat(entries(zf)).containsExactly("manifest.json");
            String manifest = new String(zf.getInputStream(zf.getEntry("manifest.json")).readAllBytes(),
                    StandardCharsets.UTF_8);
            assertThat(manifest).contains("\"truncated\":true").contains("big.json");
        }
    }

    @Test
    void boundedOutputStreamAbortsAtBudgetAndNeverRetainsMore() {
        // The counting sink accepts at most `budget` bytes; writing past it aborts and the sink never
        // retains more than `budget + one chunk`.
        long budget = 4096;
        SupportBundleWriter.BoundedOutputStream bos = new SupportBundleWriter.BoundedOutputStream(budget);
        byte[] data = incompressible(100_000);
        boolean threw = false;
        try {
            int off = 0;
            while (off < data.length) {
                int len = Math.min(SupportBundleWriter.CHUNK, data.length - off);
                bos.write(data, off, len);
                off += len;
            }
        } catch (SupportBundleWriter.BundleBudgetExceededException e) {
            threw = true;
        }
        assertThat(threw).isTrue();
        assertThat(bos.count()).isLessThanOrEqualTo(budget);
    }

    @Test
    void buildBundleScrubsSecretsFromAllEntries() throws IOException {
        byte[] secret = "CANARY-7c9f".getBytes(StandardCharsets.UTF_8);
        List<SupportBundleWriter.Entry> data = List.of(
                new SupportBundleWriter.Entry("actuator/health.json", secret));
        byte[] zip = SupportBundleWriter.buildBundle(data,
                (kept, dropped, truncated) -> ("{\"name\":\"manifest\",\"token\":\"CANARY-7c9f\"}")
                        .getBytes(StandardCharsets.UTF_8),
                Set.of("CANARY-7c9f"), 64 * 1024,
                SupportBundleWriter.deadlineNanos(10_000));
        try (ZipFile zf = new ZipFile(createTempZip(zip).toFile())) {
            for (String name : entries(zf)) {
                String content = new String(zf.getInputStream(zf.getEntry(name)).readAllBytes(),
                        StandardCharsets.UTF_8);
                assertThat(content).doesNotContain("CANARY-7c9f");
            }
        }
    }

    @Test
    void writeAtomicallyWritesAndLeavesNoTempBehind(@TempDir Path dir) throws IOException {
        byte[] zip = SupportBundleWriter.serializeZip(List.of(
                new SupportBundleWriter.Entry("manifest.json", "{}".getBytes(StandardCharsets.UTF_8))));
        Path dest = dir.resolve("bundle.zip");
        SupportBundleWriter.writeAtomically(dest, zip,
                SupportBundleWriter.deadlineNanos(10_000));
        assertThat(Files.exists(dest)).isTrue();
        assertThat(Files.readAllBytes(dest)).isEqualTo(zip);
        // No leftover temp files from this run.
        try (var stream = Files.list(dir)) {
            assertThat(stream.filter(p -> p.getFileName().toString().startsWith(".kairo-bundle-"))
                    .count()).isZero();
        }
    }

    @Test
    void writeAtomicallyCleansUpTempOnFailure(@TempDir Path dir) throws IOException {
        // Make the dest a non-empty directory: moving a file onto it fails, exercising temp cleanup.
        Path dest = dir.resolve("blocker");
        Files.createDirectories(dest);
        Files.writeString(dest.resolve("inner"), "present");
        byte[] zip = SupportBundleWriter.serializeZip(List.of(
                new SupportBundleWriter.Entry("manifest.json", "{}".getBytes(StandardCharsets.UTF_8))));
        assertThatThrownBy(() -> SupportBundleWriter.writeAtomically(dest, zip,
                SupportBundleWriter.deadlineNanos(10_000)))
                .isInstanceOf(IOException.class);
        // No leftover temp file from this run; the blocking dir is untouched.
        try (var stream = Files.list(dir)) {
            assertThat(stream.filter(p -> p.getFileName().toString().startsWith(".kairo-bundle-"))
                    .count()).isZero();
        }
        assertThat(Files.readString(dest.resolve("inner"))).isEqualTo("present");
    }

    @Test
    void expiredDeadlineAbortsWrite(@TempDir Path dir) {
        byte[] zip = "{}".getBytes(StandardCharsets.UTF_8);
        long pastDeadline = System.nanoTime() - 1_000_000L;
        assertThatThrownBy(() -> SupportBundleWriter.writeAtomically(dir.resolve("bundle.zip"), zip, pastDeadline))
                .isInstanceOf(SupportBundleWriter.BundleTimeoutException.class);
        assertThat(Files.exists(dir.resolve("bundle.zip"))).isFalse();
    }

    @Test
    void defaultBudgetIsTwentyMebibytes() {
        assertThat(SupportBundleWriter.DEFAULT_SIZE_BUDGET_BYTES).isEqualTo(20L * 1024 * 1024);
        assertThat(SupportBundleWriter.DEFAULT_TIMEOUT_MILLIS).isEqualTo(30_000L);
    }

    private static Set<String> entries(ZipFile zf) {
        return zf.stream().map(ZipEntry::getName).collect(java.util.stream.Collectors.toSet());
    }

    private static Path createTempZip(byte[] zip) throws IOException {
        Path p = Files.createTempFile("bundle-", ".zip");
        Files.write(p, zip);
        return p;
    }

    /** Deterministic pseudo-random bytes that DEFLATE cannot compress (forces compressed-size budget). */
    private static byte[] incompressible(int len) {
        byte[] b = new byte[len];
        int state = 0x12345678;
        for (int i = 0; i < len; i++) {
            state = state * 1103515245 + 12345;
            b[i] = (byte) (state >>> 24);
        }
        return b;
    }
}
