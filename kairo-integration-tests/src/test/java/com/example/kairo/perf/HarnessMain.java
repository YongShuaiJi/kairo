package com.example.kairo.perf;

import com.example.kairo.agent.core.AgentRuntime;
import com.example.demo.perf.BenchmarkTarget;
import com.example.kairo.api.InvokePhase;
import com.example.kairo.api.MethodSelector;
import com.example.kairo.api.MockRule;
import com.example.kairo.core.ClassLoaderIdentity;
import com.example.kairo.core.CompiledRule;
import com.example.kairo.core.MethodDescriptor;
import com.example.kairo.perf.ScenarioCatalog.Scenario;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.bytebuddy.agent.ByteBuddyAgent;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The forked benchmark process. One process runs exactly one scenario for one
 * fork: it prepares the scenario (optionally loading the Agent and publishing
 * rules), runs warmup iterations, then runs measurement iterations recording one
 * raw sample (ns/op) per iteration, and writes the raw samples to a JSON file.
 *
 * <p>Each fork is a fresh JVM (spawned by {@code run-performance.sh}), so the
 * 5-fork PR isolation is real process isolation with fixed JVM arguments — not a
 * single-shot {@code System.nanoTime()} assertion. Every raw sample is saved.
 *
 * <p>The harness also runs a correctness check after measurement (e.g. a BEFORE
 * hit scenario must show {@code hits >= 1}; a miss scenario must show
 * {@code hits == 0} with the method nonetheless enhanced). A failed check writes
 * an error file and exits non-zero — the harness never reports a measurement it
 * cannot prove was the intended scenario.
 *
 * <p>Exit codes: 0 success; 2 bad arguments; 3 scenario setup/measure error;
 * 4 correctness-check failure.
 */
public final class HarnessMain {

    @FunctionalInterface
    interface OpRunner {
        void run(int ops);
    }

    /** A prepared scenario: the measured batch runner and a post-measure correctness check. */
    record Prepared(OpRunner runner, Runnable check) { }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private long blackhole; // defeats dead-code elimination of the measured calls

    public static void main(String[] args) {
        try {
            new HarnessMain().run(args);
        } catch (IllegalArgumentException e) {
            System.err.println("harness argument error: " + e.getMessage());
            System.exit(2);
        } catch (Throwable t) {
            // Write an error marker alongside the output so the reporter can surface it.
            t.printStackTrace(System.err);
            System.exit(3);
        }
    }

    private void run(String[] args) throws Exception {
        Map<String, String> opts = parse(args);
        if (opts.containsKey("list")) {
            for (String id : ScenarioCatalog.ids()) {
                System.out.println(id);
            }
            return;
        }
        String scenarioId = required(opts, "scenario");
        int warmup = Integer.parseInt(required(opts, "warmup"));
        int measure = Integer.parseInt(required(opts, "measure"));
        int fork = Integer.parseInt(required(opts, "fork"));
        String out = required(opts, "out");
        String buildId = opts.getOrDefault("build-id", "unknown");
        String buildLabel = opts.getOrDefault("build-label", "unknown");

        Scenario scenario = ScenarioCatalog.get(scenarioId);
        int ops = scenario.opsPerIteration();

        Prepared prepared = prepare(scenarioId);

        // Warmup (discard timing).
        for (int i = 0; i < warmup; i++) {
            prepared.runner.run(ops);
        }

        // Measurement: one sample per iteration.
        double[] samples = new double[measure];
        for (int i = 0; i < measure; i++) {
            long start = System.nanoTime();
            prepared.runner.run(ops);
            long end = System.nanoTime();
            samples[i] = (end - start) / (double) ops;
        }

        // Correctness check before closing the agent (closes may reset counters).
        try {
            prepared.check.run();
        } catch (AssertionError e) {
            writeError(out, "correctness check failed: " + e.getMessage());
            System.err.println("CORRECTNESS FAILURE: " + e.getMessage());
            System.exit(4);
        }

        // Ensure the blackhole is observable to the JIT as a side effect.
        if (blackhole == Long.MIN_VALUE) {
            throw new AssertionError("unreachable");
        }

        writeRaw(out, scenario, fork, buildId, buildLabel, warmup, measure, ops, samples);
        System.exit(0);
    }

