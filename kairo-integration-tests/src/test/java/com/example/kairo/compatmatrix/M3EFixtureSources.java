package com.example.kairo.compatmatrix;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * The controlled plain-Java fixture sources for the M3-E compatibility scenarios
 * (C08 redefine/retransform/hot-update drift, C10 coexistence with one in-repo
 * Byte Buddy Agent). Mirrors {@link M3DFixtureSources}/{@link PlainJavaFixtureSource}:
 * the sources are plain Java with <strong>no</strong> Kairo dependency, compiled with
 * the target JDK's {@code javac} into an isolated class directory and launched as an
 * independent JVM, so the matrix exercises the real agent premain against representative
 * targets rather than classes that secretly share the agent ClassLoader.
 *
 * <p>C08 needs the harness to call {@code Instrumentation.redefineClasses} and
 * {@code retransformClasses} <strong>for real</strong> (a mock {@link
 * java.lang.instrument.Instrumentation} or an in-memory-only hash comparison does not
 * count toward acceptance, section 10.4.5). The harness is therefore itself a
 * {@code Premain-Class} javaagent that captures the real {@link
 * java.lang.instrument.Instrumentation} at premain and exposes it to {@code main};
 * its manifest declares {@code Can-Redefine-Classes}/{@code Can-Retransform-Classes}.
 *
 * <p>C10 needs a single repository-controlled Byte Buddy Agent fixture that transforms
 * {@code CoexistTarget.tag()} via genuine Byte Buddy {@code Advice}; the Kairo agent
 * loads second and the matrix proves Kairo enhance/update/unload does not remove or break
 * the Byte Buddy transform. The BB agent source references the real {@code net.bytebuddy}
 * runtime (resolved onto the independent target JVM classpath), never a class-name marker.
 */
final class M3EFixtureSources {

    // --------------------------------------------------------------- C08: redefine/retransform
    /** The target class Kairo enhances and that the harness externally redefines/retransforms. */
    static final String C08_TARGET_CLASS = "DriftTarget";
    /** The harness: a Premain-Class javaagent (captures Instrumentation) and the main class. */
    static final String C08_HARNESS_CLASS = "DriftHarness";
    static final int C08_BASELINE = 10;     // score(5) = 5*2
    static final int C08_ENHANCED_1 = 42;    // first Kairo rule
    static final int C08_ENHANCED_2 = 77;    // Kairo hot update (safe reconciliation)
    static final int C08_V2_FACTOR = 3;      // redefined body: score(x) = x*3
    static final int C08_V2_SCORE = 15;      // score(5) on redefined body, un-enhanced

    /** DriftTarget v1: {@code score(int x){ return x*2; }}. Kairo anchors against these bytes. */
    static final String C08_TARGET_SOURCE = """
            // Plain-Java fixture class redefined/retransformed externally (C08). No Kairo dep.
            public class DriftTarget {
                public int score(int x) {
                    return x * 2;
                }
            }
            """;

    /**
     * DriftTarget v2: a different method body (same signature) used for the real
     * {@code redefineClasses}. Different bytes -> the agent's redefine listener hashes a
     * changed input and lands on TARGET_DRIFTED (section 10.4.5).
     */
    static final String C08_TARGET_V2_SOURCE = """
            // DriftTarget v2: redefined body (same signature). Real redefineClasses input.
            public class DriftTarget {
                public int score(int x) {
                    return x * %d;
                }
            }
            """.formatted(C08_V2_FACTOR);

