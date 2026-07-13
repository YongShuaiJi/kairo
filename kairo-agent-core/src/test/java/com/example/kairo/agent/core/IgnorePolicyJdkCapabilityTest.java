package com.example.kairo.agent.core;

import com.example.kairo.api.SupportLevel;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V1.5 &sect;4.1: the ignore-policy strategy object and its audited JDK-enhancement
 * capability. The ignore list is not deleted (blindly weaving JDK classes is high-risk);
 * a named JDK class may be enhanced only via an armed, audited opt-in, and that class
 * carries {@link SupportLevel#EXPERIMENTAL}.
 */
class IgnorePolicyJdkCapabilityTest {

    private final IgnorePolicy policy = new IgnorePolicy();

    @Test
    void jdkAndPlatformClassesAreIgnoredByDefault() {
        assertThat(policy.ignore("java.lang.String", null)).isTrue();
        assertThat(policy.ignore("jdk.internal.misc.Unsafe", null)).isTrue();
        assertThat(policy.ignore("com.sun.tools.javac.Main", null)).isTrue();
        assertThat(policy.ignore("org.w3c.dom.Node", null)).isTrue();
        assertThat(policy.isJdkOrPlatformClass("java.lang.String")).isTrue();
    }

    @Test
    void userClassesAreNotIgnored() {
        assertThat(policy.ignore("com.example.biz.OrderService", null)).isFalse();
        assertThat(policy.isJdkOrPlatformClass("com.example.biz.OrderService")).isFalse();
    }

    @Test
    void explicitAllowOverridesIgnoreListForNamedClass() {
        assertThat(policy.ignore("com.example.kairo.Foo", null)).isTrue(); // kairo pkg ignored
        policy.allow("com.example.kairo.Foo");
        assertThat(policy.ignore("com.example.kairo.Foo", null)).isFalse();
        policy.disallow("com.example.kairo.Foo");
        assertThat(policy.ignore("com.example.kairo.Foo", null)).isTrue();
    }

    @Test
    void jdkCapabilityIsOffByDefaultAndRefusesEverything() {
        IgnorePolicy.JdkEnhancementCapability cap = policy.jdkCapability();
        assertThat(cap.enabled()).isFalse();
        assertThat(cap.allow("java.lang.String")).isFalse();
        assertThat(cap.isAllowed("java.lang.String")).isFalse();
        assertThat(cap.supportLevelFor("java.lang.String")).isEqualTo(SupportLevel.UNSUPPORTED);
    }

    @Test
    void armingAndOptingInAuditsAtExperimentalLevel() {
        IgnorePolicy.JdkEnhancementCapability cap = policy.jdkCapability();
        cap.enable();
        assertThat(cap.enabled()).isTrue();
        assertThat(cap.allow("java.lang.String")).isTrue();
        assertThat(cap.isAllowed("java.lang.String")).isTrue();
        assertThat(cap.supportLevelFor("java.lang.String")).isEqualTo(SupportLevel.EXPERIMENTAL);
        assertThat(cap.auditLog()).contains("java.lang.String");

        // Disabling immediately withdraws the opt-in even though the audit entry remains.
        cap.disable();
        assertThat(cap.isAllowed("java.lang.String")).isFalse();
        assertThat(cap.auditLog()).contains("java.lang.String"); // audit is immutable history
    }
}
