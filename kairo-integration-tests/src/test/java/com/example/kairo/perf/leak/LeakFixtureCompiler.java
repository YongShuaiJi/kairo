package com.example.kairo.perf.leak;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Compiles the M2-C business fixtures once into a disposable temp directory and exposes
 * the directory for per-cycle {@link java.net.URLClassLoader} creation. The fixtures live
 * in the weavable package {@code com.example.leakfixture} (NOT on the agent
 * {@code IgnorePolicy} list, unlike {@code com.example.kairo.*}), so the agent will
 * retransform them when rules are published against their methods.
 *
 * <p>The directory is reused across cycles: each cycle creates a fresh, distinct
 * {@code URLClassLoader} over the same compiled bytes, so the loaders are genuinely
 * unloadable and individually reclaimable without re-running {@code javac} per cycle.
 *
 * <p>The CGLIB fixture class carries the {@code $$EnhancerByCGLIB$$} name marker so the
 * product's name-based {@code ProxyTargetAnalyzer} classifies it as
 * {@code ProxyType.CGLIB} without a hard CGLIB runtime dependency (&sect;9.3: CGLIB is
 * exercised through the detection path the product actually supports).
 *
 * <p>{@link AutoCloseable}: closing deletes the temp directory. The compiled classes
 * themselves are freed when their defining loaders are collected.
 */
public final class LeakFixtureCompiler implements AutoCloseable {

    public static final String PACKAGE = "com.example.leakfixture";
    private static final String DIR_LAYOUT = "com/example/leakfixture";

    private final Path directory;

    private LeakFixtureCompiler(Path directory) {
        this.directory = directory;
    }

    /** Compile all fixtures into a fresh temp directory and return the handle. */
    public static LeakFixtureCompiler compile() throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("no system JavaCompiler on classpath (jdk.compiler module absent)");
        }
        Path dir = Files.createTempDirectory("kairo-leak-fixtures-");
        Path sourceRoot = dir.resolve(DIR_LAYOUT);
        Files.createDirectories(sourceRoot);
        List<Path> sources = new ArrayList<>();
        for (Fixture f : FIXTURES) {
            Path source = sourceRoot.resolve(f.simpleName + ".java");
            Files.writeString(source, f.source, StandardCharsets.UTF_8);
            sources.add(source);
        }
        int rc = compiler.run(null, null, null, buildArgs(dir, sources));
        if (rc != 0) {
            deleteTree(dir);
            throw new IllegalStateException("javac returned " + rc + " compiling leak fixtures");
        }
        // Remove sources; only .class bytes remain for URLClassLoader loading.
        for (Path source : sources) {
            Files.deleteIfExists(source);
        }
        return new LeakFixtureCompiler(dir);
    }

    /** The directory to add to a URLClassLoader's URLs. */
    public Path directory() {
        return directory;
    }

    /** The fully-qualified binary name of a fixture by simple name. */
    public String binaryName(String simpleName) {
        return PACKAGE + "." + simpleName;
    }

    @Override
    public void close() {
        deleteTree(directory);
    }

    private static String[] buildArgs(Path outputRoot, List<Path> sources) {
        List<String> args = new ArrayList<>();
        args.add("-d");
        args.add(outputRoot.toString());
        sources.forEach(p -> args.add(p.toString()));
        return args.toArray(new String[0]);
    }

    private static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException ignored) {
            // best-effort cleanup; a leftover temp dir is not a test failure
        }
    }

    // -------------------------------------------------------- fixture sources

    private record Fixture(String simpleName, String source) {
    }

    static final String LEAK_SERVICE = "LeakService";
    static final String LEAK_INTERFACE = "LeakInterface";
    static final String LAMBDA_HOLDER = "LambdaHolder";
    static final String GENERIC_BASE = "GenericBase";
    static final String GENERIC_SUB = "GenericSub";
    static final String CGLIB_ENHANCER = "LeakCglib$$EnhancerByCGLIB$$1";

    private static final List<Fixture> FIXTURES = List.of(
            new Fixture(LEAK_SERVICE, """
                    package com.example.leakfixture;
                    public class LeakService implements com.example.leakfixture.LeakInterface {
                        public String echo(String value) { return "echo:" + value; }
                        public int compute(int x) { return x * 2; }
                    }
                    """),
            new Fixture(LEAK_INTERFACE, """
                    package com.example.leakfixture;
                    public interface LeakInterface {
                        String echo(String value);
                    }
                    """),
            new Fixture(LAMBDA_HOLDER, """
                    package com.example.leakfixture;
                    import java.util.function.IntUnaryOperator;
                    public class LambdaHolder {
                        // A lambda captured here generates a synthetic hidden class defined
                        // by this loader, exercising the Lambda/synthetic classification path.
                        public int transform(int x) {
                            IntUnaryOperator op = v -> v * 3 + 1;
                            return op.applyAsInt(x);
                        }
                        public String label() { return "lambda-holder"; }
                    }
                    """),
            new Fixture(GENERIC_BASE, """
                    package com.example.leakfixture;
                    public class GenericBase<T> {
                        public T process(T input) { return input; }
                    }
                    """),
            new Fixture(GENERIC_SUB, """
                    package com.example.leakfixture;
                    public class GenericSub extends GenericBase<String> {
                        @Override
                        public String process(String input) { return "sub:" + input; }
                        // javac generates a bridge process(Object) delegating to process(String),
                        // exercising the SyntheticBridgePolicy bridge path.
                    }
                    """),
            new Fixture(CGLIB_ENHANCER, """
                    package com.example.leakfixture;
                    // The $$EnhancerByCGLIB$$ marker in the binary name is what the product's
                    // name-based ProxyTargetAnalyzer matches to classify a class as CGLIB.
                    public class LeakCglib$$EnhancerByCGLIB$$1 {
                        public String echo(String value) { return "cglib:" + value; }
                    }
                    """));
}
