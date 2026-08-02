package com.example.kairo.compatmatrix;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * The V1.7 row-evidence runner (entry point of {@code run-compatibility.sh}).
 *
 * <p>Contract/dispatch foundation only (section 10.4.1): it validates the environment,
 * looks up the frozen catalog scenario, and dispatches to a scenario implementation.
 * Because real fixtures land in M3-B through M3-E, in M3-A <strong>no</strong>
 * C01-C10 scenario has an implementation. Every formal scenario therefore fails
 * closed with truthful {@code NOT_RUN} evidence (exit 4) and the experimental C09
 * emits truthful {@code EXPERIMENTAL} evidence (exit 0, non-blocking). It
 * <strong>never</strong> fabricates {@code PASSED}.
 *
 * <p>The dispatch seam is {@link #dispatch(CompatibilityScenario)}. M3-B provides
 * real independent-JVM execution for C01/C02/C09; later bounded work packages plug
 * in the remaining scenarios. Unimplemented or unavailable formal rows fail closed.
 */
public final class CompatibilityRowRunner {

    /** Exit: row produced non-blocking truthful evidence (PASSED or EXPERIMENTAL). */
    public static final int EXIT_OK = 0;
    /** Exit: usage / validation error (incl. dirty PR tree). */
    public static final int EXIT_USAGE = 1;
    /** Exit: build failed (set by the shell runner; the JVM never builds). */
    public static final int EXIT_BUILD = 2;
    /** Exit: runner unusable (e.g. output path is not a writable file location). */
    public static final int EXIT_UNUSABLE = 3;
    /** Exit: row produced blocking non-passed evidence (FAILED / SKIPPED / NOT_RUN). */
    public static final int EXIT_BLOCKED = 4;
    /** Exit: row-write error. */
    public static final int EXIT_WRITE = 5;
    /** Exit: schema-validation failure (the row failed self-validation). */
    public static final int EXIT_SCHEMA = 6;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CompatibilityRowRunner() {
    }

    public static void main(String[] args) {
        int runnerPid = (int) ProcessHandle.current().pid();
        System.exit(runInProcess(args, System.getProperty("os.name"), System.getProperty("os.arch"),
                System.getProperty("java.version"), runnerPid, Instant.now()));
    }

    /**
     * In-process entry point for tests: uses the supplied environment and a fixed
     * clock so it is deterministic without a real JVM.
     */
    static int runInProcess(String[] args, String osName, String osArch, String runnerJdk,
                            int runnerPid, Instant now) {
        CompatibilityCli.RunOptions opts;
        try {
            opts = CompatibilityCli.parseRun(args);
        } catch (IllegalArgumentException e) {
            System.err.println("error: " + e.getMessage());
            return EXIT_USAGE;
        }
        if (opts.help()) {
            printUsage();
            return EXIT_OK;
        }
        String startedAt = now.toString();
        ObjectNode row = buildRow(opts, osName, osArch, runnerJdk, runnerPid, startedAt, now.toString());

        // Self-validate before writing: a malformed row is a harness bug (exit 6), never
        // a silent success.
        List<String> errors = new CompatibilityRowValidator().validate(row);
        if (!errors.isEmpty()) {
            System.err.println("error: row failed self-validation: " + String.join("; ", errors));
            return EXIT_SCHEMA;
        }

        Path output = Path.of(opts.output());
        try {
            Files.createDirectories(output.getParent() != null ? output.getParent() : Path.of("."));
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), row);
        } catch (Exception e) {
            System.err.println("error: failed to write row to " + opts.output() + ": " + e.getMessage());
            return EXIT_WRITE;
        }

        String status = row.get("status").asText();
        System.out.println("==> scenario=" + opts.scenario() + " status=" + status
                + " -> " + opts.output());
        return exitCodeForStatus(status);
    }

    /** Maps a row status to the runner exit code. */
    static int exitCodeForStatus(String status) {
        return switch (status) {
            case "PASSED", "EXPERIMENTAL" -> EXIT_OK;
            case "FAILED", "SKIPPED", "NOT_RUN" -> EXIT_BLOCKED;
            default -> EXIT_UNUSABLE;
        };
    }

    /**
     * Builds a truthful row for the scenario under the M3-A contract. Pure: no I/O,
     * no clock beyond the supplied timestamps. Dispatches via {@link #dispatch} (which
     * is process-free unless the real-exec env is provisioned).
     */
    static ObjectNode buildRow(CompatibilityCli.RunOptions opts, String osName, String osArch,
                               String runnerJdk, int runnerPid, String startedAt, String endedAt) {
        CompatibilityScenario scenario = CompatibilityScenarioCatalog.scenario(opts.scenario());
        return buildRowForResult(opts, scenario, osName, osArch, runnerJdk, runnerPid,
                startedAt, endedAt, dispatch(scenario));
    }

    /**
     * Builds a row from an explicit {@link DispatchResult}. Lets deterministic tests
     * drive a fake executor through {@link PlainJavaScenarioDispatch}, then verify the
     * produced row self-validates - without spawning a real target.
     */
    static ObjectNode buildRowForResult(CompatibilityCli.RunOptions opts, CompatibilityScenario scenario,
                                        String osName, String osArch, String runnerJdk, int runnerPid,
                                        String startedAt, String endedAt, DispatchResult d) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("schemaVersion", CompatibilityScenarioCatalog.SCHEMA_VERSION);
        root.put("catalogVersion", CompatibilityScenarioCatalog.CATALOG_VERSION);
        root.put("scenario", scenario.id());
        root.put("supportLevel", scenario.supportLevel().name());

        ObjectNode cat = root.putObject("catalog");
        cat.put("runnerOs", scenario.runnerOs());
        cat.put("runnerArch", scenario.runnerArch());
        ArrayNode jdks = cat.putArray("targetJdks");
        for (int j : scenario.targetJdks()) {
            jdks.add(j);
        }
        cat.put("loadMode", scenario.loadMode().name());
        cat.put("loadModeRaw", scenario.loadModeRaw());
        cat.put("fixture", scenario.fixture());
        cat.put("requiredBehaviorsRaw", scenario.requiredBehaviorsRaw());
        ArrayNode rb = cat.putArray("requiredBehaviors");
        for (String b : scenario.requiredBehaviors()) {
            rb.add(b);
        }

        root.put("buildId", opts.buildId());

        ObjectNode env = root.putObject("environment");
        env.put("osName", osName == null ? "" : osName);
        env.put("osArch", osArch == null ? "" : osArch);
        env.put("jdkVersion", runnerJdk == null ? "" : runnerJdk);
        env.put("runnerPid", runnerPid);

        ObjectNode tvm = root.putObject("targetJvm");
        tvm.put("pid", d.childPid);
        tvm.put("independent", d.childIndependent);
        tvm.put("jdkVersion", d.targetJdkVersion == null ? "" : d.targetJdkVersion);
        // M3-B real independent-process evidence (blank for fail-closed NOT_RUN/EXPERIMENTAL).
        tvm.put("launchCommand", d.launchCommand);
        tvm.put("attachCommand", d.attachCommand);
        tvm.put("stdoutArtifact", d.stdoutArtifact);
        tvm.put("stderrArtifact", d.stderrArtifact);

        root.put("loadingMode", scenario.loadModeRaw());
        root.put("fixture", scenario.fixture());
        root.put("startedAt", startedAt);
        root.put("endedAt", endedAt);
        root.put("command", opts.command());
        // Provenance: evidence mode + working-tree state (correction 3).
        root.put("mode", opts.mode());
        root.put("workingTreeDirty", opts.workingTreeDirty());

        ArrayNode assertions = root.putArray("assertions");
        for (Assertion a : d.assertions) {
            ObjectNode an = assertions.addObject();
            an.put("name", a.name);
            an.put("passed", a.passed);
            if (a.detail != null) {
                an.put("detail", a.detail);
            }
        }

        root.put("status", d.status);
        root.put("failureReason", d.failureReason == null ? "" : d.failureReason);
        return root;
    }

    /**
     * The dispatch seam. M3-B plugs real plain-Java execution in here for C01
     * (premain), C02 (external attach/agentmain) and C09 (agentmain on macOS
     * arm64). M3-C plugs real Spring Boot 3 executable-jar execution in here for
     * C03 (premain) and C04 (external attach). M3-D plugs real ClassLoader / proxy /
     * lambda-bridge execution in here for C05/C06/C07 (premain). When the shell
     * runner provisions the real-execution environment (system properties), the
     * implemented scenarios launch a genuinely independent target JVM; otherwise
     * they fail closed truthfully without spawning a process, so deterministic
     * tests stay process-free. Scenarios whose fixture has not landed (M3-E) keep
     * the M3-A fail-closed behaviour. It never fabricates PASSED.
     */
    static DispatchResult dispatch(CompatibilityScenario scenario) {
        if (fixtureImplemented(scenario.id())) {
            RealExecEnv env = RealExecEnv.current();
            if (env == null) {
                // Unprovisioned (unit test / no real-exec env): truthful fail-closed.
                if (!scenario.isFormal()) {
                    return new DispatchResult("EXPERIMENTAL",
                            "real-execution environment not provisioned; no real macOS arm64/JDK 21 "
                                    + "runner available; emitted EXPERIMENTAL per section 10.1/10.4.2 (C09 is non-blocking)",
                            List.of(), 0, false, "", "", "", "", "");
                }
                return new DispatchResult("NOT_RUN",
                        "real-execution environment not provisioned; fail-closed per M3-A (section 10.4.1)",
                        List.of(), 0, false, "", "", "", "", "");
            }
            if (isSpringBootScenario(scenario.id())) {
                return SpringBootScenarioDispatch.run(scenario, env);
            }
            if (isM3DScenario(scenario.id())) {
                return M3DScenarioDispatch.run(scenario, env);
            }
            return PlainJavaScenarioDispatch.run(scenario, env);
        }
        if (!scenario.isFormal()) {
            // C09 without a real macOS runner: truthful EXPERIMENTAL, non-blocking.
            return new DispatchResult("EXPERIMENTAL",
                    "no real macOS runner available; emitted EXPERIMENTAL per section 10.1/10.4.2",
                    List.of(), 0, false, "", "", "", "", "");
        }
        return new DispatchResult("NOT_RUN",
                "no fixture implemented for " + scenario.workPackage() + "; fail-closed per M3-A (section 10.4.1)",
                List.of(), 0, false, "", "", "", "", "");
    }

    /**
     * Whether a real fixture implementation exists for the scenario. M3-B flips
     * C01/C02/C09 to true; M3-C flips C03/C04 to true; M3-D flips C05/C06/C07 to true;
     * M3-E flips the rest as their fixtures land.
     */
    static boolean fixtureImplemented(String scenarioId) {
        return "C01".equals(scenarioId) || "C02".equals(scenarioId) || "C09".equals(scenarioId)
                || "C03".equals(scenarioId) || "C04".equals(scenarioId)
                || "C05".equals(scenarioId) || "C06".equals(scenarioId) || "C07".equals(scenarioId);
    }

    /**
     * Whether the scenario is an M3-C Spring Boot executable-jar target (C03/C04),
     * routed to {@link SpringBootScenarioDispatch} rather than the M3-B plain-Java
     * dispatch.
     */
    static boolean isSpringBootScenario(String scenarioId) {
        return "C03".equals(scenarioId) || "C04".equals(scenarioId);
    }

    /**
     * Whether the scenario is an M3-D ClassLoader / proxy / lambda-bridge target
     * (C05/C06/C07), routed to {@link M3DScenarioDispatch} rather than the M3-B
     * plain-Java dispatch.
     */
    static boolean isM3DScenario(String scenarioId) {
        return "C05".equals(scenarioId) || "C06".equals(scenarioId) || "C07".equals(scenarioId);
    }

    private static void printUsage() {
        System.out.println("""
                Usage: CompatibilityRowRunner --scenario <C01-C10> --output <row.json>
                        --build-id <40-hex> --command <text> --mode <pr|dev>
                        --working-tree-dirty <true|false> [--help]

                Produces one V1.7 compatibility row-evidence JSON file. M3-B implements
                real independent-JVM execution for C01/C02/C09; M3-C implements C03/C04
                against a Spring Boot 3 executable jar; M3-D implements C05 (parent/child
                same-name loaders), C06 (JDK Proxy/CGLIB/Byte Buddy) and C07
                (lambda/bridge/synthetic on JDK 17 and 21). Unavailable or later-package
                scenarios fail closed. It never fabricates PASSED.

                Exit codes:
                  0  row produced non-blocking truthful evidence (PASSED or EXPERIMENTAL)
                  1  usage / validation error (incl. dirty PR tree)
                  2  build failed (set by the shell runner)
                  3  runner unusable
                  4  blocking non-passed evidence (FAILED / SKIPPED / NOT_RUN)
                  5  row-write error
                  6  schema-validation failure
                """);
    }

    /**
     * The result of dispatching one scenario. {@code launchCommand}/{@code attachCommand}
     * and {@code stdoutArtifact}/{@code stderrArtifact} carry the real independent-process
     * evidence produced by M3-B plain-Java execution (blank for M3-A fail-closed rows).
     */
    record DispatchResult(String status, String failureReason, List<Assertion> assertions,
                          int childPid, boolean childIndependent, String targetJdkVersion,
                          String launchCommand, String attachCommand,
                          String stdoutArtifact, String stderrArtifact) {
        public DispatchResult {
            failureReason = failureReason == null ? "" : failureReason;
            targetJdkVersion = targetJdkVersion == null ? "" : targetJdkVersion;
            launchCommand = launchCommand == null ? "" : launchCommand;
            attachCommand = attachCommand == null ? "" : attachCommand;
            stdoutArtifact = stdoutArtifact == null ? "" : stdoutArtifact;
            stderrArtifact = stderrArtifact == null ? "" : stderrArtifact;
        }
    }

    /** One assertion in a row. */
    record Assertion(String name, boolean passed, String detail) {
    }
}
