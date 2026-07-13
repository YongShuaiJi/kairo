package com.example.kairo.agent.core.matrix;

import com.example.kairo.api.SupportLevel;
import com.example.kairo.api.matrix.CompatibilityCategory;
import com.example.kairo.api.matrix.CompatibilityScenario;

import java.util.ArrayList;
import java.util.List;

/**
 * V1.5 &sect;6: the declared compatibility matrix.
 *
 * <p>Enumerates every scenario &sect;6 requires the release to cover, grouped by
 * {@link CompatibilityCategory}: JDK version, agent load mode, framework, ClassLoader
 * shape, proxy kind, special method kind, class lifecycle event, Java module state and
 * source language. Each scenario carries a declared {@link SupportLevel} (&sect;2) so
 * "Byte Buddy can in principle do this" is never substituted for a Kairo support
 * statement, and a flag saying whether the scenario is exercised automatically on the
 * running JDK or only documented / deferred to the nightly matrix.
 *
 * <p>The {@link CompatibilityMatrixRunner} evaluates these against a live runner JDK;
 * scenarios that cannot run on the current JDK (a JDK 8 scenario on a JDK 17 runner) or
 * that need an external process (Spring Boot, Tomcat, attach, Kotlin) are reported as
 * {@link com.example.kairo.api.matrix.MatrixOutcome#SKIPPED} or
 * {@link com.example.kairo.api.matrix.MatrixOutcome#DOCUMENTED} rather than silently
 * omitted (&sect;9: no silent coverage gaps).
 */
public final class CompatibilityMatrixFixture {

