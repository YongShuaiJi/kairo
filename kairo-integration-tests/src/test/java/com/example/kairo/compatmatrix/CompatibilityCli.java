package com.example.kairo.compatmatrix;

import java.util.regex.Pattern;

/**
 * Pure argument parser for the three M3-A compatibility mains
 * ({@code CompatibilityRowRunner}, {@code CompatibilityAggregatorMain},
 * {@code CompatibilityVerifierMain}). Mirrors the shell-side validation so a main
 * fails fast with a precise error when invoked directly. Pure and deterministic;
 * no I/O.
 *
 * <p>The shell runners perform the same checks before launching the JVM; this parser
 * is the in-process authority for tests and direct invocation.
 */
public final class CompatibilityCli {

    private static final Pattern HEX40 = Pattern.compile("^[0-9a-f]{40}$");

    private CompatibilityCli() {
    }

    /** Options for {@code run-compatibility.sh}. */
    public record RunOptions(String scenario, String output, String buildId, String command,
                             String mode, boolean workingTreeDirty, boolean help) {
    }

    /** Options for {@code aggregate-compatibility.sh}. */
    public record AggregateOptions(String input, String output, String command, boolean help) {
    }

    /** Options for {@code verify-compatibility.sh}. */
    public record VerifyOptions(String resultFile, boolean help) {
    }

    public static RunOptions parseRun(String... args) {
        String scenario = null;
        String output = null;
        String buildId = null;
        String command = null;
        String mode = null;
        boolean workingTreeDirty = false;
        boolean help = false;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--scenario" -> {
                    if (i + 1 >= args.length) throw new IllegalArgumentException("--scenario requires a value");
                    scenario = args[++i].trim();
                }
                case "--output" -> {
                    if (i + 1 >= args.length) throw new IllegalArgumentException("--output requires a value");
                    output = args[++i].trim();
                }
                case "--build-id" -> {
                    if (i + 1 >= args.length) throw new IllegalArgumentException("--build-id requires a value");
                    buildId = args[++i].trim();
                }
                case "--command" -> {
                    if (i + 1 >= args.length) throw new IllegalArgumentException("--command requires a value");
                    command = args[++i];
                }
                case "--mode" -> {
                    if (i + 1 >= args.length) throw new IllegalArgumentException("--mode requires a value");
                    mode = args[++i].trim();
                }
                case "--working-tree-dirty" -> {
                    if (i + 1 >= args.length) throw new IllegalArgumentException("--working-tree-dirty requires a value");
                    workingTreeDirty = parseBoolean(args[++i], "--working-tree-dirty");
                }
                case "--help", "-h" -> help = true;
                default -> throw new IllegalArgumentException("unknown argument: " + a);
            }
        }
        if (help) {
            return new RunOptions(scenario, output, buildId, command, mode, workingTreeDirty, true);
        }
        if (scenario == null || scenario.isBlank()) {
            throw new IllegalArgumentException("--scenario is required (C01-C10)");
        }
        if (!CompatibilityScenarioCatalog.isKnownScenario(scenario)) {
            throw new IllegalArgumentException("--scenario must be a known C01-C10 (got: " + scenario + ")");
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
        return new RunOptions(scenario, output, buildId, command, mode, workingTreeDirty, false);
    }

    public static AggregateOptions parseAggregate(String... args) {
        String input = null;
        String output = null;
        String command = null;
        boolean help = false;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--input" -> {
                    if (i + 1 >= args.length) throw new IllegalArgumentException("--input requires a value");
                    input = args[++i].trim();
                }
                case "--output" -> {
                    if (i + 1 >= args.length) throw new IllegalArgumentException("--output requires a value");
                    output = args[++i].trim();
                }
                case "--command" -> {
                    if (i + 1 >= args.length) throw new IllegalArgumentException("--command requires a value");
                    command = args[++i];
                }
                case "--help", "-h" -> help = true;
                default -> throw new IllegalArgumentException("unknown argument: " + a);
            }
        }
        if (help) {
            return new AggregateOptions(input, output, command, true);
        }
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("--input is required (directory of row JSON)");
        }
        if (output == null || output.isEmpty()) {
            throw new IllegalArgumentException("--output is required");
        }
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("--command is required");
        }
        return new AggregateOptions(input, output, command, false);
    }

    public static VerifyOptions parseVerify(String... args) {
        String resultFile = null;
        boolean help = false;
        for (String a : args) {
            switch (a) {
                case "--help", "-h" -> help = true;
                default -> {
                    if (a.startsWith("--")) {
                        throw new IllegalArgumentException("unknown argument: " + a);
                    }
                    if (resultFile != null) {
                        throw new IllegalArgumentException("only one result file argument is allowed");
                    }
                    resultFile = a.trim();
                }
            }
        }
        if (help) {
            return new VerifyOptions(resultFile, true);
        }
        if (resultFile == null || resultFile.isBlank()) {
            throw new IllegalArgumentException("a result.json path is required");
        }
        return new VerifyOptions(resultFile, false);
    }

    private static boolean parseBoolean(String value, String flag) {
        String trimmed = value.trim();
        if ("true".equals(trimmed)) return true;
        if ("false".equals(trimmed)) return false;
        throw new IllegalArgumentException(flag + " must be 'true' or 'false' (got: " + value + ")");
    }
}
