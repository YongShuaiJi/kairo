package com.example.kairo.perf.leak;

import java.util.List;

/**
 * Single source of truth for the M2-C leak-check scenario matrix (&sect;9.3).
 *
 * <p>The matrix is the cyclic leak-surface scenarios. Each scenario is one
 * real create / enhance / invoke / unload / close lifecycle that exercises a distinct
 * resource-leak surface, distributed across the requested cycle budget. The harness,
 * the result validator and the shell runner all key off the ids declared here so the
 * evidence cannot drift from the matrix.
 *
 * <p>{@link #distribute(int)} allocates a requested cycle budget across the
 * scenarios deterministically (each receives at least one cycle). {@code --cycles 500}
 * is therefore 500 total cycles distributed across the matrix, not 500 repetitions of
 * every scenario.
 *
 * <p>Byte Buddy <em>generated</em> classes get their own scenario
 * ({@code bytebuddy-generated}): a class whose bytes are produced by Byte Buddy's
 * subclass generator, loaded into an unloadable business ClassLoader, classified,
 * enhanced, invoked and unloaded. This is distinct from the ordinary Byte Buddy
 * <em>retransformation</em> the agent performs on every scenario (retransforming an
 * existing class is not the same as exercising a Byte Buddy generated target class).
 */
public final class LeakScenarioCatalog {

    /** Minimum cycle count: every scenario must run at least once. */
    public static final int MIN_CYCLES = 6;

    public record Scenario(String id, String category, String description, String leakSurface) {
    }

    private static final List<Scenario> SCENARIOS = List.of(
            new Scenario("business-classloader", "classloader",
                    "unloadable business URLClassLoader with a Groovy return-replacement rule; enhance, invoke, unload, close",
                    "unloadable business ClassLoader + Groovy script ClassLoader + rule registry + snapshot + journal"),
            new Scenario("jdk-proxy", "proxy",
                    "JDK dynamic proxy generated in a business ClassLoader; enhance the backing target through the proxy",
                    "JDK Proxy generated class + Byte Buddy retransform + Groovy compile cache"),
            new Scenario("lambda-bridge-synthetic", "synthetic",
                    "loader holding lambda/synthetic hidden classes and a generic bridge method; classify and enhance the real target",
                    "Lambda + bridge + synthetic classification (SyntheticBridgePolicy) + Byte Buddy retransform"),
            new Scenario("groovy-compile-cache", "groovy",
                    "one loader compiling a batch of distinct Groovy scripts (cache miss) plus repeats (cache hit) before unload",
                    "Groovy script ClassLoader + compile cache + generation holder"),
            new Scenario("cglib-detection", "proxy",
                    "real loaded class carrying the CGLIB name marker; classify via ProxyTargetAnalyzer then enhance",
                    "CGLIB classification path (detection-only; no CGLIB runtime dependency) + Byte Buddy retransform"),
            new Scenario("bytebuddy-generated", "proxy",
                    "genuinely Byte Buddy-generated subclass defined in an unloadable business ClassLoader; classify, enhance, invoke, unload",
                    "Byte Buddy generated target class (subclass generation, not retransform) + defining ClassLoader + Groovy compile cache"));

    private LeakScenarioCatalog() {
    }

    public static List<Scenario> all() {
        return SCENARIOS;
    }

    public static List<String> ids() {
        return SCENARIOS.stream().map(Scenario::id).toList();
    }

    public static Scenario get(String id) {
        return SCENARIOS.stream().filter(s -> s.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown leak scenario: " + id));
    }

    /**
     * Distribute {@code cycles} across the matrix in catalog order. Each scenario
     * receives at least one cycle; the remainder is spread over the first scenarios so
     * the split is deterministic and stable.
     *
     * @throws IllegalArgumentException if {@code cycles < MIN_CYCLES}
     */
    public static int[] distribute(int cycles) {
        if (cycles < MIN_CYCLES) {
            throw new IllegalArgumentException(
                    "cycles must be >= " + MIN_CYCLES + " so every scenario runs at least once (got " + cycles + ")");
        }
        int count = SCENARIOS.size();
        int base = cycles / count;
        int remainder = cycles % count;
        int[] result = new int[count];
        for (int i = 0; i < count; i++) {
            result[i] = base + (i < remainder ? 1 : 0);
        }
        return result;
    }
}