    /** All declared V1.5 compatibility scenarios, in &sect;6 order. */
    public static List<CompatibilityScenario> scenarios() {
        List<CompatibilityScenario> s = new ArrayList<>();
        // JDK versions (&sect;6: JDK 8, 11, 17, 21).
        s.add(new CompatibilityScenario("jdk-8", "JDK 8", CompatibilityCategory.JDK_VERSION,
                SupportLevel.SUPPORTED, "8", true, "Agent runs on JDK 8 (premain)"));
        s.add(new CompatibilityScenario("jdk-11", "JDK 11", CompatibilityCategory.JDK_VERSION,
                SupportLevel.SUPPORTED, "11", true, "Agent runs on JDK 11"));
        s.add(new CompatibilityScenario("jdk-17", "JDK 17", CompatibilityCategory.JDK_VERSION,
                SupportLevel.SUPPORTED, "17", true, "Agent runs on JDK 17 (module path)"));
        s.add(new CompatibilityScenario("jdk-21", "JDK 21", CompatibilityCategory.JDK_VERSION,
                SupportLevel.SUPPORTED, "21", true, "Agent runs on JDK 21 (virtual-thread aware)"));

        // Load modes (&sect;6: premain, agentmain, attach). Premain is the runtime start; agentmain
        // and attach are exercised in-process via ByteBuddyAgent.install() (self-attach), which
        // proves the attach mechanism the attach CLI and agentmain use.
        s.add(new CompatibilityScenario("load-premain", "premain load", CompatibilityCategory.LOAD_MODE,
                SupportLevel.SUPPORTED, null, true, "Agent loaded at JVM start via -javaagent"));
        s.add(new CompatibilityScenario("load-agentmain", "agentmain attach", CompatibilityCategory.LOAD_MODE,
                SupportLevel.LIMITED, null, true, "Agent attached to a running JVM via VirtualMachine.loadAgent; ByteBuddyAgent.install() verifies the attach mechanism"));
        s.add(new CompatibilityScenario("load-attach-cli", "attach CLI", CompatibilityCategory.LOAD_MODE,
                SupportLevel.LIMITED, null, true, "kairo-attach-cli attaches to a target PID; ByteBuddyAgent.install() verifies the attach mechanism"));

        // Frameworks (&sect;6: Spring Boot 2.x, 3.x; Tomcat). The agent's framework-loader
        // recognizer is exercised in-process (PASSED); the real LaunchedURLClassLoader /
        // WebappClassLoader shape is validated by the CI compatibility-matrix workflow.
        s.add(new CompatibilityScenario("spring-boot-2", "Spring Boot 2.x", CompatibilityCategory.FRAMEWORK,
                SupportLevel.LIMITED, "8/11", true, "LaunchedURLClassLoader (legacy) recognized; real loader shape in CI matrix"));
        s.add(new CompatibilityScenario("spring-boot-3", "Spring Boot 3.x", CompatibilityCategory.FRAMEWORK,
                SupportLevel.LIMITED, "17/21", true, "LaunchedURLClassLoader (launch) recognized; real loader shape in CI matrix"));
        s.add(new CompatibilityScenario("tomcat-webapp", "Tomcat WebApp ClassLoader",
                CompatibilityCategory.FRAMEWORK, SupportLevel.LIMITED, null, true,
                "ParallelWebappClassLoader recognized; real loader shape in CI matrix"));

        // ClassLoader shapes (&sect;6: custom ClassLoader, parent-child same-name).
        s.add(new CompatibilityScenario("cl-custom", "Custom ClassLoader",
                CompatibilityCategory.CLASSLOADER, SupportLevel.SUPPORTED, null, true,
                "User-defined ClassLoader loads the target class"));
        s.add(new CompatibilityScenario("cl-parent-child-samename", "Parent/child same-name class",
                CompatibilityCategory.CLASSLOADER, SupportLevel.SUPPORTED, null, true,
                "Two loaders each define a class with the same binary name; only the selected one is enhanced"));
        s.add(new CompatibilityScenario("cl-bootstrap", "Bootstrap loader target",
                CompatibilityCategory.CLASSLOADER, SupportLevel.EXPERIMENTAL, null, false,
                "JDK/bootstrap class enhancement is a separate high-risk capability (IgnorePolicy.JdkEnhancementCapability)"));

        // Proxy kinds (&sect;6: JDK Proxy, CGLIB, Spring AOP). JDK Proxy, CGLIB and Byte Buddy
        // proxies are classified in-process; Spring AOP needs Spring on the path (CI matrix).
        s.add(new CompatibilityScenario("proxy-jdk", "JDK dynamic proxy",
                CompatibilityCategory.PROXY, SupportLevel.LIMITED, null, true,
                "java.lang.reflect.Proxy; enhance proxy or target, caller chooses (no auto-jump)"));
        s.add(new CompatibilityScenario("proxy-cglib", "CGLIB subclass proxy",
                CompatibilityCategory.PROXY, SupportLevel.LIMITED, null, true,
                "CGLIB $$EnhancerByCGLIB subclass generated via Byte Buddy; target (super) class preferred"));
        s.add(new CompatibilityScenario("proxy-bytebuddy", "Byte Buddy proxy",
                CompatibilityCategory.PROXY, SupportLevel.LIMITED, null, true,
                "Byte Buddy $ByteBuddy$ subclass generated; target (super) class preferred"));
        s.add(new CompatibilityScenario("proxy-spring-aop", "Spring AOP proxy",
                CompatibilityCategory.PROXY, SupportLevel.LIMITED, null, false,
                "Spring AOP (JDK or CGLIB); requires Spring on the path - CI spring-boot-matrix validates"));

        // Special method kinds (&sect;6: Lambda, bridge, synthetic).
        s.add(new CompatibilityScenario("method-lambda", "Lambda / invokedynamic",
                CompatibilityCategory.METHOD_KIND, SupportLevel.EXPERIMENTAL, null, true,
                "Lambda class names unstable; prefer enhancing the declaring method with invokedynamic"));
        s.add(new CompatibilityScenario("method-bridge", "Bridge method",
                CompatibilityCategory.METHOD_KIND, SupportLevel.SUPPORTED, null, true,
                "Bridge refused by default, user-declared method recommended; allowBridge for explicit opt-in"));
        s.add(new CompatibilityScenario("method-synthetic", "Synthetic method",
                CompatibilityCategory.METHOD_KIND, SupportLevel.SUPPORTED, null, true,
                "Synthetic refused by default, user-declared method recommended; allowSynthetic for explicit opt-in"));

        // Lifecycle events (&sect;6: first-load, retransform, redefine, hot-update).
        s.add(new CompatibilityScenario("life-first-load", "First-load pending apply",
                CompatibilityCategory.LIFECYCLE, SupportLevel.SUPPORTED, null, true,
                "PendingEnhancementRegistry applies a rule when a matching class first loads"));
        s.add(new CompatibilityScenario("life-retransform", "Retransform",
                CompatibilityCategory.LIFECYCLE, SupportLevel.SUPPORTED, null, true,
                "Retransform an already-loaded class to apply/remove a rule"));
        s.add(new CompatibilityScenario("life-redefine", "Redefine (hot swap)",
                CompatibilityCategory.LIFECYCLE, SupportLevel.LIMITED, null, true,
                "External redefine of a target class; live redefine listener flags drift"));
        s.add(new CompatibilityScenario("life-hot-update", "Hot-update reconciliation",
                CompatibilityCategory.LIFECYCLE, SupportLevel.SUPPORTED, null, true,
                "Bytecode hash change re-verifies fingerprint; compatible -> new revision, else TARGET_DRIFTED"));

        // Module state (&sect;6: open / not open).
        s.add(new CompatibilityScenario("module-open", "Open module package",
                CompatibilityCategory.MODULE, SupportLevel.SUPPORTED, "9+", true,
                "Target package open to Kairo module; Advice reflects freely"));
        s.add(new CompatibilityScenario("module-closed", "Closed module package",
                CompatibilityCategory.MODULE, SupportLevel.LIMITED, "9+", true,
                "Package not open; minimal redefineModule open (audited) or fail-open with diagnostic"));

        // Source languages (&sect;6: Kotlin normal, default params, coroutines). Kotlin needs the
        // Kotlin compiler on the path; the synthetic-method matrix scenario covers the JVM-level
        // shape Kotlin default params produce, and the CI kotlin-matrix job validates the language.
        s.add(new CompatibilityScenario("lang-kotlin-method", "Kotlin normal method",
                CompatibilityCategory.LANGUAGE, SupportLevel.LIMITED, null, false,
                "Kotlin compiles to plain JVM methods; requires kotlin-compiler - CI kotlin-matrix validates"));
        s.add(new CompatibilityScenario("lang-kotlin-default", "Kotlin default parameters",
                CompatibilityCategory.LANGUAGE, SupportLevel.EXPERIMENTAL, null, false,
                "Default parameters generate synthetic $defaults methods; method-synthetic covers JVM shape; CI kotlin-matrix validates"));
        s.add(new CompatibilityScenario("lang-kotlin-coroutines", "Kotlin coroutines",
                CompatibilityCategory.LANGUAGE, SupportLevel.EXPERIMENTAL, null, false,
                "Coroutine state machines are generated; requires kotlin-compiler - CI kotlin-matrix validates"));

        return s;
    }

    private CompatibilityMatrixFixture() {
    }
}
