package com.example.kairo.agent.core.bytecode.diff;

import com.example.kairo.agent.core.bytecode.BytecodeHash;
import com.example.kairo.api.bytecode.BytecodeDiffResult;
import com.example.kairo.api.bytecode.BytecodeSnapshotKind;
import com.example.kairo.api.bytecode.ClassIdentity;
import com.example.kairo.api.bytecode.TransformationRevision;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Authoritative bytecode comparison. Normalizes both sides with
 * {@link BytecodeNormalizer} (shaded ASM core, no {@code tree} package) and
 * produces a frozen {@link BytecodeDiffResult} carrying per-method instruction
 * diffs and class-level structural diffs.
 *
 * <p>Constant-pool indices, stack-map frames and debug info are normalized away,
 * so two semantically-equal class files compare identical even when their raw
 * bytes (and SHA-256 hashes) differ. Real Advice changes - added instructions
 * and the {@code onThrowable} try-catch - are preserved and surfaced as method
 * diffs.
 */
public final class BytecodeDiffService {

    /**
     * Diff two bytecode payloads of the same class.
     *
     * @param classIdentity the class both snapshots belong to
     * @param fromBytes     nullable "from" bytes; null yields an un-normalized result
     * @param fromRevision  revision of the "from" snapshot
     * @param fromKind      kind of the "from" snapshot
     * @param toBytes       nullable "to" bytes; null yields an un-normalized result
     * @param toRevision    revision of the "to" snapshot
     * @param toKind        kind of the "to" snapshot
     */
    public BytecodeDiffResult diff(ClassIdentity classIdentity,
                                   byte[] fromBytes, TransformationRevision fromRevision, BytecodeSnapshotKind fromKind,
                                   byte[] toBytes, TransformationRevision toRevision, BytecodeSnapshotKind toKind) {
        Objects.requireNonNull(classIdentity, "classIdentity");
        Objects.requireNonNull(fromRevision, "fromRevision");
        Objects.requireNonNull(toRevision, "toRevision");
        Objects.requireNonNull(fromKind, "fromKind");
        Objects.requireNonNull(toKind, "toKind");

        String fromHash = fromBytes == null ? null : BytecodeHash.sha256Hex(fromBytes);
        String toHash = toBytes == null ? null : BytecodeHash.sha256Hex(toBytes);

        if (fromBytes == null || toBytes == null) {
            return new BytecodeDiffResult(classIdentity, fromRevision, toRevision, fromKind, toKind,
                    fromHash, toHash, false, false, List.of(), List.of(),
                    "one side is null; cannot normalize");
        }

        NormalizedClass from;
        NormalizedClass to;
        try {
            from = BytecodeNormalizer.normalize(fromBytes);
            to = BytecodeNormalizer.normalize(toBytes);
        } catch (RuntimeException e) {
            return new BytecodeDiffResult(classIdentity, fromRevision, toRevision, fromKind, toKind,
                    fromHash, toHash, false, false, List.of(),
                    List.of("normalization failed: " + e.getClass().getSimpleName() + ": " + e.getMessage()),
                    "normalization failed: " + e.getMessage());
        }

        boolean identical = from.equals(to);
        List<String> structuralDiffs = structuralDiffs(from, to);
        List<BytecodeDiffResult.MethodDiff> methodDiffs = methodDiffs(from, to);

        String summary = identical
                ? "identical (normalized)"
                : "differ: " + methodDiffs.size() + " method(s), " + structuralDiffs.size() + " structural";

        return new BytecodeDiffResult(classIdentity, fromRevision, toRevision, fromKind, toKind,
                fromHash, toHash, identical, true, methodDiffs, structuralDiffs, summary);
    }

    private static List<String> structuralDiffs(NormalizedClass from, NormalizedClass to) {
        List<String> diffs = new ArrayList<>();
        if (from.access != to.access) {
            diffs.add("access: " + from.access + " -> " + to.access);
        }
        if (!equalsNullable(from.superName, to.superName)) {
            diffs.add("super: " + from.superName + " -> " + to.superName);
        }
        if (!equalsNullable(from.signature, to.signature)) {
            diffs.add("signature: " + from.signature + " -> " + to.signature);
        }
        if (!from.interfaces.equals(to.interfaces)) {
            diffs.add("interfaces: " + from.interfaces + " -> " + to.interfaces);
        }
        if (!from.annotations.equals(to.annotations)) {
            diffs.add("annotations: " + from.annotations + " -> " + to.annotations);
        }
        Map<String, NormalizedClass.NormalizedField> fromFields = fieldMap(from);
        Map<String, NormalizedClass.NormalizedField> toFields = fieldMap(to);
        for (String key : unionKeys(fromFields, toFields)) {
            NormalizedClass.NormalizedField f = fromFields.get(key);
            NormalizedClass.NormalizedField t = toFields.get(key);
            if (f == null) {
                diffs.add("field added: " + key);
            } else if (t == null) {
                diffs.add("field removed: " + key);
            } else if (!f.equals(t)) {
                diffs.add("field modified: " + key + " (" + f.canonicalLine() + " -> " + t.canonicalLine() + ")");
            }
        }
        return diffs;
    }

