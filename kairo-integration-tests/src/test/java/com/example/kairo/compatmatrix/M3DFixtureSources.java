package com.example.kairo.compatmatrix;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * The controlled plain-Java fixture sources for the M3-D compatibility scenarios
 * (C05 parent/child same-name loaders, C06 JDK Proxy / CGLIB / Byte Buddy, C07
 * lambda / bridge / synthetic). Mirrors {@link PlainJavaFixtureSource}: the sources
 * are plain Java with <strong>no</strong> Kairo dependency, compiled with the target
 * JDK's {@code javac} into an isolated class directory and launched as an independent
 * JVM, so the matrix exercises the real agent premain against representative targets
 * rather than classes that secretly share the agent ClassLoader.
 *
 * <p>C06's harness additionally needs the real Byte Buddy library and Spring's
 * repackaged CGLIB on its compile + runtime classpath; the executor passes those jars
 * explicitly. CGLIB is <em>not</em> faked by a class-name marker: the harness calls the
 * genuine {@code org.springframework.cglib.proxy.Enhancer}, and Byte Buddy genuinely
 * generates a subclass at runtime.
 */
final class M3DFixtureSources {

    // --------------------------------------------------------------- C05: loaders
    /** The same-name class loaded by two loaders (parent + child-first child). */
    static final String C05_TARGET_CLASS = "LoaderTarget";
    /** The harness main that creates the two loaders and drives invocation over stdin. */
    static final String C05_HARNESS_CLASS = "LoaderTargetHarness";
    static final int C05_BASELINE = 10;
    static final int C05_ENHANCED = 42;

    /** LoaderTarget: same source loaded by two distinct loaders -> two distinct classes. */
    static final String C05_TARGET_SOURCE = """
            // Plain-Java fixture class loaded by two URLClassLoaders (C05). No Kairo dep.
            public class LoaderTarget {
                public int score() {
                    return %d;
                }
            }
            """.formatted(C05_BASELINE);

    /**
     * LoaderTargetHarness: creates a parent URLClassLoader and a child-first
     * URLClassLoader, both pointing at the same classes dir, loads LoaderTarget in
     * each (two distinct classes, same binary name), and drives invocation over stdin.
     * LoaderTarget is referenced ONLY reflectively so the application loader never
     * loads it - exactly two loader-owned copies exist for the agent to discover.
     */
    static final String C05_HARNESS_SOURCE = """
            import java.io.BufferedReader;
            import java.io.File;
            import java.io.InputStreamReader;
            import java.lang.reflect.Method;
            import java.net.URL;
            import java.net.URLClassLoader;
            import java.util.HashSet;
            import java.util.Set;

            // Harness for C05: two same-name loaders. No Kairo dependency.
            public class LoaderTargetHarness {
                public static void main(String[] args) throws Exception {
                    URL[] urls = { new File(System.getProperty("classes.dir")).toURI().toURL() };
                    URLClassLoader parentLoader = new URLClassLoader(urls, null);
                    URLClassLoader childLoader = new ChildFirstClassLoader(urls, parentLoader);
                    Class<?> classA = parentLoader.loadClass("LoaderTarget");
                    Class<?> classB = childLoader.loadClass("LoaderTarget");
                    System.out.println("READY pid=" + ProcessHandle.current().pid()
                            + " jdk=" + System.getProperty("java.version")
                            + " same=" + (classA == classB));
                    System.out.flush();
                    BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if ("INVOKE A".equals(line)) {
                            Object t = classA.getDeclaredConstructor().newInstance();
                            System.out.println("RESULT A " + classA.getMethod("score").invoke(t));
                        } else if ("INVOKE B".equals(line)) {
                            Object t = classB.getDeclaredConstructor().newInstance();
                            System.out.println("RESULT B " + classB.getMethod("score").invoke(t));
                        } else if ("SHUTDOWN".equals(line)) {
                            System.out.println("BYE");
                            System.out.flush();
                            return;
                        }
                        System.out.flush();
                    }
                }

                // A child-first loader: for the fixture class it loads its own copy before
                // delegating to the parent, so parent and child each DEFINE LoaderTarget
                // (two distinct classes with the same binary name).
                static final class ChildFirstClassLoader extends URLClassLoader {
                    private final Set<String> selfFirst = new HashSet<>();
                    ChildFirstClassLoader(URL[] urls, ClassLoader parent) {
                        super(urls, parent);
                        selfFirst.add("LoaderTarget");
                    }
                    @Override
                    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                        synchronized (getClassLoadingLock(name)) {
                            Class<?> c = findLoadedClass(name);
                            if (c == null && selfFirst.contains(name)) {
                                try { c = findClass(name); } catch (ClassNotFoundException ignored) { }
                            }
                            if (c == null) {
                                c = super.loadClass(name, false);
                            }
                            if (resolve) { resolveClass(c); }
                            return c;
                        }
                    }
                }
            }
            """;

