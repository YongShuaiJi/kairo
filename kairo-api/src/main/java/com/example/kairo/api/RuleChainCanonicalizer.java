package com.example.kairo.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Canonical ordering and hashing for rule chains, shared verbatim by the
 * Platform and the Agent so both compute the same hash for the same chain.
 *
 * <p>The canonical ordering is fixed by the V1.4 contract as
 * {@code priority DESC, createdAt ASC, ruleId ASC}. The chain hash is the
 * SHA-256 of a canonical JSON document built from the target identity and the
 * ordered entry list. The document uses sorted keys, escaped strings and no
 * incidental whitespace, so it does not depend on database natural order,
 * {@code ConcurrentHashMap} iteration order, or any serializer configuration.
 *
 * <p>The hash deliberately excludes the monotonic {@code revision} (which
 * changes even when content is unchanged) and the transformation revision
 * (which is the bytecode-layer concern, reconciled separately). Two chains with
 * the same target, same rules and same desired state hash equal regardless of
 * their revision numbers.
 */
public final class RuleChainCanonicalizer {

    private RuleChainCanonicalizer() {
    }

    /**
     * Sort a copy of the entries by the canonical key
     * {@code priority DESC, createdAt ASC, ruleId ASC}.
     */
    public static List<RuleChainEntry> canonicalOrder(List<RuleChainEntry> entries) {
        Objects.requireNonNull(entries, "entries");
        List<RuleChainEntry> copy = new ArrayList<>(entries);
        copy.sort(Comparator
                .comparingInt((RuleChainEntry e) -> e.priority()).reversed()
                .thenComparingLong(RuleChainEntry::createdAtMillis)
                .thenComparing(RuleChainEntry::ruleId));
        return List.copyOf(copy);
    }

    /**
     * Compute the canonical JSON document for a chain. Exposed for auditability
     * so the hash input can be logged and reproduced.
     */
    public static String canonicalJson(EnhancementTarget target, List<RuleChainEntry> orderedEntries,
                                       ChainDesiredState desiredState) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(orderedEntries, "orderedEntries");
        Objects.requireNonNull(desiredState, "desiredState");
        StringBuilder sb = new StringBuilder();
        sb.append("{\"target\":");
        appendTarget(sb, target);
        sb.append(",\"state\":\"").append(desiredState.name()).append("\"");
        sb.append(",\"entries\":[");
        for (int i = 0; i < orderedEntries.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            appendEntry(sb, orderedEntries.get(i));
        }
        sb.append("]}");
        return sb.toString();
    }

    /**
     * SHA-256 hex of the canonical JSON for the given (already-ordered) entries.
     * Callers should pass entries produced by {@link #canonicalOrder(List)}.
     */
    public static String hash(EnhancementTarget target, List<RuleChainEntry> orderedEntries,
                              ChainDesiredState desiredState) {
        String json = canonicalJson(target, orderedEntries, desiredState);
        return sha256Hex(json);
    }

    /**
     * Convenience: canonicalize, then hash. Equivalent to
     * {@code hash(target, canonicalOrder(entries), desiredState)}.
     */
    public static String canonicalHash(EnhancementTarget target, List<RuleChainEntry> entries,
                                       ChainDesiredState desiredState) {
        return hash(target, canonicalOrder(entries), desiredState);
    }

    static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by every JLS-compliant platform.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static void appendTarget(StringBuilder sb, EnhancementTarget target) {
        sb.append("{\"method\":");
        appendMethod(sb, target.method());
        sb.append(",\"location\":\"").append(target.location().name()).append("\"");
        if (target.callSiteSelector() != null) {
            sb.append(",\"callSite\":");
            appendCallSite(sb, target.callSiteSelector());
        }
        sb.append('}');
    }

    private static void appendMethod(StringBuilder sb, MethodSelector method) {
        sb.append("{\"className\":\"").append(escape(method.className())).append("\"");
        sb.append(",\"classLoaderId\":\"").append(escape(nullable(method.classLoaderId()))).append("\"");
        sb.append(",\"methodName\":\"").append(escape(method.methodName())).append("\"");
        sb.append(",\"descriptor\":\"").append(escape(method.methodDescriptor())).append("\"}");
    }

    private static void appendCallSite(StringBuilder sb, CallSiteSelector selector) {
        sb.append("{\"owner\":\"").append(escape(selector.owner())).append("\"");
        sb.append(",\"name\":\"").append(escape(selector.name())).append("\"");
        sb.append(",\"descriptor\":\"").append(escape(selector.descriptor())).append("\"");
        sb.append(",\"opcode\":\"").append(selector.opcode().name()).append("\"");
        sb.append(",\"index\":").append(selector.occurrenceIndex()).append('}');
    }

    private static void appendEntry(StringBuilder sb, RuleChainEntry entry) {
        sb.append("{\"ruleId\":\"").append(escape(entry.ruleId())).append("\"");
        sb.append(",\"version\":").append(entry.version());
        sb.append(",\"priority\":").append(entry.priority());
        sb.append(",\"createdAt\":").append(entry.createdAtMillis());
        sb.append(",\"scriptHash\":\"").append(escape(entry.scriptHash())).append("\"");
        if (entry.mutexGroup() != null) {
            sb.append(",\"mutexGroup\":\"").append(escape(entry.mutexGroup())).append("\"");
        }
        sb.append('}');
    }

    private static String nullable(String value) {
        return value == null ? "" : value;
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = null;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            String rep = switch (c) {
                case '"' -> "\\\"";
                case '\\' -> "\\\\";
                case '\n' -> "\\n";
                case '\r' -> "\\r";
                case '\t' -> "\\t";
                case '\b' -> "\\b";
                case '\f' -> "\\f";
                default -> c < 0x20 ? String.format("\\u%04x", (int) c) : null;
            };
            if (rep != null) {
                if (out == null) {
                    out = new StringBuilder(value.length() + 8);
                    out.append(value, 0, i);
                }
                out.append(rep);
            } else if (out != null) {
                out.append(c);
            }
        }
        return out == null ? value : out.toString();
    }
}
