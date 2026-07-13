package com.example.kairo.agent.core;

import com.example.kairo.api.EnhancementLocation;
import com.example.kairo.api.EnhancementTarget;
import com.example.kairo.api.MethodSelector;
import com.example.kairo.api.ProxyAnalysis;
import com.example.kairo.api.ProxyType;
import com.example.kairo.api.SupportLevel;
import com.example.kairo.core.ClassLoaderIdentity;
import com.example.kairo.core.MethodDescriptor;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Default {@link ProxyTargetAnalyzer} (V1.5 &sect;4.2).
 *
 * <p>Detection is name- and structure-based so it carries no hard dependency on
 * CGLIB or Byte Buddy at runtime:
 * <ul>
 *   <li><b>JDK proxy</b> &mdash; {@link Proxy#isProxyClass(Class)};</li>
 *   <li><b>CGLIB</b> &mdash; class name contains {@code $$EnhancerByCGLIB} or
 *       {@code $$FastClassByCGLIB}, or a generated CGLIB accessor pattern;</li>
 *   <li><b>Byte Buddy</b> &mdash; class name contains {@code $ByteBuddy$} or a
 *       Byte Buddy generated-suffix pattern;</li>
 *   <li><b>Unknown</b> &mdash; {@link Class#isHidden()} (JDK 15+ hidden classes,
 *       including some lambda forms) or a {@code $$} generated pattern not
 *       recognised above;</li>
 *   <li><b>Plain</b> &mdash; none of the above.</li>
 * </ul>
 *
 * <p>For a JDK proxy the recommended target is the interface method the user
 * most likely declared; for a CGLIB/Byte Buddy subclass proxy the recommended
 * target is the <em>target (super) class</em>, because enhancing the proxy
 * subclass only affects proxy instances while enhancing the real class affects
 * every instance. The analysis never publishes; it only recommends.
 */
public final class DefaultProxyTargetAnalyzer implements ProxyTargetAnalyzer {

    @Override
    public ProxyAnalysis analyze(Class<?> type) {
        ProxyType proxyType = classify(type);
        List<String> interfaces = interfacesOf(type, proxyType);
        String superclass = superclassOf(type, proxyType);
        List<MethodSelector> candidates = candidateUserMethods(type, proxyType);
        MethodSelector recommended = recommendedTarget(type, proxyType, candidates);
        String impact = impactExplanation(type, proxyType);
        SupportLevel level = supportLevelFor(proxyType);
        return new ProxyAnalysis(proxyType, interfaces, superclass, candidates, recommended, impact, level);
    }

    static ProxyType classify(Class<?> type) {
        if (Proxy.isProxyClass(type)) {
            return ProxyType.JDK_PROXY;
        }
        String name = type.getName();
        if (name.contains("$$EnhancerByCGLIB") || name.contains("$$FastClassByCGLIB")
                || name.contains("$$BulkBeanByCGLIB") || name.contains("ByCGLIB$$")) {
            return ProxyType.CGLIB;
        }
        if (name.contains("$ByteBuddy$") || name.contains("$$bytebuddy$$")
                || name.contains("ByteBuddy")) {
            return ProxyType.BYTE_BUDDY;
        }
        if (isHidden(type) || name.contains("$$Lambda$") || name.contains("$$")) {
            return ProxyType.UNKNOWN;
        }
        return ProxyType.PLAIN;
    }

    private static boolean isHidden(Class<?> type) {
        try {
            return Boolean.TRUE.equals(type.isHidden());
        } catch (NoSuchMethodError | RuntimeException ignored) {
            return false;
        }
    }

    private static List<String> interfacesOf(Class<?> type, ProxyType proxyType) {
        if (proxyType == ProxyType.JDK_PROXY) {
            return Arrays.stream(type.getInterfaces()).map(Class::getName).toList();
        }
        return List.of();
    }

    private static String superclassOf(Class<?> type, ProxyType proxyType) {
        if (proxyType == ProxyType.CGLIB || proxyType == ProxyType.BYTE_BUDDY) {
            Class<?> sup = type.getSuperclass();
            return sup == null ? null : sup.getName();
        }
        return null;
    }

    private static List<MethodSelector> candidateUserMethods(Class<?> type, ProxyType proxyType) {
        List<MethodSelector> out = new ArrayList<>();
        Class<?> surface = switch (proxyType) {
            case JDK_PROXY -> type.getInterfaces().length > 0 ? type.getInterfaces()[0] : type;
            case CGLIB, BYTE_BUDDY -> type.getSuperclass() != null ? type.getSuperclass() : type;
            default -> type;
        };
        // A JDK proxy's surface is an interface: every method is abstract there, but the
        // proxy implements them, so abstract is not a reason to skip. For a class surface
        // (plain / CGLIB / Byte Buddy super) abstract methods are genuinely unenhanceable.
        boolean surfaceIsInterface = surface.isInterface();
        for (Method method : surface.getDeclaredMethods()) {
            int mods = method.getModifiers();
            if (Modifier.isStatic(mods) || Modifier.isNative(mods)
                    || method.isBridge() || method.isSynthetic()) {
                continue;
            }
            if (!surfaceIsInterface && Modifier.isAbstract(mods)) {
                continue;
            }
            out.add(new MethodSelector(surface.getName(),
                    ClassLoaderIdentity.idOf(surface.getClassLoader()),
                    method.getName(), MethodDescriptor.of(method)));
        }
        return out;
    }

    private static MethodSelector recommendedTarget(Class<?> type, ProxyType proxyType,
                                                     List<MethodSelector> candidates) {
        if (candidates.isEmpty()) {
            return null;
        }
        return switch (proxyType) {
            case JDK_PROXY, CGLIB, BYTE_BUDDY -> candidates.get(0);
            default -> candidates.get(0);
        };
    }

    private static String impactExplanation(Class<?> type, ProxyType proxyType) {
        return switch (proxyType) {
            case JDK_PROXY -> "JDK dynamic proxy " + type.getName() + " dispatches to an InvocationHandler. "
                    + "Enhancing the proxy class affects only proxy instances; enhancing the interface's "
                    + "declaring method has no effect unless the interface is also a concrete class. "
                    + "Select the proxy class to intercept proxy invocations.";
            case CGLIB -> "CGLIB subclass proxy " + type.getName() + " extends target "
                    + (type.getSuperclass() == null ? "?" : type.getSuperclass().getName()) + ". "
                    + "Enhancing the proxy subclass affects only proxy instances; enhancing the target "
                    + "(super) class affects every instance of the real class. Prefer the target class.";
            case BYTE_BUDDY -> "Byte Buddy proxy " + type.getName() + " extends target "
                    + (type.getSuperclass() == null ? "?" : type.getSuperclass().getName()) + ". "
                    + "Enhancing the proxy subclass affects only proxy instances; enhancing the target "
                    + "(super) class affects every instance. Prefer the target class.";
            case UNKNOWN -> "Generated/hidden class " + type.getName() + " whose structure the analyzer "
                    + "could not classify. Lambda forms and hidden classes have unstable names and "
                    + "lifetimes; prefer enhancing the declaring method that contains the invokedynamic.";
            case PLAIN -> "Plain class; no proxy machinery detected. Enhance directly.";
        };
    }

    private static SupportLevel supportLevelFor(ProxyType proxyType) {
        return switch (proxyType) {
            case PLAIN -> SupportLevel.SUPPORTED;
            case JDK_PROXY, CGLIB, BYTE_BUDDY -> SupportLevel.LIMITED;
            case UNKNOWN -> SupportLevel.EXPERIMENTAL;
        };
    }

    /** Build an {@link EnhancementTarget} for the recommended proxy/super method. */
    public static EnhancementTarget recommendedTarget(Class<?> type, EnhancementLocation location) {
        ProxyAnalysis analysis = new DefaultProxyTargetAnalyzer().analyze(type);
        MethodSelector recommended = analysis.recommendedTarget();
        if (recommended == null) {
            return null;
        }
        return EnhancementTarget.of(recommended, location);
    }
}
