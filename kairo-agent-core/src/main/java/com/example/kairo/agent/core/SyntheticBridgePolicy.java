package com.example.kairo.agent.core;

import com.example.kairo.api.MethodSelector;
import com.example.kairo.core.ClassLoaderIdentity;
import com.example.kairo.core.MethodDescriptor;

import java.lang.reflect.Method;

/**
 * V1.5 &sect;4.3: policy for synthetic, bridge and lambda methods.
 *
 * <p>Replaces the V1.0 one-size-fits-all refusal ("取消当前一刀切拒绝，改为发现时标记、
 * 策略控制和精确描述符匹配"). Discovery already <em>marks</em> synthetic/bridge on every
 * {@code MethodInfo} the repository returns (see {@code LoadedClassRepository}); this policy
 * is the <em>control</em> half. It produces a {@link Verdict} the publish, record, match and
 * script-session paths consult so the same rule applies everywhere.
 *
 * <p>Defaults remain strict &mdash; a bridge or compiler-synthetic method is refused with a
 * recommendation to enhance the user-declared method instead &mdash; but the caller may arm
 * {@link #allowBridge(boolean)} / {@link #allowSynthetic(boolean)} for an explicit opt-in
 * (&sect;4.3: "bridge 方法...显式选择后可以增强"). Arming alone enhances nothing; it only
 * permits a method the caller has already named explicitly, which is the "explicit selection".
 *
 * <p>Lambda / hidden classes have unstable names and lifetimes (&sect;4.3: "Lambda 类名和
 * 生命周期不稳定"), so a target whose declaring class is a lambda form or a hidden class is
 * redirected to the declaring method that contains the {@code invokedynamic}; the agent never
 * promises cross-restart reuse of a generated class name.
 */
public final class SyntheticBridgePolicy {

    /** Outcome of evaluating a method against the policy. */
    public enum Decision {
        /** The method may be enhanced as-is. */
        ALLOW,
        /** Refuse by default and recommend an alternate user-declared method; allow only on explicit opt-in. */
        RECOMMEND_ALTERNATE,
        /** Refuse unconditionally. */
        REJECT
    }

    /**
     * Policy verdict for a method. {@link #alternate()} is the recommended user-declared
     * method when {@link #decision()} is {@link Decision#RECOMMEND_ALTERNATE}, or {@code null}
     * when no alternate could be located (e.g. a lambda form whose declaring method is unknown).
     */
    public record Verdict(Decision decision, String reason, MethodSelector alternate) {
        /** Whether the policy permits enhancing the method. */
        public boolean isAllowed() {
            return decision == Decision.ALLOW;
        }
    }

    private volatile boolean allowBridge;
    private volatile boolean allowSynthetic;

    /** Whether bridge methods may be enhanced when explicitly selected by the caller. */
    public boolean allowBridge() {
        return allowBridge;
    }

    /** Arm explicit bridge-method enhancement. Off by default. */
    public void allowBridge(boolean allowBridge) {
        this.allowBridge = allowBridge;
    }

    /** Whether compiler-synthetic (non-bridge) methods may be enhanced when explicitly selected. */
    public boolean allowSynthetic() {
        return allowSynthetic;
    }

    /** Arm explicit synthetic-method enhancement. Off by default. */
    public void allowSynthetic(boolean allowSynthetic) {
        this.allowSynthetic = allowSynthetic;
    }

    /**
     * Evaluate {@code method}. A {@code null} method (e.g. a constructor path with no
     * reflective method) is allowed.
     */
    public Verdict evaluate(Method method) {
        if (method == null) {
            return new Verdict(Decision.ALLOW, null, null);
        }
        Class<?> declaring = method.getDeclaringClass();
        if (isLambdaOrHidden(declaring)) {
            return new Verdict(Decision.RECOMMEND_ALTERNATE,
                    "lambda/hidden class " + declaring.getName() + " has an unstable name and lifetime; "
                            + "enhance the declaring method that contains the invokedynamic instead "
                            + "(no cross-restart reuse of generated class names)",
                    null);
        }
        boolean bridge = method.isBridge();
        boolean synthetic = method.isSynthetic();
        if (!bridge && !synthetic) {
            return new Verdict(Decision.ALLOW, null, null);
        }
        if (bridge) {
            if (allowBridge) {
                return new Verdict(Decision.ALLOW, "bridge method explicitly allowed by policy", null);
            }
            MethodSelector alt = findUserDeclared(declaring, method);
            return new Verdict(Decision.RECOMMEND_ALTERNATE,
                    "bridge method " + descriptor(method) + "; enhance the user-declared method"
                            + (alt == null ? "" : " " + alt.methodName() + alt.methodDescriptor())
                            + " instead (arm allowBridge to enhance the bridge directly)", alt);
        }
        if (allowSynthetic) {
            return new Verdict(Decision.ALLOW, "synthetic method explicitly allowed by policy", null);
        }
        MethodSelector alt = findUserDeclared(declaring, method);
        return new Verdict(Decision.RECOMMEND_ALTERNATE,
                "synthetic method " + descriptor(method) + "; enhance the user-declared method"
                        + (alt == null ? "" : " " + alt.methodName() + alt.methodDescriptor())
                        + " instead (arm allowSynthetic to enhance the synthetic directly)", alt);
    }

    /**
     * Whether a class is a lambda form / hidden class whose name and lifetime are unstable.
     * Hidden classes (JDK 15+) carry a {@code /} in their binary name; lambda forms use
     * {@code $$Lambda$}.
     */
    public static boolean isLambdaOrHidden(Class<?> type) {
        if (type == null) {
            return false;
        }
        String name = type.getName();
        if (name.indexOf('/') >= 0 || name.contains("$$Lambda$")) {
            return true;
        }
        try {
            return Boolean.TRUE.equals(type.isHidden());
        } catch (NoSuchMethodError | RuntimeException ignored) {
            return false;
        }
    }

    /** Locate the non-bridge, non-synthetic user-declared method with the same name in the class. */
    private static MethodSelector findUserDeclared(Class<?> declaring, Method bridgeOrSynthetic) {
        String name = bridgeOrSynthetic.getName();
        for (Method m : declaring.getDeclaredMethods()) {
            if (!m.isBridge() && !m.isSynthetic() && m.getName().equals(name)) {
                return new MethodSelector(declaring.getName(),
                        ClassLoaderIdentity.idOf(declaring.getClassLoader()),
                        m.getName(), MethodDescriptor.of(m));
            }
        }
        return null;
    }

    private static String descriptor(Method m) {
        return m.getDeclaringClass().getName() + "#" + m.getName() + MethodDescriptor.of(m);
    }
}
