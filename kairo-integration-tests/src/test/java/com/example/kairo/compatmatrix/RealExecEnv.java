package com.example.kairo.compatmatrix;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The real-execution environment provisioned by {@code run-compatibility.sh} for
 * the M3-B plain-Java scenarios (C01/C02/C09). It carries the <em>actual</em>
 * runner host (OS/arch/JDK), the runner's own PID, the repo root, a work
 * directory, the target JDK homes available on this host, the built agent
 * artifact paths, and operation timeouts.
 *
 * <p>{@link #current()} reads these from system properties set by the shell
 * runner and returns {@code null} when real execution is <strong>not</strong>
 * provisioned (e.g. a unit test invoking the runner in-process). In that
 * unprovisioned state the dispatch seam returns truthful fail-closed
 * {@code NOT_RUN} (formal) / {@code EXPERIMENTAL} (C09) evidence and never
 * spawns a process, so the deterministic tests stay process-free.
 *
 * <p>Tests construct a {@code RealExecEnv} directly to drive the gate logic
 * with controlled host/JDK/artifact values.
 */
final class RealExecEnv {

    final String hostOs;          // raw os.name of the runner host
    final String hostArch;        // raw os.arch of the runner host
    final String runnerJdkVersion; // raw java.version of the runner JVM
    final int runnerPid;          // the runner's own PID (independent-PID check)
    final Path repoRoot;
    final Path workDir;
    /** Available target JDK homes keyed by major feature version (17, 21, ...). */
    final Map<Integer, Path> targetJdks;
    final Path bootstrapJar;     // kairo-agent-bootstrap.jar (shaded, Premain/Agent-Class)
    final Path bootstrapApiJar;  // kairo-bootstrap-api jar appended to bootstrap ClassLoader
    final Path coreJar;           // kairo-agent-core-modern.jar (shaded fat core)
    final Path attachJar;         // kairo-attach.jar (shaded, AttachCommand main)
    final long startupTimeoutMillis;
    final long operationTimeoutMillis;

    RealExecEnv(String hostOs, String hostArch, String runnerJdkVersion, int runnerPid,
                Path repoRoot, Path workDir, Map<Integer, Path> targetJdks,
                Path bootstrapJar, Path bootstrapApiJar, Path coreJar, Path attachJar,
                long startupTimeoutMillis, long operationTimeoutMillis) {
        this.hostOs = Objects.requireNonNull(hostOs, "hostOs");
        this.hostArch = Objects.requireNonNull(hostArch, "hostArch");
        this.runnerJdkVersion = Objects.requireNonNull(runnerJdkVersion, "runnerJdkVersion");
        this.runnerPid = runnerPid;
        this.repoRoot = Objects.requireNonNull(repoRoot, "repoRoot");
        this.workDir = Objects.requireNonNull(workDir, "workDir");
        this.targetJdks = Map.copyOf(Objects.requireNonNull(targetJdks, "targetJdks"));
        this.bootstrapJar = bootstrapJar;
        this.bootstrapApiJar = bootstrapApiJar;
        this.coreJar = coreJar;
        this.attachJar = attachJar;
        this.startupTimeoutMillis = startupTimeoutMillis;
        this.operationTimeoutMillis = operationTimeoutMillis;
    }

    /** Reads the env from system properties; {@code null} when not provisioned. */
    static RealExecEnv current() {
        String flag = System.getProperty("kairo.compat.real.exec");
        if (!"true".equalsIgnoreCase(flag)) {
            return null;
        }
        String repoRoot = System.getProperty("kairo.compat.repo.root");
        String workDir = System.getProperty("kairo.compat.work.dir");
        if (isBlank(repoRoot) || isBlank(workDir)) {
            return null;
        }
        Map<Integer, Path> jdks = new LinkedHashMap<>();
        for (int major : new int[]{8, 11, 17, 21}) {
            String home = System.getProperty("kairo.compat.target.jdk." + major);
            if (!isBlank(home) && Files.isDirectory(Path.of(home))) {
                jdks.put(major, Path.of(home));
            }
        }
        Path bootstrap = pathProp("kairo.compat.artifacts.bootstrapJar");
        Path bootstrapApi = pathProp("kairo.compat.artifacts.bootstrapApiJar");
        Path core = pathProp("kairo.compat.artifacts.coreJar");
        Path attach = pathProp("kairo.compat.artifacts.attachJar");
        long startup = longProp("kairo.compat.timeout.startupMillis", 60_000L);
        long operation = longProp("kairo.compat.timeout.operationMillis", 30_000L);
        return new RealExecEnv(
                System.getProperty("os.name", ""),
                System.getProperty("os.arch", ""),
                System.getProperty("java.version", ""),
                (int) ProcessHandle.current().pid(),
                Path.of(repoRoot), Path.of(workDir), jdks,
                bootstrap, bootstrapApi, core, attach, startup, operation);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static Path pathProp(String name) {
        String v = System.getProperty(name);
        return isBlank(v) ? null : Path.of(v);
    }

    private static long longProp(String name, long fallback) {
        String v = System.getProperty(name);
        if (isBlank(v)) {
            return fallback;
        }
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
