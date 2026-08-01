package com.example.kairo.compatmatrix;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * The controlled Spring Boot 3 executable-jar fixture for the M3-C compatibility
 * scenarios C03 (premain) and C04 (external attach). The fixture is the repository's
 * existing {@code kairo-demo} module: a genuine Spring Boot 3 application packaged by
 * {@code spring-boot-maven-plugin:repackage} into an executable jar (Main-Class =
 * {@code org.springframework.boot.loader.launch.JarLauncher}, embedded {@code BOOT-INF}
 * Tomcat). It has no Kairo dependency on its classpath, so the matrix exercises the real
 * agent load path against a representative Spring Boot target whose classes are loaded
 * by the application's own {@code LaunchedURLClassLoader}.
 *
 * <p>The fixture is intentionally NOT a new module: the V1.7 reactor is frozen at 16
 * modules ({@code ModuleBoundaryConvergenceTest}) and {@code kairo-demo} is already a
 * sanctioned non-product Spring Boot 3 executable jar (roadmap &sect;2.3). M3-C reuses
 * it as the controlled fixture rather than introducing a second Spring Boot module, so
 * C03/C04 prove the real agent load path against a real Spring Boot 3 executable jar -
 * not a plain jar and not Spring objects constructed in the test JVM.
 *
 * <p>The fixture exposes {@code GET /demo/score?base=N} backed by
 * {@code OrderService.calculateScore(int)} (returns {@code base*2}). The runner captures
 * the <em>real</em> baseline from the live HTTP response, publishes a rule that mocks
 * {@code calculateScore} to a fixed value, proves the application HTTP invocation result
 * changes, then unloads and proves it returns to the real baseline. Nothing is hard-coded
 * beyond the mocked return value carried by the rule script: the baseline and restored
 * values come from the running target.
 */
final class SpringBootFixtureTarget {

    /** The Spring Boot executable-jar classifier produced by kairo-demo's repackage goal. */
    static final String EXEC_CLASSIFIER = "exec";

    /** The only controlled application entry point accepted by the M3-C fixture gate. */
    static final String START_CLASS = "com.example.demo.DemoApplication";
    /** Spring Boot 3 launcher class embedded in a repackaged executable jar. */
    static final String BOOT_LAUNCHER = "org.springframework.boot.loader.launch.JarLauncher";

    /** The target Spring bean class whose method is enhanced. */
    static final String TARGET_CLASS_NAME = "com.example.demo.OrderService";
    /** The target method name on the Spring bean. */
    static final String TARGET_METHOD_NAME = "calculateScore";
    /** {@code calculateScore(int) -> int} JVM descriptor. */
    static final String TARGET_METHOD_DESCRIPTOR = "(I)I";

    /** The application HTTP endpoint that invokes the enhanced method. */
    static final String APP_PATH = "/demo/score?base=10";
    /** The request base; the real baseline is derived from the live response (base*2 = 20). */
    static final int APP_BASE = 10;

    /** The fixed mocked return value applied by the enhance rule (from the rule script). */
    static final int ENHANCED_SCORE = 42;

    private SpringBootFixtureTarget() {
    }

    /**
     * Whether {@code jar} is a genuine Spring Boot executable jar (a real
     * {@code spring-boot-maven-plugin:repackage} artifact), not a plain jar. A plain
     * jar's Main-Class is the application class ({@code DemoApplication}) and it has no
     * {@code BOOT-INF/} tree; an executable jar's Main-Class is a Spring Boot loader
     * ({@code org.springframework.boot.loader.*}) and it embeds {@code BOOT-INF/}. This
     * is the gate that rejects a plain/non-executable jar as fake C03/C04 evidence.
     */
    static boolean isSpringBootExecutableJar(Path jar) throws IOException {
        if (jar == null || !Files.isRegularFile(jar)) {
            return false;
        }
        try (JarFile jf = new JarFile(jar.toFile())) {
            Manifest manifest = jf.getManifest();
            if (manifest == null) {
                return false;
            }
            String mainClass = manifest.getMainAttributes().getValue("Main-Class");
            String startClass = manifest.getMainAttributes().getValue("Start-Class");
            if (!BOOT_LAUNCHER.equals(mainClass) || !START_CLASS.equals(startClass)) {
                return false;
            }
            return jf.getJarEntry("org/springframework/boot/loader/launch/JarLauncher.class") != null
                    && jf.getJarEntry("BOOT-INF/classes/com/example/demo/DemoApplication.class") != null
                    && jf.getJarEntry("BOOT-INF/classes/com/example/demo/OrderService.class") != null;
        }
    }
}