    // --------------------------------------------------------------- C06: proxies
    /** The target class proxied by all three proxy types. */
    static final String C06_TARGET_CLASS = "ProxyTarget";
    static final String C06_HARNESS_CLASS = "ProxyTargetHarness";
    static final int C06_BASELINE = 20;
    static final int C06_ENHANCED = 42;

    /**
     * ProxyTarget: implements {@link java.util.function.IntSupplier} so a genuine JDK
     * Proxy can be created over a built-in interface (no custom interface needed).
     * {@code getAsInt()} delegates to {@code score()}; enhancing getAsInt() propagates
     * to all three proxy types because each ultimately invokes ProxyTarget.getAsInt().
     */
    static final String C06_TARGET_SOURCE = """
            import java.util.function.IntSupplier;

            // Plain-Java fixture target proxied by JDK Proxy / CGLIB / Byte Buddy (C06).
            public class ProxyTarget implements IntSupplier {
                public int score() {
                    return %d;
                }
                @Override
                public int getAsInt() {
                    return score();
                }
            }
            """.formatted(C06_BASELINE);

    /**
     * ProxyTargetHarness: creates a genuine JDK Proxy, a genuine CGLIB runtime
     * subclass, and a genuine Byte Buddy runtime-generated subclass. Each invocation
     * ultimately routes through {@code ProxyTarget}. Drives invocation over stdin so the
     * runner proves target resolution, real enhancement through every proxy, and
     * precise unload/restore. Requires byte-buddy + spring-core (cglib) on classpath.
     */
    static final String C06_HARNESS_SOURCE = """
            import java.io.BufferedReader;
            import java.io.InputStreamReader;
            import java.lang.reflect.Proxy;
            import java.util.function.IntSupplier;

            import net.bytebuddy.ByteBuddy;
            import net.bytebuddy.implementation.SuperMethodCall;
            import net.bytebuddy.matcher.ElementMatchers;

            import org.springframework.cglib.proxy.Enhancer;
            import org.springframework.cglib.proxy.Factory;
            import org.springframework.cglib.proxy.MethodInterceptor;

            // Harness for C06: three genuine proxy types. No Kairo dependency.
            public class ProxyTargetHarness {
                public static void main(String[] args) throws Exception {
                    ProxyTarget target = new ProxyTarget();

                    // 1. Genuine JDK Proxy over IntSupplier (delegates to target.getAsInt()).
                    IntSupplier jdkProxy = (IntSupplier) Proxy.newProxyInstance(
                            ProxyTarget.class.getClassLoader(),
                            new Class<?>[] { IntSupplier.class },
                            (p, method, a) -> method.invoke(target, a));

                    // 2. Genuine CGLIB runtime subclass; interceptor calls super (target).
                    Enhancer enhancer = new Enhancer();
                    enhancer.setSuperclass(ProxyTarget.class);
                    enhancer.setCallback((MethodInterceptor) (obj, method, a, proxy) ->
                            proxy.invokeSuper(obj, a));
                    ProxyTarget cglibProxy = (ProxyTarget) enhancer.create();

                    // 3. Genuine Byte Buddy runtime-generated subclass overriding getAsInt.
                    Class<?> bbClass = new ByteBuddy()
                            .subclass(ProxyTarget.class)
                            .method(ElementMatchers.named("getAsInt"))
                            .intercept(SuperMethodCall.INSTANCE)
                            .make()
                            .load(ProxyTarget.class.getClassLoader())
                            .getLoaded();
                    ProxyTarget byteBuddyProxy = (ProxyTarget) bbClass.getDeclaredConstructor().newInstance();

                    System.out.println("READY pid=" + ProcessHandle.current().pid()
                            + " jdk=" + System.getProperty("java.version"));
                    System.out.flush();
                    BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if ("INVOKE JDK".equals(line)) {
                            System.out.println("RESULT JDK " + jdkProxy.getAsInt());
                        } else if ("INVOKE CGLIB".equals(line)) {
                            System.out.println("RESULT CGLIB " + cglibProxy.getAsInt());
                        } else if ("INVOKE BYTEBUDDY".equals(line)) {
                            System.out.println("RESULT BYTEBUDDY " + byteBuddyProxy.getAsInt());
                        } else if ("INVOKE DIRECT".equals(line)) {
                            System.out.println("RESULT DIRECT " + target.getAsInt());
                        } else if ("TYPES".equals(line)) {
                            System.out.println("TYPES jdkProxy=" + Proxy.isProxyClass(jdkProxy.getClass())
                                    + " jdkTargetClass=" + target.getClass().getName()
                                    + " cglibFactory=" + Factory.class.isAssignableFrom(cglibProxy.getClass())
                                    + " cglibClass=" + cglibProxy.getClass().getName()
                                    + " cglibSuper=" + cglibProxy.getClass().getSuperclass().getName()
                                    + " byteBuddySubclass="
                                    + (byteBuddyProxy.getClass().getSuperclass() == ProxyTarget.class)
                                    + " byteBuddyClass=" + byteBuddyProxy.getClass().getName()
                                    + " byteBuddySuper=" + byteBuddyProxy.getClass().getSuperclass().getName());
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

    // ----------------------------------------------------- C07: lambda/bridge/synthetic
    /** The single target class with a lambda path and a generic-bridge path. */
    static final String C07_TARGET_CLASS = "LambdaBridgeTarget";
    static final int C07_SCORE_BASELINE = 10;
    static final int C07_SCORE_ENHANCED = 42;
    static final int C07_COMPUTE_BASELINE = 105;   // 5 + 100
    static final int C07_COMPUTE_ENHANCED = 200;

    /**
     * LambdaBridgeTarget: a stable concrete {@code score()} (lambda path), a
     * {@code lambdaScore()} returning {@code () -> score()} (compiler emits a synthetic
     * {@code lambda$lambdaScore$0} method), and a generic {@code Node<T extends Number>}
     * with {@code IntNode extends Node<Integer>} overriding {@code compute(Integer)} so
     * the compiler emits a {@code compute(Number)} bridge. Driving main() over stdin
     * invokes the lambda path, the bridge path (via a raw {@code Node} reference), the
     * concrete method, and reflects the synthetic/bridge flags for the discovery policy.
     */
    static final String C07_TARGET_SOURCE = """
            import java.io.BufferedReader;
            import java.io.InputStreamReader;
            import java.lang.reflect.Method;
            import java.util.function.IntSupplier;