    /**
     * DriftHarness: a Premain-Class javaagent that captures the real {@link
     * java.lang.instrument.Instrumentation} and is also the main class. Drives invocation
     * over stdin and performs <strong>real</strong> {@code redefineClasses} /
     * {@code retransformClasses} on DriftTarget when commanded. The harness reports
     * {@code canRedefine}/{@code canRetransform} in READY so a fake/mock Instrumentation
     * cannot masquerade as the real capability.
     */
    static final String C08_HARNESS_SOURCE = """
            import java.io.BufferedReader;
            import java.io.InputStreamReader;
            import java.lang.instrument.ClassDefinition;
            import java.lang.instrument.Instrumentation;
            import java.lang.reflect.Method;
            import java.nio.file.Files;
            import java.nio.file.Path;

            // C08 harness: real-Instrumentation agent + main. No Kairo dependency.
            public class DriftHarness {
                private static volatile Instrumentation INST;

                public static void premain(String agentArgs, Instrumentation inst) {
                    INST = inst;
                    System.out.println("HARNESS_AGENT_INSTALLED");
                    System.out.flush();
                }

                public static void main(String[] args) throws Exception {
                    Class<?> target = Class.forName("DriftTarget");
                    Object instance = target.getDeclaredConstructor().newInstance();
                    Method score = target.getMethod("score", int.class);
                    boolean canRedefine = INST != null && INST.isRedefineClassesSupported();
                    boolean canRetransform = INST != null && INST.isRetransformClassesSupported();
                    System.out.println("READY pid=" + ProcessHandle.current().pid()
                            + " jdk=" + System.getProperty("java.version")
                            + " canRedefine=" + canRedefine
                            + " canRetransform=" + canRetransform);
                    System.out.flush();
                    BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("INVOKE SCORE")) {
                            int x = Integer.parseInt(line.substring("INVOKE SCORE".length()).trim());
                            System.out.println("RESULT SCORE " + score.invoke(instance, x));
                        } else if (line.startsWith("REDEFINE")) {
                            String v2dir = line.substring("REDEFINE".length()).trim();
                            Path v2 = Path.of(v2dir).resolve("DriftTarget.class");
                            byte[] bytes = Files.readAllBytes(v2);
                            INST.redefineClasses(new ClassDefinition(target, bytes));
                            System.out.println("RESULT REDEFINE ok bytes=" + bytes.length);
                        } else if ("RETRANSFORM".equals(line)) {
                            INST.retransformClasses(target);
                            System.out.println("RESULT RETRANSFORM ok");
                        } else if ("CAPABILITIES".equals(line)) {
                            System.out.println("RESULT CAPABILITIES canRedefine=" + canRedefine
                                    + " canRetransform=" + canRetransform
                                    + " inst=" + (INST != null));
                        } else if ("SHUTDOWN".equals(line)) {
                            System.out.println("BYE");
                            System.out.flush();
                            return;
                        }
                        System.out.flush();
                    }
                }
            }
            """;

    /** Manifest for the C08 harness agent jar: declares redefine/retransform capability. */
    static final String C08_HARNESS_MANIFEST = """
            Manifest-Version: 1.0
            Premain-Class: DriftHarness
            Can-Redefine-Classes: true
            Can-Retransform-Classes: true
            """;

    // --------------------------------------------------------------- C10: controlled BB-agent coexistence
    /** The target class enhanced by Kairo and transformed by the controlled Byte Buddy Agent. */
    static final String C10_TARGET_CLASS = "CoexistTarget";
    /** The harness main: drives score()/tag() over stdin (NOT an agent). */
    static final String C10_HARNESS_CLASS = "CoexistHarness";
    /** The controlled Byte Buddy Agent Premain-Class. */
    static final String C10_BB_AGENT_CLASS = "ByteBuddyCoexistAgent";
    /** The Byte Buddy Advice class applied to CoexistTarget.tag(). */
    static final String C10_BB_ADVICE_CLASS = "TagAdvice";
    static final int C10_BASELINE = 10;      // score(5) = 5*2
    static final int C10_ENHANCED_1 = 42;    // Kairo enhance
    static final int C10_ENHANCED_2 = 77;    // Kairo update
    static final String C10_TAG_BB = "BB";   // the BB transform's behavioral marker
    static final String C10_TAG_ORIGINAL = ""; // tag() with no BB transform

    /**
     * CoexistTarget: {@code score(int x){ return x*2; }} (the method Kairo enhances) and
     * {@code tag()}{ return ""; } (the method the controlled Byte Buddy Agent transforms to
     * return {@code "BB"}). Two distinct methods so the BB transform is a genuine behavioral
     * marker that must survive Kairo enhance/update/unload.
     */
    static final String C10_TARGET_SOURCE = """
            // Plain-Java fixture coexisting with a controlled Byte Buddy Agent (C10). No Kairo dep.
            public class CoexistTarget {
                public int score(int x) {
                    return x * 2;
                }
                public String tag() {
                    return "";
                }
            }
            """;