    private static List<BytecodeDiffResult.MethodDiff> methodDiffs(NormalizedClass from, NormalizedClass to) {
        Map<String, NormalizedClass.NormalizedMethod> fromMethods = methodMap(from);
        Map<String, NormalizedClass.NormalizedMethod> toMethods = methodMap(to);
        List<BytecodeDiffResult.MethodDiff> diffs = new ArrayList<>();
        for (String key : unionKeys(fromMethods, toMethods)) {
            NormalizedClass.NormalizedMethod f = fromMethods.get(key);
            NormalizedClass.NormalizedMethod t = toMethods.get(key);
            if (f == null) {
                diffs.add(new BytecodeDiffResult.MethodDiff(t.name, t.desc, BytecodeDiffResult.ChangeType.ADDED,
                        prefix("-", t.instructions), List.of()));
            } else if (t == null) {
                diffs.add(new BytecodeDiffResult.MethodDiff(f.name, f.desc, BytecodeDiffResult.ChangeType.REMOVED,
                        prefix("+", f.instructions), List.of()));
            } else if (!f.equals(t)) {
                diffs.add(new BytecodeDiffResult.MethodDiff(f.name, f.desc, BytecodeDiffResult.ChangeType.MODIFIED,
                        instructionDiff(f.instructions, t.instructions), attributeDiff(f, t)));
            }
        }
        return diffs;
    }

    private static List<String> attributeDiff(NormalizedClass.NormalizedMethod f, NormalizedClass.NormalizedMethod t) {
        List<String> attrs = new ArrayList<>();
        if (f.access != t.access) {
            attrs.add("access: " + f.access + " -> " + t.access);
        }
        if (!equalsNullable(f.signature, t.signature)) {
            attrs.add("signature: " + f.signature + " -> " + t.signature);
        }
        if (!f.exceptions.equals(t.exceptions)) {
            attrs.add("throws: " + f.exceptions + " -> " + t.exceptions);
        }
        if (!f.annotations.equals(t.annotations)) {
            attrs.add("annotations: " + f.annotations + " -> " + t.annotations);
        }
        if (!f.tryCatch.equals(t.tryCatch)) {
            attrs.add("try-catch: " + f.tryCatch + " -> " + t.tryCatch);
        }
        return attrs;
    }

    /**
     * Longest-common-subsequence line diff of two instruction token lists,
     * emitted as {@code + ...} (added) / {@code - ...} (removed) lines.
     */
    private static List<String> instructionDiff(List<String> from, List<String> to) {
        int n = from.size();
        int m = to.size();
        int[][] dp = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                dp[i][j] = from.get(i).equals(to.get(j))
                        ? dp[i + 1][j + 1] + 1
                        : Math.max(dp[i + 1][j], dp[i][j + 1]);
            }
        }
        List<String> out = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < n && j < m) {
            if (from.get(i).equals(to.get(j))) {
                out.add("  " + from.get(i));
                i++;
                j++;
            } else if (dp[i + 1][j] >= dp[i][j + 1]) {
                out.add("- " + from.get(i));
                i++;
            } else {
                out.add("+ " + to.get(j));
                j++;
            }
        }
        while (i < n) {
            out.add("- " + from.get(i++));
        }
        while (j < m) {
            out.add("+ " + to.get(j++));
        }
        return out;
    }

    private static List<String> prefix(String p, List<String> lines) {
        List<String> out = new ArrayList<>(lines.size());
        for (String line : lines) {
            out.add(p + " " + line);
        }
        return out;
    }

    private static Map<String, NormalizedClass.NormalizedField> fieldMap(NormalizedClass c) {
        Map<String, NormalizedClass.NormalizedField> map = new LinkedHashMap<>();
        for (NormalizedClass.NormalizedField f : c.fields) {
            map.put(f.name + " " + f.desc, f);
        }
        return map;
    }

    private static Map<String, NormalizedClass.NormalizedMethod> methodMap(NormalizedClass c) {
        Map<String, NormalizedClass.NormalizedMethod> map = new LinkedHashMap<>();
        for (NormalizedClass.NormalizedMethod m : c.methods) {
            map.put(m.key(), m);
        }
        return map;
    }

    private static List<String> unionKeys(Map<String, ?> a, Map<String, ?> b) {
        List<String> keys = new ArrayList<>(a.size() + b.size());
        for (String k : a.keySet()) {
            if (!keys.contains(k)) {
                keys.add(k);
            }
        }
        for (String k : b.keySet()) {
            if (!keys.contains(k)) {
                keys.add(k);
            }
        }
        return keys;
    }

    private static boolean equalsNullable(Object a, Object b) {
        return Objects.equals(a, b);
    }
}