    // ------------------------------------------------------------------ scenarios

    private Prepared prepare(String id) throws Exception {
        BenchmarkTarget target = new BenchmarkTarget();
        Method score = BenchmarkTarget.class.getMethod("score", int.class);
        Method scoreThrows = BenchmarkTarget.class.getMethod("scoreThrows", int.class);

        return switch (id) {
            case "no-agent-baseline" -> new Prepared(
                    ops -> {
                        long acc = 0;
                        for (int i = 0; i < ops; i++) {
                            acc += target.score(i);
                        }
                        blackhole += acc;
                    },
                    () -> assertCond(target.score(5) == 10, "no-agent score(5)=10"));
            case "agent-loaded-not-enhanced" -> {
                AgentRuntime rt = startAgent();
                yield new Prepared(
                        ops -> {
                            long acc = 0;
                            for (int i = 0; i < ops; i++) {
                                acc += target.score(i);
                            }
                            blackhole += acc;
                        },
                        () -> {
                            assertCond(rt.rules().isEmpty(), "no rules published");
                            assertCond(target.score(5) == 10, "not enhanced, score(5)=10");
                        });
            }
            case "enhanced-rule-not-matched" -> {
                AgentRuntime rt = startAgent();
                long retransformBefore = rt.transformerManager().retransformCount();
                CompiledRule r = rt.publish(score, rule("miss-0", score, InvokePhase.BEFORE,
                        "return mock.proceed()", 0, 100));
                yield new Prepared(
                        ops -> {
                            long acc = 0;
                            for (int i = 0; i < ops; i++) {
                                acc += target.score(i);
                            }
                            blackhole += acc;
                        },
                        () -> {
                            assertCond(rt.transformerManager().retransformCount() > retransformBefore,
                                    "method was enhanced (retransformed)");
                            assertCond(r.hits() == 0, "percentage=0 rule never fired (hits=0)");
                            assertCond(target.score(5) == 10, "original body ran (score(5)=10)");
                        });
            }
            case "before-hit" -> singleBeforeHit(
                    target, score, "return mock.proceed()", 100, "before-hit", 10);
            case "groovy-noop" -> singleBeforeHit(
                    target, score, "return mock.proceed()", 100, "groovy-noop", 10);
            case "groovy-arg-read" -> singleBeforeHit(target, score,
                    "def a = args[0]; def n = method.name; return mock.proceed()",
                    100, "groovy-arg-read", 10);
            case "groovy-return-replace" -> singleBeforeHit(target, score,
                    "return mock.returnValue(args[0] + 100)",
                    100, "groovy-return-replace", 105);
            case "return-hit" -> {
                AgentRuntime rt = startAgent();
                CompiledRule r = rt.publish(score, rule("return-hit", score, InvokePhase.RETURN,
                        "return mock.returnValue(result + 1)", 100, 100));
                yield new Prepared(
                        ops -> {
                            long acc = 0;
                            for (int i = 0; i < ops; i++) {
                                acc += target.score(i);
                            }
                            blackhole += acc;
                        },
                        () -> {
                            assertCond(r.hits() >= 1, "return rule fired");
                            assertCond(target.score(5) == 11, "return replaced (5*2+1=11)");
                        });
            }
            case "throws-hit" -> {
                AgentRuntime rt = startAgent();
                CompiledRule r = rt.publish(scoreThrows, rule("throws-hit", scoreThrows, InvokePhase.THROWS,
                        "return mock.returnValue(args[0] + 1)", 100, 100));
                yield new Prepared(
                        ops -> {
                            long acc = 0;
                            for (int i = 0; i < ops; i++) {
                                acc += target.scoreThrows(i);
                            }
                            blackhole += acc;
                        },
                        () -> {
                            assertCond(r.hits() >= 1, "throws rule fired");
                            assertCond(target.scoreThrows(5) == 6, "throw converted to return (5+1=6)");
                        });
            }
            case "chain-1" -> chainScenario(target, score, 1);
            case "chain-5" -> chainScenario(target, score, 5);
            case "chain-20" -> chainScenario(target, score, 20);
            case "inventory-query" -> {
                AgentRuntime rt = startAgent();
                // Publish a volume of rules so rules() and the chain snapshot have real inventory.
                for (int i = 0; i < 50; i++) {
                    rt.publish(score, rule("inv-r" + i, score, InvokePhase.BEFORE,
                            "return mock.proceed()", 100, 1000 - i));
                }
                yield new Prepared(
                        ops -> {
                            long acc = 0;
                            for (int i = 0; i < ops; i++) {
                                acc += rt.rules().size();
                                acc += rt.searchClasses("", 500).size();
                                acc += rt.classLoaderRepository().liveLoaders().size();
                            }
                            blackhole += acc;
                        },
                        () -> assertCond(rt.rules().size() >= 50, "inventory has >=50 rules"));
            }
            case "event-buffer-full-drop" -> {
                AgentRuntime rt = startAgent();
                // Pre-fill the bounded RuntimeEventBuffer (default capacity 1000) so every
                // measured record drops the oldest — the drop-oldest path. RuntimeEventBuffer
                // is a bounded ArrayDeque with NO blocking backpressure: when full it silently
                // evicts the oldest entry. We prove that concretely:
                //   (a) after prefill the buffer is exactly at capacity (1000), and
                //   (b) after measuring, no prefill event remains — every measured record
                //       evicted a prefill entry (drop-oldest), and the buffer stayed at capacity.
                final int capacity = 1000;
                for (int i = 0; i < capacity; i++) {
                    rt.recordEvent("perf.prefill", "perf", null, null, "prefill-" + i);
                }
                yield new Prepared(
                        ops -> {
                            for (int i = 0; i < ops; i++) {
                                rt.recordEvent("perf.measure", "perf", null, null, "measured-" + i);
                            }
                            blackhole += rt.events().size();
                        },
                        () -> {
                            List<com.example.kairo.agent.core.RuntimeEvent> events = rt.events();
                            assertCond(events.size() == capacity,
                                    "buffer exactly bounded (==" + capacity + "): " + events.size());
                            boolean anyPrefill = events.stream()
                                    .anyMatch(e -> "perf.prefill".equals(e.type()));
                            assertCond(!anyPrefill,
                                    "all prefill events evicted by measured records (drop-oldest)");
                            boolean allMeasured = events.stream()
                                    .allMatch(e -> "perf.measure".equals(e.type()));
                            assertCond(allMeasured,
                                    "all remaining events are measured records (drop-oldest replaced head)");
                        });
            }
            default -> throw new IllegalArgumentException("unknown scenario: " + id);
        };
    }