            // Plain-Java fixture for C07: lambda, compiler bridge and synthetic methods.
            public class LambdaBridgeTarget {
                public int score() {
                    return %d;
                }

                // Lambda path: the body becomes a synthetic lambda$lambdaScore$0 method.
                public IntSupplier lambdaScore() {
                    return () -> score();
                }

                public static class Node<T extends Number> {
                    public int compute(T n) {
                        return n.intValue();
                    }
                }

                public static class IntNode extends Node<Integer> {
                    @Override
                    public int compute(Integer n) {
                        return n.intValue() + 100;
                    }
                    // Compiler-generated bridge: compute(Number) -> compute(Integer).
                }

                public static void main(String[] args) throws Exception {
                    LambdaBridgeTarget t = new LambdaBridgeTarget();
                    IntNode node = new IntNode();
                    System.out.println("READY pid=" + ProcessHandle.current().pid()
                            + " jdk=" + System.getProperty("java.version"));
                    System.out.flush();
                    BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if ("INVOKE LAMBDA".equals(line)) {
                            System.out.println("RESULT LAMBDA " + t.lambdaScore().getAsInt());
                        } else if ("INVOKE SCORE".equals(line)) {
                            System.out.println("RESULT SCORE " + t.score());
                        } else if ("INVOKE BRIDGE".equals(line)) {
                            Node raw = node;
                            Number arg = Integer.valueOf(5);
                            System.out.println("RESULT BRIDGE " + raw.compute(arg));
                        } else if ("INVOKE CONCRETE".equals(line)) {
                            System.out.println("RESULT CONCRETE " + node.compute(Integer.valueOf(5)));
                        } else if ("REFLECT".equals(line)) {
                            System.out.println("REFLECT " + describeMethods());
                        } else if ("SHUTDOWN".equals(line)) {
                            System.out.println("BYE");
                            System.out.flush();
                            return;
                        }
                        System.out.flush();
                    }
                }

                static String describeMethods() {
                    StringBuilder sb = new StringBuilder();
                    for (Class<?> c : new Class<?>[] { LambdaBridgeTarget.class, IntNode.class, Node.class }) {
                        for (Method m : c.getDeclaredMethods()) {
                            sb.append(c.getSimpleName()).append('#').append(m.getName())
                                    .append(" synth=").append(m.isSynthetic())
                                    .append(" bridge=").append(m.isBridge())
                                    .append(';');
                        }
                    }
                    return sb.toString();
                }
            }
            """.formatted(C07_SCORE_BASELINE);

    private final Path workDir;

    M3DFixtureSources(Path workDir) {
        this.workDir = Objects.requireNonNull(workDir, "workDir");
    }

    /** Writes one named source file into the work directory and returns its path. */
    Path writeSource(String className, String source) throws IOException {
        Files.createDirectories(workDir);
        Path sourceFile = workDir.resolve(className + ".java");
        Files.writeString(sourceFile, source, StandardCharsets.UTF_8);
        return sourceFile;
    }

    /** The class-directory path the compiled classes live in. */
    Path classDirectory() {
        return workDir.resolve("classes");
    }
}
