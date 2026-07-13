package com.example.kairo.api;

import java.util.List;
import java.util.Objects;

/**
 * Result of analyzing a class for proxy structure (V1.5 &sect;4.2).
 *
 * <p>{@code ProxyTargetAnalyzer} produces this so the platform can present, for
 * a candidate class: its detected {@link ProxyType}, the interfaces a JDK proxy
 * implements or the superclass a CGLIB/Byte Buddy proxy extends, the user
 * methods that are candidates for enhancement, a recommended enhancement target,
 * and a plain-language explanation of what enhancing the proxy class, the target
 * class, or both would mean.
 *
 * <p>The system never auto-jumps from a proxy class to its target and publishes
 * on its own (&sect;4.2: "系统不应擅自从代理类跳转到目标类后发布"). This type is
 * advisory; the final selection is made by the caller and recorded in the rule
 * target and audit.
 */
public final class ProxyAnalysis {

    private final ProxyType proxyType;
    private final List<String> proxyInterfaces;
    private final String superclass;
    private final List<MethodSelector> candidateUserMethods;
    private final MethodSelector recommendedTarget;
    private final String impactExplanation;
    private final SupportLevel supportLevel;

    public ProxyAnalysis(ProxyType proxyType, List<String> proxyInterfaces, String superclass,
                         List<MethodSelector> candidateUserMethods, MethodSelector recommendedTarget,
                         String impactExplanation, SupportLevel supportLevel) {
        this.proxyType = Objects.requireNonNull(proxyType, "proxyType");
        this.proxyInterfaces = proxyInterfaces == null ? List.of() : List.copyOf(proxyInterfaces);
        this.superclass = superclass;
        this.candidateUserMethods = candidateUserMethods == null ? List.of() : List.copyOf(candidateUserMethods);
        this.recommendedTarget = recommendedTarget;
        this.impactExplanation = impactExplanation;
        this.supportLevel = supportLevel == null ? SupportLevel.SUPPORTED : supportLevel;
    }

    public ProxyType proxyType() {
        return proxyType;
    }

    public List<String> proxyInterfaces() {
        return proxyInterfaces;
    }

    public String superclass() {
        return superclass;
    }

    public List<MethodSelector> candidateUserMethods() {
        return candidateUserMethods;
    }

    public MethodSelector recommendedTarget() {
        return recommendedTarget;
    }

    public String impactExplanation() {
        return impactExplanation;
    }

    public SupportLevel supportLevel() {
        return supportLevel;
    }

    /** Whether the analyzed class is any kind of generated proxy. */
    public boolean isProxy() {
        return proxyType != ProxyType.PLAIN && proxyType != ProxyType.UNKNOWN;
    }
}
