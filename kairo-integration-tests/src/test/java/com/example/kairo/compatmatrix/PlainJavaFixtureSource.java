package com.example.kairo.compatmatrix;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * The controlled plain-Java fixture target for the M3-B compatibility scenarios
 * (C01 premain, C02 external attach/agentmain, C09 agentmain on macOS arm64).
 *
 * <p>The fixture is intentionally <strong>plain Java</strong>: it has no Kairo
 * dependency on its classpath (it does not reference {@code kairo-groovy},
 * {@code kairo-bootstrap-api} or any agent type). This mirrors a real
 * third-party application whose classes are loaded by the platform/application
 * ClassLoader, so the matrix exercises the real agent load path against a
 * representative target rather than a class that secretly shares the agent's
 * ClassLoader.
 *
 * <p>The runner compiles this source with the target JDK's {@code javac} into an
 * isolated class directory and launches it as an <strong>independent</strong>
 * JVM. The fixture prints a {@code READY} line carrying its own PID and JDK
 * version, then drives its {@code score()} method on demand over stdin
 * ({@code INVOKE} / {@code SHUTDOWN}) and prints the <em>actual</em> return
 * value, so the runner's assertions are derived from real target behavior
 * rather than hard-coded booleans.
 *
 * <p>The source is held as a string so the compiled class genuinely lives on a
 * controlled plain-Java classpath (just the compiled class directory) and can
 * never accidentally pick up agent classes from the runner/test classpath.
 */
final class PlainJavaFixtureSource {

    /** The fixture class name (launched as {@code PlainJavaTarget}). */
    static final String CLASS_NAME = "PlainJavaTarget";

    /** Baseline return value of {@code score()} before any enhancement. */
    static final int BASELINE_SCORE = 10;
    /** Mock return value applied by the enhance rule (C01/C02/C09). */
    static final int ENHANCED_SCORE = 42;
    /** Mock return value applied by the update rule (C01 only). */
    static final int UPDATED_SCORE = 77;

    /**
     * The fixture source. Plain Java (JDK 9+ for {@code ProcessHandle}); no Kairo
     * imports. The {@code READY} line carries {@code pid} and {@code jdk} so the
     * runner records the real independent child PID and actual target JDK.
     */
    static final String SOURCE = """
            import java.io.BufferedReader;
            import java.io.InputStreamReader;

            // Plain-Java fixture target for V1.7 M3-B compatibility scenarios.
            // No Kairo dependency: launched on a controlled plain-Java classpath and
            // driven by the runner over stdin. The printed RESULT is the real return
            // value of score(), so assertions reflect actual target behavior.
            public class PlainJavaTarget {
                public int score() {
                    return %d;
                }

                public static void main(String[] args) throws Exception {
                    PlainJavaTarget target = new PlainJavaTarget();
                    System.out.println("READY pid=" + ProcessHandle.current().pid()
                            + " jdk=" + System.getProperty("java.version"));
                    System.out.flush();
                    BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if ("INVOKE".equals(line)) {
                            System.out.println("RESULT " + target.score());
                        } else if ("SHUTDOWN".equals(line)) {
                            System.out.println("BYE");
                            System.out.flush();
                            return;
                        }
                        System.out.flush();
                    }
                }
            }
            """.formatted(BASELINE_SCORE);

    private final Path workDir;

    PlainJavaFixtureSource(Path workDir) {
        this.workDir = Objects.requireNonNull(workDir, "workDir");
    }

    /** Writes the fixture source into the work directory. Returns the source file path. */
    Path writeSource() throws IOException {
        Files.createDirectories(workDir);
        Path source = workDir.resolve(CLASS_NAME + ".java");
        Files.writeString(source, SOURCE, StandardCharsets.UTF_8);
        return source;
    }

    /** The class-directory path the compiled class will live in. */
    Path classDirectory() {
        return workDir.resolve("classes");
    }
}
