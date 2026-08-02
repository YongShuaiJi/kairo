package com.example.kairo.api.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Bounded, safe-by-construction ZIP writer for V1.7 M4-C &sect;11.3 support bundles. Pure JDK &mdash;
 * no JSON library, no Spring, no HTTP &mdash; so the same safety contract is shared by both
 * {@code kairo-cli diagnose} and {@code kairo-ops support-bundle}. The owning command collects entries
 * (already-bounded, in-memory bytes) and supplies a manifest; this writer owns the safety invariants
 * the &sect;11.3 contract requires:
 *
 * <ul>
 *   <li><b>Entry-name validation</b> &mdash; rejects absolute paths, {@code ..} traversal, backslash
 *       separators, control characters, directory entries, empty/self segments, duplicates and
 *       directory/file conflicts. Validated on construction, so a bad name can never reach the archive.</li>
 *   <li><b>Secret scrubbing</b> &mdash; every registered secret string is replaced by {@code "***"}
 *       across all entry bytes (data and manifest) before serialisation.</li>
 *   <li><b>Byte budget (streaming)</b> &mdash; enforced <em>during</em> serialisation by a counting sink
 *       ({@link BoundedOutputStream}) that aborts as soon as compressed ZIP bytes exceed the budget,
 *       never retaining more than roughly {@code budget + one write chunk}. The writer drops the largest
 *       remaining entry and retries from a fresh bounded attempt; if the manifest alone cannot fit, it
 *       fails safely ({@link BundleBudgetExceededException}) and no archive is produced.</li>
 *   <li><b>Whole-operation timeout</b> &mdash; a deadline is checked while scrubbing, selecting,
 *       serialising each entry, writing each chunk, and before the atomic move; an expired deadline throws
 *       {@link BundleTimeoutException} and no archive is produced.</li>
 *   <li><b>Atomic output</b> &mdash; the archive is written to a temp file in the destination directory and
 *       moved atomically; if atomic move is unsupported the operation fails and cleans the temp (no silent
 *       non-atomic fallback). The temp created by this invocation is removed on any failure.</li>
 * </ul>
 *
 * <p>Collection is never filesystem-based (all entries are in-memory bytes from bounded HTTP responses),
 * so there is no directory walk and no symlink to follow.
 */
public final class SupportBundleWriter {

    /** Default hard maximum archive size: 20 MiB (&sect;11.3). */
    public static final long DEFAULT_SIZE_BUDGET_BYTES = 20L * 1024 * 1024;

    /** Default whole-operation timeout: 30 seconds (&sect;11.3). */
    public static final long DEFAULT_TIMEOUT_MILLIS = 30_000L;

    /** Stable, deterministic manifest entry name. */
    public static final String MANIFEST_ENTRY = "manifest.json";

    /** Chunk size used for streaming entry content into the ZIP (and for deadline checks). */
    static final int CHUNK = 8192;

    private SupportBundleWriter() {
    }

    /** A single archive entry: a validated name and its already-serialised UTF-8 content. */
    public record Entry(String name, byte[] content) {
        public Entry {
            validateEntryName(name);
            if (content == null) {
                content = new byte[0];
            }
        }
    }

    /** Supplies the manifest bytes given the final kept/dropped entry names and truncation flag. */
    @FunctionalInterface
    public interface ManifestSupplier {
        byte[] manifest(List<String> keptEntryNames, List<String> droppedEntryNames, boolean truncated);
    }

    /** Raised when the operation deadline expires; the caller must leave no final bundle. */
    public static final class BundleTimeoutException extends RuntimeException {
        public BundleTimeoutException(String message) {
            super(message);
        }
    }

    /**
     * Raised when the archive cannot fit the byte budget &mdash; in particular when the manifest alone
     * exceeds the budget. The caller must leave no final bundle (fail safely).
     */
    public static final class BundleBudgetExceededException extends RuntimeException {
        public BundleBudgetExceededException(String message) {
            super(message);
        }
    }

