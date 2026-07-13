package com.example.kairo.agent.core;

import com.example.kairo.api.SupportLevel;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Strategy that decides which classes the Kairo transformer skips (V1.5 &sect;4.1
 * / &sect;5: "ByteBuddyTransformerManager.ignore 改为策略对象，支持受控的
 * Bootstrap/JDK 类范围").
 *
 * <p>Replaces the V1.0 hard-coded {@code isIgnored} static method with a
 * policy object. The safe default keeps the V1.0 behaviour: {@code java.*},
 * {@code javax.*}, {@code jdk.*}, {@code sun.*}, {@code com.sun.*}, the JVM's
 * DOM/XML/JGSS packages, Byte Buddy, Groovy and Kairo's own packages are never
 * woven. This list is <em>not</em> deleted (&sect;4.1: "不能简单删除 ignore 列表")
 * because blindly weaving JDK classes is a high-risk operation that can deadlock
 * or corrupt the bootstrap path.
 *
 * <p>The policy exposes a separate, audited {@link JdkEnhancementCapability}:
 * a caller may explicitly allow a <em>named</em> JDK class for enhancement, which
 * the publish path records as a high-risk decision. Global JDK enhancement is
 * never enabled by a flag alone; every class is opt-in and audited.
 */
public final class IgnorePolicy {

    /** Packages the transformer never weaves unless individually allowed. */
    private static final Set<String> IGNORED_PREFIXES = Set.of(
            "java.",
            "javax.",
            "jdk.",
            "sun.",
            "com.sun.",
            "com.oracle.",
            "org.w3c.dom.",
            "org.xml.sax.",
            "org.ietf.jgss.",
            "net.bytebuddy.",
            "groovy.",
            "org.codehaus.groovy.",
            "com.example.kairo.");

    private final Set<String> additionallyAllowed = new CopyOnWriteArraySet<>();
    private final JdkEnhancementCapability jdkCapability = new JdkEnhancementCapability();

    /**
     * Whether the transformer should ignore (skip) this class. Returns
     * {@code false} for classes the policy explicitly allowed even when their
     * package is on the ignore list.
     */
    public boolean ignore(String binaryName, ClassLoader classLoader) {
        if (binaryName == null) {
            return true;
        }
        if (additionallyAllowed.contains(binaryName)) {
            return false;
        }
        for (String prefix : IGNORED_PREFIXES) {
            if (binaryName.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** Whether {@code binaryName} is a JDK/JVM class covered by the ignore list. */
    public boolean isJdkOrPlatformClass(String binaryName) {
        if (binaryName == null) {
            return false;
        }
        return binaryName.startsWith("java.") || binaryName.startsWith("javax.")
                || binaryName.startsWith("jdk.") || binaryName.startsWith("sun.")
                || binaryName.startsWith("com.sun.") || binaryName.startsWith("com.oracle.")
                || binaryName.startsWith("org.w3c.dom.") || binaryName.startsWith("org.xml.sax.")
                || binaryName.startsWith("org.ietf.jgss.");
    }

    /**
     * Explicitly allow one named class for enhancement. Used by the JDK
     * enhancement capability; audited via {@link #jdkCapability()}.
     */
    public boolean allow(String binaryName) {
        if (binaryName == null || binaryName.isBlank()) {
            return false;
        }
        return additionallyAllowed.add(binaryName);
    }

    /** Withdraw an explicit allowance. */
    public boolean disallow(String binaryName) {
        return additionallyAllowed.remove(binaryName);
    }

    /** The audited JDK-enhancement capability gate. */
    public JdkEnhancementCapability jdkCapability() {
        return jdkCapability;
    }

    /**
     * V1.5 &sect;4.1: the JDK-class enhancement capability. Enhancing a JDK class
     * is a separate high-risk ability: it is off by default, every opt-in is
     * recorded, and the support level for an enhanced JDK class is
     * {@link SupportLevel#EXPERIMENTAL}.
     */
    public static final class JdkEnhancementCapability {
        private volatile boolean enabled = false;
        private final Set<String> allowedClasses = ConcurrentHashMap.newKeySet();
        private final Set<String> auditLog = ConcurrentHashMap.newKeySet();

        /** Whether the capability is armed (opt-in classes may be enhanced). */
        public boolean enabled() {
            return enabled;
        }

        /** Arm the capability. Off by default; enabling alone enhances nothing. */
        public void enable() {
            enabled = true;
        }

        public void disable() {
            enabled = false;
        }

        /**
         * Opt in one named JDK class for enhancement. Records the decision in the
         * audit log. Returns false when the capability is not armed.
         */
        public boolean allow(String binaryName) {
            if (!enabled || binaryName == null || binaryName.isBlank()) {
                return false;
            }
            allowedClasses.add(binaryName);
            auditLog.add(binaryName);
            return true;
        }

        public boolean isAllowed(String binaryName) {
            return enabled && binaryName != null && allowedClasses.contains(binaryName);
        }

        /** The set of JDK classes ever opted in (audit). */
        public Set<String> auditLog() {
            return Set.copyOf(auditLog);
        }

        public SupportLevel supportLevelFor(String binaryName) {
            return isAllowed(binaryName) ? SupportLevel.EXPERIMENTAL : SupportLevel.UNSUPPORTED;
        }
    }
}
