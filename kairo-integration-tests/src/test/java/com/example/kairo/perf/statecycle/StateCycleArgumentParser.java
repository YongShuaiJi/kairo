package com.example.kairo.perf.statecycle;

import java.util.regex.Pattern;

/**
 * Pure argument parser for {@link StateCycleHarness}. Mirrors the shell-side
 * validation so the harness fails fast with a precise error when invoked
 * directly. The shell runner ({@code run-state-cycle.sh}) performs the same
 * checks before launching the JVM; this parser is the in-process authority for
 * tests and direct invocation.
 *
 * <p>Recognised flags:
 * <ul>
 *   <li>{@code --cycles <N>}   total cycles to distribute across the matrix (>= {@value StateCycleScenarioCatalog#MIN_CYCLES})</li>
 *   <li>{@code --output <dir>} output directory for {@code state-cycle-result.json}</li>
 *   <li>{@code --build-id <40-hex>} resolved HEAD commit recorded in the evidence</li>
 *   <li>{@code --command <text>} exact shell command recorded in the evidence</li>
 *   <li>{@code --jvm-args <args>} fixed JVM args recorded in the evidence</li>
 *   <li>{@code --mode <pr|dev>} evidence mode; {@code pr} forbids a dirty working tree</li>
 *   <li>{@code --working-tree-dirty <true|false>} whether the git working tree was dirty</li>
 *   <li>{@code --help} print usage</li>
 * </ul>
 */
public final class StateCycleArgumentParser {

    public record Options(int cycles, String output, String buildId, String command, String jvmArgs,
                          String mode, boolean workingTreeDirty, boolean help) {
    }

    private static final Pattern HEX40 = Pattern.compile("^[0-9a-f]{40}$");

    private StateCycleArgumentParser() {
    }

    public static Options parse(String... args) {
        int cycles = -1;
        String output = null;
        String buildId = null;
        String command = null;
        String jvmArgs = null;
        String mode = null;
        boolean workingTreeDirty = false;
        boolean help = false;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--cycles" -> {
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException("--cycles requires a value");
                    }
                    cycles = parseCycles(args[++i]);
                }
                case "--output" -> {
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException("--output requires a value");
                    }
                    output = args[++i].trim();
                }
                case "--build-id" -> {
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException("--build-id requires a value");
                    }
                    buildId = args[++i].trim();
                }
                case "--command" -> {
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException("--command requires a value");
                    }
                    command = args[++i];
                }
                case "--jvm-args" -> {
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException("--jvm-args requires a value");
                    }
                    jvmArgs = args[++i];
                }
                case "--mode" -> {
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException("--mode requires a value");
                    }
                    mode = args[++i].trim();
                }
                case "--working-tree-dirty" -> {
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException("--working-tree-dirty requires a value");
                    }
                    workingTreeDirty = parseBoolean(args[++i], "--working-tree-dirty");
                }
                case "--help", "-h" -> help = true;
                default -> throw new IllegalArgumentException("unknown argument: " + a);
            }
        }
        if (help) {
            return new Options(cycles, output, buildId, command, jvmArgs, mode, workingTreeDirty, true);
        }
        if (cycles < 0) {
            throw new IllegalArgumentException("--cycles is required");
        }
        if (cycles < StateCycleScenarioCatalog.MIN_CYCLES) {
            throw new IllegalArgumentException(
                    "--cycles must be >= " + StateCycleScenarioCatalog.MIN_CYCLES
                            + " so every scenario runs at least once (got " + cycles + ")");
        }
        if (output == null || output.isEmpty()) {
            throw new IllegalArgumentException("--output is required");
        }
        if (buildId == null || !HEX40.matcher(buildId).matches()) {
            throw new IllegalArgumentException("--build-id must be a 40-hex lowercase commit id");
        }
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("--command is required");
        }
        if (jvmArgs == null || jvmArgs.isBlank()) {
            throw new IllegalArgumentException("--jvm-args is required");
        }
        if (mode == null) {
            throw new IllegalArgumentException("--mode is required (pr or dev)");
        }
        if (!mode.equals("pr") && !mode.equals("dev")) {
            throw new IllegalArgumentException("--mode must be 'pr' or 'dev' (got: " + mode + ")");
        }
        if (mode.equals("pr") && workingTreeDirty) {
            throw new IllegalArgumentException(
                    "PR evidence refuses a dirty working tree; commit first or use --allow-dirty (dev)");
        }
        return new Options(cycles, output, buildId, command, jvmArgs, mode, workingTreeDirty, false);
    }

    private static boolean parseBoolean(String value, String flag) {
        String trimmed = value.trim();
        if ("true".equals(trimmed)) {
            return true;
        }
        if ("false".equals(trimmed)) {
            return false;
        }
        throw new IllegalArgumentException(flag + " must be 'true' or 'false' (got: " + value + ")");
    }

    private static int parseCycles(String value) {
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("--cycles requires a non-empty integer");
        }
        int parsed;
        try {
            parsed = Integer.parseInt(trimmed);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("--cycles must be an integer (got: " + value + ")");
        }
        if (parsed <= 0) {
            throw new IllegalArgumentException("--cycles must be > 0 (got " + parsed + ")");
        }
        return parsed;
    }
}
