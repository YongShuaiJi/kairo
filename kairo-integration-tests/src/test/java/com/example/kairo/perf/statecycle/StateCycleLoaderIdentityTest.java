package com.example.kairo.perf.statecycle;

import org.junit.jupiter.api.Test;

import java.net.URL;
import java.net.URLClassLoader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Focused deterministic tests for the parent/child loader identity assertion
 * (defect 1: the inline check was inverted, so distinct loader ids threw). No JVM
 * agent required: {@link com.example.kairo.core.ClassLoaderIdentity#idOf} assigns a
 * unique sequential id per ClassLoader instance, so two genuinely distinct loaders
 * carry distinct ids and a shared loader carries one id.
 */
class StateCycleLoaderIdentityTest {

    @Test
    void distinctClassLoadersDoNotThrow() throws Exception {
        try (URLClassLoader a = new URLClassLoader(new URL[]{}, ClassLoader.getSystemClassLoader());
             URLClassLoader b = new URLClassLoader(new URL[]{}, ClassLoader.getSystemClassLoader())) {
            assertThat(a).isNotSameAs(b);
            // Fixed assertion: distinct loader ids must NOT throw.
            StateCycleHarness.assertClassLoaderIdsDistinct(a, b);
        }
    }

    @Test
    void sameClassLoaderThrows() throws Exception {
        try (URLClassLoader a = new URLClassLoader(new URL[]{}, ClassLoader.getSystemClassLoader())) {
            assertThatThrownBy(() -> StateCycleHarness.assertClassLoaderIdsDistinct(a, a))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("loader-identity-ids");
        }
    }
}
