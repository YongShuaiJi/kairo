package com.example.kairo.agent.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V1.5 &sect;4.1 / &sect;6: the framework-loader recognizer classifies well-known Spring Boot /
 * Tomcat / Jetty / plugin ClassLoader class names so the platform can render the loader tree with
 * framework labels. The agent's core identity never depends on framework classes; this is a
 * human-readable label only.
 */
class FrameworkLoaderRecognizerTest {

    @Test
    void recognizesSpringBootLoaders() {
        assertThat(FrameworkLoaderRecognizer.recognize(
                "org.springframework.boot.loader.launch.LaunchedURLClassLoader"))
                .isEqualTo("Spring Boot (LaunchedURLClassLoader)");
        assertThat(FrameworkLoaderRecognizer.recognize(
                "org.springframework.boot.loader.LaunchedURLClassLoader"))
                .isEqualTo("Spring Boot (LaunchedURLClassLoader, legacy)");
        assertThat(FrameworkLoaderRecognizer.recognize(
                "org.springframework.boot.web.embedded.tomcat.TomcatEmbeddedWebappClassLoader"))
                .isEqualTo("Spring Boot embedded Tomcat");
    }

    @Test
    void recognizesTomcatAndJettyLoaders() {
        assertThat(FrameworkLoaderRecognizer.recognize(
                "org.apache.catalina.loader.WebappClassLoader")).isEqualTo("Tomcat WebApp");
        assertThat(FrameworkLoaderRecognizer.recognize(
                "org.apache.catalina.loader.ParallelWebappClassLoader")).isEqualTo("Tomcat WebApp (parallel)");
        assertThat(FrameworkLoaderRecognizer.recognize(
                "org.eclipse.jetty.webapp.WebAppClassLoader")).isEqualTo("Jetty WebApp");
    }

    @Test
    void recognizesPluginLoaderByClassName() {
        assertThat(FrameworkLoaderRecognizer.recognize("com.acme.PluginClassLoader"))
                .isEqualTo("Plugin ClassLoader");
    }

    @Test
    void returnsNullForBootstrapSystemAndPlainLoaders() {
        assertThat(FrameworkLoaderRecognizer.recognize(null)).isNull();
        assertThat(FrameworkLoaderRecognizer.recognize("bootstrap")).isNull();
        assertThat(FrameworkLoaderRecognizer.recognize("jdk.internal.loader.ClassLoaders$AppClassLoader")).isNull();
        assertThat(FrameworkLoaderRecognizer.recognize("java.net.URLClassLoader")).isNull();
    }
}
