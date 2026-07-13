package com.example.kairo.api;

import com.example.kairo.api.bytecode.ClassIdentity;
import com.example.kairo.api.bytecode.ClassMetadata;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V1.5 &sect;2 / &sect;3.1 foundation: support levels, ClassMetadata enrichment
 * (attached alongside the identity pair, never folded into equality) and the
 * ClassSelector / ResolvedTarget / ProxyAnalysis envelopes.
 */
class ModernJvmApiFoundationTest {

    @Test
    void supportLevelStableOnlySupported() {
        assertThat(SupportLevel.SUPPORTED.isStable()).isTrue();
        assertThat(SupportLevel.SUPPORTED.isAttemptable()).isTrue();
        for (SupportLevel level : SupportLevel.values()) {
            assertThat(level.isAttemptable()).isEqualTo(level != SupportLevel.UNSUPPORTED);
        }
        assertThat(SupportLevel.LIMITED.isStable()).isFalse();
        assertThat(SupportLevel.UNSUPPORTED.isStable()).isFalse();
        assertThat(SupportLevel.UNSUPPORTED.isAttemptable()).isFalse();
    }

    @Test
    void classMetadataEnrichmentDoesNotAffectIdentityEquality() {
        ClassIdentity pair = new ClassIdentity("com.example.Foo", "loader-1");
        ClassMetadata a = ClassMetadata.builder()
                .identity(pair)
                .loaderClassName("org.catalina.WebappLoader")
                .parentLoaderId("loader-0")
                .moduleName("my.module")
                .namedModule(true)
                .codeSource("file:/app/foo.jar")
                .protectionDomainSummary("pd-1")
                .bytecodeHash("abc123")
                .supportLevel(SupportLevel.SUPPORTED)
                .build();
        ClassMetadata b = ClassMetadata.builder()
                .identity(pair)
                .supportLevel(SupportLevel.LIMITED)
                .build();

        // The identity pair is the stable key; metadata enrichment differs but the
        // underlying identity still equals itself.
        assertThat(a.identity()).isEqualTo(b.identity());
        assertThat(a.identity()).hasSameHashCodeAs(b.identity());
        assertThat(a.supportLevel()).isEqualTo(SupportLevel.SUPPORTED);
        assertThat(b.supportLevel()).isEqualTo(SupportLevel.LIMITED);
        assertThat(a.loaderClassName()).isEqualTo("org.catalina.WebappLoader");
        assertThat(b.loaderClassName()).isNull();
    }

    @Test
    void classMetadataDefaultsSupportLevelToSupported() {
        ClassMetadata metadata = ClassMetadata.builder()
                .identity(new ClassIdentity("com.example.Foo", "loader-1"))
                .build();
        assertThat(metadata.supportLevel()).isEqualTo(SupportLevel.SUPPORTED);
        assertThat(metadata.namedModule()).isFalse();
    }

    @Test
    void classSelectorExactVsFuzzy() {
        ClassSelector exact = ClassSelector.builder()
                .className("com.example.Foo")
                .classLoaderId("loader-1")
                .build();
        assertThat(exact.isExact()).isTrue();
        assertThat(exact.allMatch()).isFalse();

        ClassSelector fuzzy = ClassSelector.builder()
                .className("com.example.Foo")
                .loaderClassName("TomcatEmbeddedWebappClassLoader")
                .allMatch(true)
                .build();
        assertThat(fuzzy.isExact()).isFalse();
        assertThat(fuzzy.classLoaderId()).isNull();
        assertThat(fuzzy.loaderClassName()).isEqualTo("TomcatEmbeddedWebappClassLoader");
        assertThat(fuzzy.allMatch()).isTrue();
    }

    @Test
    void classSelectorRejectsBlankClassName() {
        assertThatThrownBy(() -> ClassSelector.builder().className(" ").build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void resolvedTargetCarriesIdentityAndLevel() {
        MethodSelector method = new MethodSelector("com.example.Foo", "loader-1", "score", "(I)I");
        EnhancementTarget target = EnhancementTarget.of(method, EnhancementLocation.METHOD_RETURN);
        ClassIdentity identity = new ClassIdentity("com.example.Foo", "loader-1");
        ResolvedTarget resolved = new ResolvedTarget(target, identity, null,
                SupportLevel.LIMITED, ProxyType.CGLIB, java.util.List.of("proxy subclass"), 123L);
        assertThat(resolved.identity()).isEqualTo(identity);
        assertThat(resolved.supportLevel()).isEqualTo(SupportLevel.LIMITED);
        assertThat(resolved.proxyType()).isEqualTo(ProxyType.CGLIB);
        assertThat(resolved.notes()).containsExactly("proxy subclass");
        assertThat(resolved.resolvedAtMillis()).isEqualTo(123L);
    }

    @Test
    void proxyAnalysisIsProxyDetection() {
        ProxyAnalysis proxy = new ProxyAnalysis(ProxyType.JDK_PROXY,
                java.util.List.of("com.example.If"), "java.lang.reflect.Proxy",
                java.util.List.of(), null, "enhance target interface", SupportLevel.LIMITED);
        assertThat(proxy.isProxy()).isTrue();
        assertThat(proxy.proxyInterfaces()).containsExactly("com.example.If");

        ProxyAnalysis plain = new ProxyAnalysis(ProxyType.PLAIN, java.util.List.of(),
                "com.example.Foo", java.util.List.of(), null, null, SupportLevel.SUPPORTED);
        assertThat(plain.isProxy()).isFalse();
    }
}