    /** A single BEFORE rule that proceeds, used by before-hit and the groovy-* series. */
    private Prepared singleBeforeHit(BenchmarkTarget target, Method score,
                                     String script, int percentage, String ruleId,
                                     int expectedAtFive) throws Exception {
        AgentRuntime rt = startAgent();
        CompiledRule r = rt.publish(score, rule(ruleId, score, InvokePhase.BEFORE, script, percentage, 100));
        return new Prepared(
                ops -> {
                    long acc = 0;
                    for (int i = 0; i < ops; i++) {
                        acc += target.score(i);
                    }
                    blackhole += acc;
                },
                () -> {
                    assertCond(r.hits() >= 1, ruleId + " rule fired");
                    assertCond(target.score(5) == expectedAtFive,
                            ruleId + " behavior is correct: score(5)=" + expectedAtFive);
                });
    }

    /** N BEFORE proceed rules on the same method; measures per-rule chain iteration. */
    private Prepared chainScenario(BenchmarkTarget target, Method score, int n) throws Exception {
        AgentRuntime rt = startAgent();
        List<CompiledRule> rules = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            rules.add(rt.publish(score, rule("chain-" + n + "-r" + i, score, InvokePhase.BEFORE,
                    "return mock.proceed()", 100, 1000 - i)));
        }
        return new Prepared(
                ops -> {
                    long acc = 0;
                    for (int i = 0; i < ops; i++) {
                        acc += target.score(i);
                    }
                    blackhole += acc;
                },
                () -> {
                    for (int i = 0; i < rules.size(); i++) {
                        assertCond(rules.get(i).hits() >= 1,
                                "chain-" + n + " rule " + i + " fired");
                    }
                    assertCond(target.score(5) == 10,
                            "chain-" + n + " preserves original behavior: score(5)=10");
                });
    }

    private AgentRuntime startAgent() {
        Instrumentation instrumentation = ByteBuddyAgent.install();
        AgentRuntime runtime = new AgentRuntime(instrumentation);
        runtime.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                runtime.close();
            } catch (RuntimeException ignored) {
                // best-effort cleanup on fork exit
            }
        }));
        return runtime;
    }

    private static MockRule rule(String id, Method method, InvokePhase phase,
                                 String script, int percentage, int priority) {
        return MockRule.builder()
                .id(id)
                .name(id)
                .target(MethodSelector.builder()
                        .className(method.getDeclaringClass().getName())
                        .classLoaderId(ClassLoaderIdentity.idOf(method.getDeclaringClass().getClassLoader()))
                        .methodName(method.getName())
                        .methodDescriptor(MethodDescriptor.of(method))
                        .build())
                .phase(phase)
                .script(script)
                .priority(priority)
                .percentage(percentage)
                .failOpen(true)
                .enabled(true)
                .build();
    }

    // ------------------------------------------------------------------ output

    private void writeRaw(String outPath, Scenario scenario, int fork, String buildId,
                          String buildLabel, int warmup, int measure, int ops, double[] samples) throws Exception {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("scenario", scenario.id());
        root.put("fork", fork);
        root.put("buildId", buildId);
        root.put("buildLabel", buildLabel);
        root.put("opsPerIteration", ops);
        root.put("opsLabel", scenario.opsLabel());
        root.put("warmupIterations", warmup);
        root.put("measurementIterations", measure);
        ArrayNode arr = root.putArray("samples");
        for (double s : samples) {
            arr.add(s);
        }
        root.set("environment", environment());
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(new java.io.File(outPath), root);
    }

    private void writeError(String outPath, String message) throws Exception {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("error", message);
        root.put("phase", "harness");
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(new java.io.File(outPath), root);
    }

    private static ObjectNode environment() {
        ObjectNode env = MAPPER.createObjectNode();
        env.put("jdkVersion", System.getProperty("java.version"));
        env.put("javaVmName", System.getProperty("java.vm.name"));
        env.put("osName", System.getProperty("os.name"));
        env.put("osArch", System.getProperty("os.arch"));
        env.put("osVersion", System.getProperty("os.version"));
        env.put("availableProcessors", Runtime.getRuntime().availableProcessors());
        env.put("maxHeapBytes", Runtime.getRuntime().maxMemory());
        return env;
    }

    // ------------------------------------------------------------------ helpers

    private static void assertCond(boolean cond, String message) {
        if (!cond) {
            throw new AssertionError(message);
        }
    }

    private static String required(Map<String, String> opts, String key) {
        String v = opts.get(key);
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("missing required --" + key);
        }
        return v;
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> opts = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.startsWith("--")) {
                String key = a.substring(2);
                if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                    opts.put(key, args[++i]);
                } else {
                    opts.put(key, "true");
                }
            }
        }
        return opts;
    }
}
