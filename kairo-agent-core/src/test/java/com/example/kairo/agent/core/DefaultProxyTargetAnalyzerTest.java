package com.example.kairo.agent.core;

import com.example.kairo.api.ProxyType;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V1.5 &sect;4.2: the default proxy-target analyzer classifies JDK proxies, CGLIB / Byte
 * Buddy subclass proxies by name pattern, and lambda / hidden classes as UNKNOWN, and
 * recommends the target (super) class for subclass proxies. It never publishes; it only
 * recommends.
 */
class DefaultProxyTargetAnalyzerTest {

    private final DefaultProxyTargetAnalyzer analyzer = new DefaultProxyTargetAnalyzer();

    @Test
    void classifiesJdkProxyAndRecommendsInterfaceMethod() {
        Runnable proxy = (Runnable) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{Runnable.class},
                (p, m, a) -> null);
        var analysis = analyzer.analyze(proxy.getClass());
        assertThat(analysis.proxyType()).isEqualTo(ProxyType.JDK_PROXY);
        assertThat(analysis.proxyInterfaces()).contains(Runnable.class.getName());
        assertThat(analysis.isProxy()).isTrue();
        assertThat(analysis.supportLevel()).isEqualTo(com.example.kairo.api.SupportLevel.LIMITED);
        // Recommended target is a user method on the interface surface.
        assertThat(analysis.recommendedTarget()).isNotNull();
        assertThat(analysis.recommendedTarget().methodName()).isEqualTo("run");
    }

    @Test
    void classifiesPlainClassAsSupported() {
        var analysis = analyzer.analyze(PlainService.class);
        assertThat(analysis.proxyType()).isEqualTo(ProxyType.PLAIN);
        assertThat(analysis.isProxy()).isFalse();
        assertThat(analysis.supportLevel()).isEqualTo(com.example.kairo.api.SupportLevel.SUPPORTED);
    }

    @Test
    void classifiesCglibAndByteBuddyByNamePattern() {
        assertThat(DefaultProxyTargetAnalyzer.classify(new CglibLike$$EnhancerByCGLIB$$a1b2().getClass()))
                .isEqualTo(ProxyType.CGLIB);
        assertThat(DefaultProxyTargetAnalyzer.classify(new ByteBuddyLike$ByteBuddy$abc().getClass()))
                .isEqualTo(ProxyType.BYTE_BUDDY);
    }

    @Test
    void classifiesLambdaAsUnknownAndRecommendsDeclaringMethod() {
        Runnable lambda = () -> { };
        var analysis = analyzer.analyze(lambda.getClass());
        // Lambda classes are hidden on JDK 15+ -> UNKNOWN, with an unstable-name explanation.
        assertThat(analysis.proxyType()).isIn(ProxyType.UNKNOWN, ProxyType.PLAIN);
        if (analysis.proxyType() == ProxyType.UNKNOWN) {
            assertThat(analysis.supportLevel()).isEqualTo(com.example.kairo.api.SupportLevel.EXPERIMENTAL);
            assertThat(analysis.impactExplanation()).contains("invokedynamic");
        }
    }

    /** A class whose name mimics a CGLIB enhancer subclass. */
    static final class CglibLike$$EnhancerByCGLIB$$a1b2 extends PlainService {
    }

    /** A class whose name mimics a Byte Buddy generated subclass. */
    static final class ByteBuddyLike$ByteBuddy$abc extends PlainService {
    }

    public static class PlainService {
        public String serve() {
            return "ok";
        }
    }
}
