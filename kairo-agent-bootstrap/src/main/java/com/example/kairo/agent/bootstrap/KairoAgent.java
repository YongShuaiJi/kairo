package com.example.kairo.agent.bootstrap;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.InvocationTargetException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class KairoAgent {

    private static final String DEFAULT_LAUNCHER = "com.example.kairo.agent.server.AgentCoreLauncher";

    private static volatile AutoCloseable coreHandle;

    private KairoAgent() {
    }

    public static void premain(String agentArgs, Instrumentation instrumentation) {
        start(agentArgs, instrumentation, "premain");
    }

    public static void agentmain(String agentArgs, Instrumentation instrumentation) {
        start(agentArgs, instrumentation, "attach");
    }

    private static void start(String agentArgs, Instrumentation instrumentation, String loadMode) {
        AgentArguments arguments = AgentArguments.parse(agentArgs);
        boolean reload = "true".equalsIgnoreCase(arguments.stringValue("reload", "false"));
        synchronized (KairoAgent.class) {
            if (coreHandle != null) {
                if (!reload) {
                    return;
                }
                try {
                    coreHandle.close();
                } catch (Exception e) {
                    logFailure("agent.bootstrap.reload_stop_failed", loadMode, e);
                } finally {
                    coreHandle = null;
                }
            }
            try {
                if (arguments.bootstrapJar() != null) {
                    BootstrapJarInstaller.install(instrumentation, arguments.bootstrapJar());
                }
                Path coreJar = resolveCoreJar(arguments);
                IsolatedAgentClassLoader coreClassLoader = new IsolatedAgentClassLoader(
                        new URL[]{coreJar.toUri().toURL()},
                        KairoAgent.class.getClassLoader()
                );
                String launcherClassName = arguments.stringValue("coreLauncher", DEFAULT_LAUNCHER);
                Class<?> launcherClass = Class.forName(launcherClassName, true, coreClassLoader);
                Object handle = launcherClass
                        .getMethod("start", String.class, Instrumentation.class, String.class)
                        .invoke(null, agentArgs, instrumentation, loadMode);
                coreHandle = handle instanceof AutoCloseable ? (AutoCloseable) handle : new AutoCloseable() {
                    @Override
                    public void close() {
                        // no-op
                    }
                };
                System.err.println("event=\"agent.bootstrap.started\" loadMode=\""
                        + safe(loadMode) + "\" launcher=\"" + safe(launcherClassName) + "\"");
            } catch (Throwable throwable) {
                Throwable failure = throwable;
                if (throwable instanceof InvocationTargetException) {
                    Throwable targetException = ((InvocationTargetException) throwable).getTargetException();
                    if (targetException != null) {
                        failure = targetException;
                    }
                }
                logFailure("agent.bootstrap.failed_open", loadMode, failure);
            }
        }
    }

    /** Bootstrap stays dependency-free/JDK 8 compatible, so it uses a tiny local safe formatter. */
    private static void logFailure(String event, String loadMode, Throwable failure) {
        StringBuilder stack = new StringBuilder();
        StackTraceElement[] trace = failure == null ? new StackTraceElement[0] : failure.getStackTrace();
        for (int i = 0; i < trace.length && i < 8; i++) {
            if (i > 0) {
                stack.append(" <- ");
            }
            stack.append(trace[i]);
        }
        System.err.println("event=\"" + safe(event) + "\" loadMode=\"" + safe(loadMode)
                + "\" failureType=\"" + safe(failure == null ? "" : failure.getClass().getName())
                + "\" failure=\"" + safe(failure == null ? "" : failure.getMessage())
                + "\" failureStack=\"" + safe(stack.toString()) + "\"");
    }

    private static String safe(String value) {
        String sanitized = value == null ? "" : value
                .replaceAll("[\\r\\n\\t]+", " ")
                .replaceAll("(?i)(authorization|cookie|credential|password|secret|token|api[-_]?key)"
                        + "(\\s*[:=]\\s*)([^\\s,;]+)", "$1$2[REDACTED]")
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");
        return sanitized.length() <= 1000 ? sanitized : sanitized.substring(0, 1000) + "...";
    }

    private static Path resolveCoreJar(AgentArguments arguments) {
        Path explicit = arguments.pathValue("coreJar");
        if (explicit == null) {
            explicit = arguments.pathValue(isModernRuntime() ? "modernCoreJar" : "legacyCoreJar");
        }
        if (explicit == null) {
            explicit = systemPropertyPath("kairo.core.jar");
        }
        if (explicit != null) {
            return requireFile(explicit, "Agent core jar");
        }
        Path bootstrapJar = currentJarPath();
        Path directory = Files.isDirectory(bootstrapJar) ? bootstrapJar : bootstrapJar.getParent();
        String preferred = isModernRuntime()
                ? "kairo-agent-core-modern.jar"
                : "kairo-agent-core-legacy.jar";
        Path sibling = directory.resolve(preferred);
        if (Files.isRegularFile(sibling)) {
            return sibling;
        }
        Path fallback = directory.resolve("kairo-agent-core.jar");
        return requireFile(fallback, "Agent core jar");
    }

    private static Path requireFile(Path path, String description) {
        Path absolute = path.toAbsolutePath().normalize();
        if (!Files.isRegularFile(absolute)) {
            throw new IllegalArgumentException(description + " not found: " + absolute);
        }
        return absolute;
    }

    private static Path systemPropertyPath(String name) {
        String value = System.getProperty(name);
        return value == null || value.trim().isEmpty() ? null : Paths.get(value);
    }

    private static boolean isModernRuntime() {
        return runtimeFeatureVersion() >= 17;
    }

    private static Path currentJarPath() {
        try {
            return Paths.get(KairoAgent.class.getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("Cannot resolve agent bootstrap location", e);
        }
    }

    private static int runtimeFeatureVersion() {
        String version = System.getProperty("java.specification.version", "8");
        if (version.startsWith("1.")) {
            version = version.substring(2);
        }
        int dot = version.indexOf('.');
        if (dot >= 0) {
            version = version.substring(0, dot);
        }
        try {
            return Integer.parseInt(version);
        } catch (NumberFormatException ignored) {
            return 8;
        }
    }
}