    /**
     * Validate a single entry name. Throws on blank, absolute path, backslash, control characters,
     * {@code ..} traversal, {@code .} self segments, empty segments, trailing slash, or excessive length.
     */
    public static String validateEntryName(String name) {
        if (name == null || name.isBlank()) {
            throw invalid("blank entry name");
        }
        if (name.length() > 256) {
            throw invalid("entry name too long: " + name.length());
        }
        if (name.charAt(0) == '/') {
            throw invalid("absolute path: " + name);
        }
        if (name.charAt(0) == '\\') {
            throw invalid("backslash-leading path: " + name);
        }
        if (name.endsWith("/")) {
            throw invalid("directory entry: " + name);
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                throw invalid("control character in entry name");
            }
            if (c == '\\') {
                throw invalid("backslash separator in entry name");
            }
        }
        if (name.contains("//")) {
            throw invalid("empty segment in entry name");
        }
        for (String segment : name.split("/")) {
            if ("..".equals(segment)) {
                throw invalid("traversal segment in entry name");
            }
            if (".".equals(segment)) {
                throw invalid("self segment in entry name");
            }
        }
        return name;
    }

    /** Reject duplicate or directory/file-conflicting entry names. */
    public static void assertNoDuplicates(List<String> names) {
        Set<String> seen = new LinkedHashSet<>();
        for (String n : names) {
            validateEntryName(n);
            if (!seen.add(n)) {
                throw invalid("duplicate entry: " + n);
            }
        }
        for (String n : names) {
            int slash = n.indexOf('/');
            if (slash > 0 && seen.contains(n.substring(0, slash))) {
                throw invalid("conflicting entry: " + n + " under directory " + n.substring(0, slash));
            }
        }
    }

    /** Replace every registered secret string in UTF-8 content with {@code "***"}. */
    public static byte[] scrub(byte[] content, Collection<String> secrets) {
        if (content == null || content.length == 0 || secrets == null || secrets.isEmpty()) {
            return content == null ? new byte[0] : content;
        }
        String text = new String(content, StandardCharsets.UTF_8);
        boolean changed = false;
        for (String secret : secrets) {
            if (secret == null || secret.isEmpty()) {
                continue;
            }
            if (text.contains(secret)) {
                text = text.replace(secret, "***");
                changed = true;
            }
        }
        return changed ? text.getBytes(StandardCharsets.UTF_8) : content;
    }

    /** Deadline (nanos, {@link System#nanoTime()} basis) for a timeout in milliseconds. */
    public static long deadlineNanos(long timeoutMillis) {
        return System.nanoTime() + Math.max(0L, timeoutMillis) * 1_000_000L;
    }

    /** Whether the deadline has expired. */
    public static boolean expired(long deadlineNanos) {
        return System.nanoTime() >= deadlineNanos;
    }

    /** Remaining milliseconds until the deadline (never negative). */
    public static long remainingMillis(long deadlineNanos) {
        return Math.max(0L, (deadlineNanos - System.nanoTime()) / 1_000_000L);
    }

    /**
     * Build a manifest + data entries into a single ZIP byte[], enforcing the byte budget <em>during</em>
     * serialisation by a counting sink that aborts as soon as compressed ZIP bytes exceed the budget.
     * On overflow the largest remaining data entry is dropped and a fresh bounded attempt is made; the
     * manifest is re-supplied each attempt. Secrets are scrubbed from every entry (data and manifest)
     * before serialisation. The deadline is checked while scrubbing, before each manifest, before each
     * serialisation attempt, per entry and per chunk; an expired deadline throws
     * {@link BundleTimeoutException}. If the manifest alone cannot fit, {@link BundleBudgetExceededException}
     * is thrown and no archive is produced.
     */
    public static byte[] buildBundle(List<Entry> dataEntries, ManifestSupplier manifestSupplier,
                                     Collection<String> secrets, long budgetBytes, long deadlineNanos)
            throws IOException {
        long budget = Math.max(1L, budgetBytes);
        List<Entry> scrubbed = scrubAll(dataEntries, secrets, deadlineNanos);
        List<Entry> kept = new ArrayList<>(scrubbed);
        List<String> dropped = new ArrayList<>();
        while (true) {
            checkDeadline(deadlineNanos);
            byte[] manifestBytes = scrub(manifestSupplier.manifest(entryNames(kept), List.copyOf(dropped),
                    !dropped.isEmpty()), secrets);
            Entry manifestEntry = new Entry(MANIFEST_ENTRY, manifestBytes);
            List<Entry> all = new ArrayList<>(kept.size() + 1);
            all.add(manifestEntry);
            all.addAll(kept);
            try {
                return serializeBounded(all, budget, deadlineNanos);
            } catch (BundleBudgetExceededException overflow) {
                checkDeadline(deadlineNanos);
                if (kept.isEmpty()) {
                    // Manifest alone cannot fit: fail safely with no bundle.
                    throw new BundleBudgetExceededException(
                            "support bundle manifest exceeds size budget; no bundle written");
                }
                int largest = largestIndex(kept);
                dropped.add(kept.remove(largest).name());
            }
        }
    }

    /**
     * Serialise entries (manifest first per caller order) to a deterministic ZIP byte[] with no bound.
     * Intended only for caller-known-tiny serialisations (e.g. unit-test fixtures); the production path
     * uses {@link #buildBundle(List, ManifestSupplier, Collection, long, long)} which bounds the output.
     */
    public static byte[] serializeZip(List<Entry> entries) throws IOException {
        List<String> names = new ArrayList<>(entries.size());
        for (Entry e : entries) {
            names.add(e.name());
        }
        assertNoDuplicates(names);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos, StandardCharsets.UTF_8)) {
            for (Entry e : entries) {
                ZipEntry ze = new ZipEntry(e.name());
                ze.setTime(0L);
                zos.putNextEntry(ze);
                zos.write(e.content());
                zos.closeEntry();
            }
        }
        return baos.toByteArray();
    }

    /**
     * Serialise entries to a ZIP, aborting (via {@link BundleBudgetExceededException}) as soon as the
     * compressed bytes written exceed {@code budget}. Never retains more than roughly {@code budget + one
     * chunk}. The deadline is checked before each entry and before each chunk write.
     */
    static byte[] serializeBounded(List<Entry> entries, long budget, long deadlineNanos) throws IOException {
        List<String> names = new ArrayList<>(entries.size());
        for (Entry e : entries) {
            names.add(e.name());
        }
        assertNoDuplicates(names);
        BoundedOutputStream bos = new BoundedOutputStream(budget);
        boolean overflow = false;
        try (ZipOutputStream zos = new ZipOutputStream(bos, StandardCharsets.UTF_8)) {
            for (Entry e : entries) {
                checkDeadline(deadlineNanos);
                ZipEntry ze = new ZipEntry(e.name());
                ze.setTime(0L);
                zos.putNextEntry(ze);
                byte[] content = e.content();
                int off = 0;
                while (off < content.length) {
                    checkDeadline(deadlineNanos);
                    int len = Math.min(CHUNK, content.length - off);
                    zos.write(content, off, len);
                    off += len;
                }
                zos.closeEntry();
            }
        } catch (BundleBudgetExceededException overflowDuringWrite) {
            overflow = true;
        }
        if (overflow) {
            // The partial archive in `bos` is discarded; caller drops an entry and retries.
            throw new BundleBudgetExceededException("archive exceeded budget during serialisation");
        }
        return bos.toByteArray();
    }

    /**
     * Write the archive bytes atomically to {@code dest}: a temp file in the destination directory is
     * written then moved with {@code ATOMIC_MOVE} + {@code REPLACE_EXISTING}. The deadline is checked
     * first; an expired deadline throws {@link BundleTimeoutException}. If atomic move is unsupported the
     * operation fails and the temp is cleaned (no silent non-atomic fallback). The temp created by this
     * invocation is removed on any failure so no partial/misleading archive is left behind. The output
     * path is normalised (not dereferenced); collection is in-memory so no filesystem tree or symlink is
     * ever walked.
     */
    public static void writeAtomically(Path dest, byte[] zipBytes, long deadlineNanos) throws IOException {
        if (expired(deadlineNanos)) {
            throw new BundleTimeoutException("bundle deadline expired before write");
        }
        Path normalized = dest.toAbsolutePath().normalize();
        Path parent = normalized.getParent();
        if (parent == null) {
            parent = Path.of(".").toAbsolutePath();
        }
        Files.createDirectories(parent);
        Path temp = Files.createTempFile(parent, ".kairo-bundle-", ".tmp");
        try {
            Files.write(temp, zipBytes, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
            checkDeadline(deadlineNanos);
            // Atomic rename that also replaces any stale bundle from a previous run (POSIX rename(2)).
            // If ATOMIC_MOVE is unsupported we fail and clean the temp rather than silently falling back
            // to a non-atomic replacement.
            try {
                Files.move(temp, normalized, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException notAtomic) {
                throw new IOException("atomic move unsupported for " + normalized, notAtomic);
            }
        } catch (RuntimeException | IOException e) {
            try {
                Files.deleteIfExists(temp);
            } catch (Exception ignored) {
                // best-effort cleanup of this invocation's temp; original failure is propagated
            }
            throw e;
        }
    }

    /** Check the deadline, throwing {@link BundleTimeoutException} if expired. */
    public static void checkDeadline(long deadlineNanos) {
        if (expired(deadlineNanos)) {
            throw new BundleTimeoutException("bundle deadline expired during build");
        }
    }

    private static List<Entry> scrubAll(List<Entry> dataEntries, Collection<String> secrets, long deadlineNanos)
            throws IOException {
        List<Entry> scrubbed = new ArrayList<>(dataEntries.size());
        for (Entry e : dataEntries) {
            checkDeadline(deadlineNanos);
            scrubbed.add(new Entry(e.name(), scrub(e.content(), secrets)));
        }
        return scrubbed;
    }

    private static List<String> entryNames(List<Entry> entries) {
        List<String> names = new ArrayList<>(entries.size());
        for (Entry e : entries) {
            names.add(e.name());
        }
        return names;
    }

    private static int largestIndex(List<Entry> entries) {
        int idx = 0;
        for (int i = 1; i < entries.size(); i++) {
            if (entries.get(i).content().length > entries.get(idx).content().length) {
                idx = i;
            }
        }
        return idx;
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException("invalid bundle entry: " + message);
    }

    /**
     * A counting output stream that throws {@link BundleBudgetExceededException} as soon as a write would
     * exceed the configured limit. The backing buffer therefore never retains more than roughly
     * {@code limit + one chunk} (the chunk that triggers the abort is rejected, not stored). Used as the
     * sink for {@link ZipOutputStream} so the compressed archive is bounded during serialisation rather
     * than after the fact.
     */
    static final class BoundedOutputStream extends OutputStream {
        private final ByteArrayOutputStream baos;
        private final long limit;
        private long count;

        BoundedOutputStream(long limit) {
            this.limit = Math.max(1L, limit);
            this.baos = new ByteArrayOutputStream((int) Math.min(this.limit + CHUNK, Integer.MAX_VALUE));
        }

        @Override
        public void write(int b) {
            ensure(1);
            baos.write(b);
            count++;
        }

        @Override
        public void write(byte[] b, int off, int len) {
            if (len <= 0) {
                return;
            }
            ensure(len);
            baos.write(b, off, len);
            count += len;
        }

        private void ensure(int n) {
            if (count + (long) n > limit) {
                throw new BundleBudgetExceededException("bounded output stream limit exceeded");
            }
        }

        /** Bytes accepted so far (never exceeds {@code limit}). */
        long count() {
            return count;
        }

        byte[] toByteArray() {
            return baos.toByteArray();
        }
    }
}
