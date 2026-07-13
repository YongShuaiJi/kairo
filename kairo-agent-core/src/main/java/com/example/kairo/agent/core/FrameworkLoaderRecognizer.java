package com.example.kairo.agent.core;

/**
 * Recognizes well-known framework ClassLoaders by class name (V1.5 &sect;4.1).
 *
 * <p>The agent's core identity never depends on framework classes &mdash; the
 * stable loader id is the single identity. This recognizer only attaches a
 * human-readable framework label so the platform can render the loader tree and
 * flag Spring Boot / Tomcat / plugin loaders for the operator. Recognition is
 * name-based so the agent carries no hard dependency on the frameworks.
 */
public enum FrameworkLoaderRecognizer {

    SPRING_BOOT_LAUNCHER("org.springframework.boot.loader.launch.LaunchedURLClassLoader",
            "Spring Boot (LaunchedURLClassLoader)"),
    SPRING_BOOT_LEGACY("org.springframework.boot.loader.LaunchedURLClassLoader",
            "Spring Boot (LaunchedURLClassLoader, legacy)"),
    TOMCAT_EMBED("org.springframework.boot.web.embedded.tomcat.TomcatEmbeddedWebappClassLoader",
            "Spring Boot embedded Tomcat"),
    TOMCAT("org.apache.catalina.loader.WebappClassLoader", "Tomcat WebApp"),
    TOMCAT_PARALLEL("org.apache.catalina.loader.ParallelWebappClassLoader", "Tomcat WebApp (parallel)"),
    JETTY("org.eclipse.jetty.webapp.WebAppClassLoader", "Jetty WebApp"),
    PLUGIN_GENERIC("PluginClassLoader", "Plugin ClassLoader");

    private final String loaderClassName;
    private final String label;

    FrameworkLoaderRecognizer(String loaderClassName, String label) {
        this.loaderClassName = loaderClassName;
        this.label = label;
    }

    public String loaderClassName() {
        return loaderClassName;
    }

    public String label() {
        return label;
    }

    /**
     * Recognize the framework label for a loader class name, or {@code null} when
     * the loader is not a recognized framework loader (the bootstrap loader, the
     * system loader, or a plain custom loader).
     */
    public static String recognize(String loaderClassName) {
        if (loaderClassName == null || "bootstrap".equals(loaderClassName)) {
            return null;
        }
        for (FrameworkLoaderRecognizer recognizer : values()) {
            if (recognizer.loaderClassName.equals(loaderClassName)) {
                return recognizer.label;
            }
        }
        if (loaderClassName.contains("Plugin")) {
            return PLUGIN_GENERIC.label;
        }
        return null;
    }
}
