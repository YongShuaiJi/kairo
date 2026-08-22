package com.example.kairo.api.diagnostics;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Small dependency-free formatter for Kairo diagnostic events.
 *
 * <p>The output is deliberately logfmt-like so both humans and log collectors can search it.
 * Values are bounded, single-line and scrubbed before they leave the process. Callers must log
 * identifiers and state, never scripts, request/response bodies, method arguments or credentials.
 */
public final class DiagnosticEvent {

    public static final int MAX_VALUE_LENGTH = 512;
    public static final int MAX_LOG_LINE_LENGTH = 4096;
    private static final String REDACTED = "[REDACTED]";
    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "authorization", "cookie", "credential", "password", "secret", "token",
            "accesstoken", "refreshtoken", "apikey", "script", "scriptsource", "sourcecode",
            "requestbody", "responsebody", "requestpayload", "responsepayload", "payload",
            "arguments", "returnvalue");
    private static final Pattern BEARER = Pattern.compile(
            "(?i)(bearer\\s+)[a-z0-9._~+\\-/]+=*");
    private static final Pattern NAMED_SECRET = Pattern.compile(
            "(?i)(authorization|cookie|credential|password|secret|token|api[-_]?key|access[-_]?token|refresh[-_]?token)"
                    + "(\\s*[:=]\\s*)([^\\s,;]+)");
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}&&[^\\t]]+");

    private DiagnosticEvent() {
    }

    /** Format an event followed by key/value pairs. */
    public static String format(String event, Object... fields) {
        if (fields.length % 2 != 0) {
            throw new IllegalArgumentException("diagnostic fields must be key/value pairs");
        }
        StringBuilder output = new StringBuilder(128)
                .append("event=").append(quoted(sanitize(event)));
        for (int i = 0; i < fields.length; i += 2) {
            String key = normalizeKey(String.valueOf(fields[i]));
            Object value = fields[i + 1];
            StringBuilder field = new StringBuilder(64).append(' ').append(key).append('=');
            if (value == null) {
                field.append("null");
            } else if (value instanceof Number || value instanceof Boolean) {
                field.append(value);
            } else if (isSensitiveKey(key)) {
                field.append(quoted(REDACTED));
            } else {
                field.append(quoted(sanitize(String.valueOf(value))));
            }
            // Preserve complete key/value tokens. Cutting through a quoted value would make the
            // line unparsable, so stop before the field and mark the event as truncated.
            if (output.length() + field.length() > MAX_LOG_LINE_LENGTH - " truncated=true".length()) {
                output.append(" truncated=true");
                break;
            }
            output.append(field);
        }
        return output.toString();
    }

    /** A safe, bounded, single-line failure summary suitable for an in-memory event buffer. */
    public static String failureSummary(Throwable failure) {
        if (failure == null) {
            return "";
        }
        StringBuilder summary = new StringBuilder();
        Throwable cursor = failure;
        int depth = 0;
        while (cursor != null && depth++ < 6) {
            if (!summary.isEmpty()) {
                summary.append(" <- ");
            }
            summary.append(cursor.getClass().getName());
            String message = cursor.getMessage();
            if (message != null && !message.isBlank()) {
                summary.append(": ").append(sanitize(message));
            }
            cursor = cursor.getCause();
        }
        return bound(summary.toString());
    }

    /** Root-cause code locations without exception messages or local values. */
    public static String stackSummary(Throwable failure) {
        if (failure == null) {
            return "";
        }
        Throwable root = failure;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        StringBuilder summary = new StringBuilder(root.getClass().getName());
        StackTraceElement[] trace = root.getStackTrace();
        int included = 0;
        for (StackTraceElement frame : trace) {
            if (included++ >= 8) {
                break;
            }
            summary.append(" at ").append(frame);
        }
        return bound(summary.toString());
    }

    /** Stable short fingerprint for correlating a value without logging the value itself. */
    public static String fingerprint(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                hex.append(String.format(Locale.ROOT, "%02x", digest[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    /** Scrub credentials, control characters and excessive values. */
    public static String sanitize(String value) {
        if (value == null) {
            return "";
        }
        return bound(scrub(value), MAX_VALUE_LENGTH);
    }

    /** Scrub a complete structured log line without applying the shorter per-field limit. */
    public static String sanitizeLogLine(String value) {
        if (value == null) {
            return "";
        }
        return bound(scrub(value), MAX_LOG_LINE_LENGTH);
    }

    private static String scrub(String value) {
        String singleLine = CONTROL.matcher(value).replaceAll(" ")
                .replace('\n', ' ')
                .replace('\r', ' ')
                .replace('\t', ' ');
        singleLine = BEARER.matcher(singleLine).replaceAll("$1" + REDACTED);
        singleLine = NAMED_SECRET.matcher(singleLine).replaceAll("$1$2" + REDACTED);
        return singleLine;
    }

    private static String bound(String value) {
        return bound(value, MAX_VALUE_LENGTH);
    }

    private static String bound(String value, int maxLength) {
        return value.length() <= maxLength
                ? value : value.substring(0, maxLength) + "...";
    }

    private static boolean isSensitiveKey(String key) {
        String compact = key.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
        if (SENSITIVE_KEYS.contains(compact)) {
            return true;
        }
        return compact.endsWith("password") || compact.endsWith("secret")
                || compact.endsWith("token") || compact.endsWith("credential")
                || compact.endsWith("cookie");
    }

    private static String normalizeKey(String key) {
        String normalized = key == null ? "field" : key.replaceAll("[^A-Za-z0-9_.-]", "_");
        return normalized.isBlank() ? "field" : normalized;
    }

    private static String quoted(String value) {
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }
}
