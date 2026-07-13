package com.example.kairo.agent.core;

import com.example.kairo.api.MethodSelector;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V1.5 &sect;4.3: the synthetic/bridge/lambda policy replaces the blanket refusal with
 * mark-on-discovery + policy control. Default refuses bridge and synthetic methods and
 * recommends the user-declared method; arming {@code allowBridge}/{@code allowSynthetic}
 * is the explicit opt-in.
 */
class SyntheticBridgePolicyTest {

    private final SyntheticBridgePolicy policy = new SyntheticBridgePolicy();

    @Test
    void plainMethodIsAllowed() throws Exception {
        Method plain = Plain.class.getDeclaredMethod("value");
        SyntheticBridgePolicy.Verdict verdict = policy.evaluate(plain);
        assertThat(verdict.isAllowed()).isTrue();
        assertThat(verdict.decision()).isEqualTo(SyntheticBridgePolicy.Decision.ALLOW);
    }

    @Test
    void bridgeMethodIsRefusedWithUserDeclaredAlternateByDefault() throws Exception {
        Method bridge = bridgeMethod();
        SyntheticBridgePolicy.Verdict verdict = policy.evaluate(bridge);
        assertThat(verdict.isAllowed()).isFalse();
        assertThat(verdict.decision()).isEqualTo(SyntheticBridgePolicy.Decision.RECOMMEND_ALTERNATE);
        assertThat(verdict.reason()).contains("bridge method");
        MethodSelector alternate = verdict.alternate();
        assertThat(alternate).isNotNull();
        assertThat(alternate.methodName()).isEqualTo("get");
        // The alternate is the user-declared String-returning method, not the bridge.
        assertThat(alternate.methodDescriptor()).contains("Ljava/lang/String;");
    }

    @Test
    void armingAllowBridgePermitsExplicitBridgeSelection() throws Exception {
        Method bridge = bridgeMethod();
        policy.allowBridge(true);
        try {
            SyntheticBridgePolicy.Verdict verdict = policy.evaluate(bridge);
            assertThat(verdict.isAllowed()).isTrue();
            assertThat(verdict.reason()).contains("explicitly allowed");
        } finally {
            policy.allowBridge(false);
        }
    }

    @Test
    void syntheticAccessorIsRefusedWithAlternateByDefault() throws Exception {
        Method synthetic = syntheticMethod();
        SyntheticBridgePolicy.Verdict verdict = policy.evaluate(synthetic);
        assertThat(verdict.isAllowed()).isFalse();
        assertThat(verdict.decision()).isEqualTo(SyntheticBridgePolicy.Decision.RECOMMEND_ALTERNATE);
        assertThat(verdict.reason()).contains("synthetic method");
    }

    @Test
    void armingAllowSyntheticPermitsExplicitSelection() throws Exception {
        Method synthetic = syntheticMethod();
        policy.allowSynthetic(true);
        try {
            assertThat(policy.evaluate(synthetic).isAllowed()).isTrue();
        } finally {
            policy.allowSynthetic(false);
        }
    }

    @Test
    void nullMethodIsAllowed() {
        assertThat(policy.evaluate(null).isAllowed()).isTrue();
    }

    private static Method bridgeMethod() {
        return java.util.Arrays.stream(StringHolder.class.getDeclaredMethods())
                .filter(Method::isBridge)
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected a bridge method"));
    }

    private static Method syntheticMethod() {
        return java.util.Arrays.stream(SyntheticAccessor.class.getDeclaredMethods())
                .filter(Method::isSynthetic)
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected a synthetic method"));
    }

    /** Plain class with a plain method. */
    public static final class Plain {
        public String value() {
            return "p";
        }
    }

    /** Generic superclass + covariant override so javac emits a real bridge method. */
    public static class Holder<T> {
        public T get() {
            return null;
        }
    }

    public static final class StringHolder extends Holder<String> {
        @Override
        public String get() {
            return "s";
        }
    }

    /**
     * A nested public class with a private field and a public accessor. javac may or may not
     * synthesize accessors depending on the nest-mates version; to guarantee a synthetic method
     * we declare a non-private synthetic-looking method via an enum body whose values() is
     * synthetic.
     */
    public enum SyntheticAccessor {
        ONE;
    }
}
