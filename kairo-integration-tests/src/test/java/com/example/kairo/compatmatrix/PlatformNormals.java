package com.example.kairo.compatmatrix;

import java.util.Locale;

/**
 * Normalizes raw {@code os.name}/{@code os.arch} system properties to the
 * catalog's normalized forms so row evidence can be checked against the frozen
 * runner OS/arch without depending on a specific JVM string spelling
 * (e.g. {@code amd64} and {@code x86_64} both mean x86_64; {@code aarch64} and
 * {@code arm64} both mean arm64; {@code Mac OS X} and {@code macOS} both mean
 * macOS).
 *
 * <p>Pure and deterministic; no I/O.
 */
public final class PlatformNormals {

    private PlatformNormals() {
    }

    /** Normalized OS label used by the catalog: {@code Linux} or {@code macOS}. */
    public static String normalizeOs(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.contains("linux")) {
            return "Linux";
        }
        if (s.contains("mac") || s.contains("darwin")) {
            return "macOS";
        }
        if (s.contains("windows")) {
            return "Windows";
        }
        return raw.trim();
    }

    /** Normalized arch label used by the catalog: {@code x86_64} or {@code arm64}. */
    public static String normalizeArch(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.equals("x86_64") || s.equals("amd64") || s.equals("x64")) {
            return "x86_64";
        }
        if (s.equals("aarch64") || s.equals("arm64") || s.equals("aarch64_be")) {
            return "arm64";
        }
        if (s.equals("x86") || s.equals("i386") || s.equals("i486") || s.equals("i586") || s.equals("i686")) {
            return "x86";
        }
        return raw.trim();
    }

    /** Parses a JDK feature/version string to its major version (17, 21, ...). */
    public static int majorJdk(String raw) {
        if (raw == null || raw.isBlank()) {
            return -1;
        }
        String s = raw.trim();
        // "1.8" -> 8; "17.0.11" -> 17; "21" -> 21.
        if (s.startsWith("1.")) {
            String sub = s.substring(2);
            int dot = sub.indexOf('.');
            String num = dot < 0 ? sub : sub.substring(0, dot);
            try {
                return Integer.parseInt(num);
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        int dot = s.indexOf('.');
        String head = dot < 0 ? s : s.substring(0, dot);
        // Handle "17-ea" style.
        int dash = head.indexOf('-');
        if (dash >= 0) {
            head = head.substring(0, dash);
        }
        try {
            return Integer.parseInt(head);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