    /** CoexistHarness: drives score()/tag() over stdin. No Kairo dependency, not an agent. */
    static final String C10_HARNESS_SOURCE = """
            import java.io.BufferedReader;
            import java.io.InputStreamReader;

            // C10 harness: drives score()/tag() over stdin. No Kairo dependency.
            public class CoexistHarness {
                public static void main(String[] args) throws Exception {
                    CoexistTarget t = new CoexistTarget();
                    System.out.println("READY pid=" + ProcessHandle.current().pid()
                            + " jdk=" + System.getProperty("java.version"));
                    System.out.flush();
                    BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("INVOKE SCORE")) {
                            int x = Integer.parseInt(line.substring("INVOKE SCORE".length()).trim());
                            System.out.println("RESULT SCORE " + t.score(x));
                        } else if ("INVOKE TAG".equals(line)) {
                            System.out.println("RESULT TAG " + t.tag());
                        } else if ("SHUTDOWN".equals(line)) {
                            System.out.println("BYE");
                            System.out.flush();
                            return;
                        }
                        System.out.flush();
                    }
                }
            }
            """;

    /**
     * The single repository-controlled Byte Buddy Agent fixture (section 10.4.5 C10). Installed
     * <em>ahead of</em> Kairo (first {@code -javaagent}) so the matrix exercises the meaningful
     * coexistence case: a foreign transform already in place when Kairo enhances. Uses genuine
     * Byte Buddy {@code AgentBuilder} + {@code Advice} with {@code RETRANSFORMATION} so the
     * transform re-applies idempotently on every Kairo retransform (retransform re-runs
     * transformers on the original bytes, so the BB advice weaves deterministically onto the
     * original bytes each time, byte-for-byte stable). Prints {@code BB_AGENT_INSTALLED} so the
     * row evidence proves the third-party transformation was actually loaded (never absent).
     */
    static final String C10_BB_AGENT_SOURCE = """
            import java.lang.instrument.Instrumentation;
            import net.bytebuddy.agent.builder.AgentBuilder;
            import net.bytebuddy.asm.Advice;

            import static net.bytebuddy.matcher.ElementMatchers.named;

            // The single in-repo controlled Byte Buddy Agent fixture (C10). Genuine Byte Buddy.
            public class ByteBuddyCoexistAgent {
                public static void premain(String agentArgs, Instrumentation inst) {
                    System.out.println("BB_AGENT_INSTALLED pid=" + ProcessHandle.current().pid());
                    System.out.flush();
                    new AgentBuilder.Default()
                            .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                            .type(named("CoexistTarget"))
                            .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                                    builder.visit(Advice.to(TagAdvice.class).on(named("tag"))))
                            .installOn(inst);
                }
            }
            """;

    /** The Byte Buddy Advice: forces {@code CoexistTarget.tag()} to return {@code "BB"}. */
    static final String C10_BB_ADVICE_SOURCE = """
            import net.bytebuddy.asm.Advice;

            // Byte Buddy Advice applied to CoexistTarget.tag() by the controlled BB Agent (C10).
            public class TagAdvice {
                @Advice.OnMethodExit
                public static void exit(@Advice.Return(readOnly = false) String ret) {
                    ret = "BB";
                }
            }
            """;

    /** Manifest for the C10 Byte Buddy Agent jar. */
    static final String C10_BB_AGENT_MANIFEST = """
            Manifest-Version: 1.0
            Premain-Class: ByteBuddyCoexistAgent
            Can-Retransform-Classes: true
            """;

    private final Path workDir;

    M3EFixtureSources(Path workDir) {
        this.workDir = Objects.requireNonNull(workDir, "workDir");
    }

    /** Writes one named source file into the work directory and returns its path. */
    Path writeSource(String className, String source) throws IOException {
        Files.createDirectories(workDir);
        Path sourceFile = workDir.resolve(className + ".java");
        Files.writeString(sourceFile, source, StandardCharsets.UTF_8);
        return sourceFile;
    }

    /** Writes a manifest file and returns its path. */
    Path writeManifest(String fileName, String content) throws IOException {
        Files.createDirectories(workDir);
        Path manifest = workDir.resolve(fileName);
        Files.writeString(manifest, content, StandardCharsets.UTF_8);
        return manifest;
    }

    /** The class-directory path the compiled classes live in. */
    Path classDirectory() {
        return workDir.resolve("classes");
    }
}
