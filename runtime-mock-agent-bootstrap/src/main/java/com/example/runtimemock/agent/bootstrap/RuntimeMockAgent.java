package com.example.runtimemock.agent.bootstrap;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.InvocationTargetException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class RuntimeMockAgent {

    private static final String DEFAULT_LAUNCHER = "com.example.runtimemock.agent.server.AgentCoreLauncher";

    private static volatile AutoCloseable coreHandle;

    private RuntimeMockAgent() {
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
        synchronized (RuntimeMockAgent.class) {
            if (coreHandle != null) {
                if (!reload) {
                    return;
                }
                try {
                    coreHandle.close();
                } catch (Exception e) {
                    System.err.println("[runtime-mock] Agent reload failed to stop old core: " + e);
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
                        RuntimeMockAgent.class.getClassLoader()
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
            } catch (Throwable throwable) {
                Throwable failure = throwable;
                if (throwable instanceof InvocationTargetException) {
                    Throwable targetException = ((InvocationTargetException) throwable).getTargetException();
                    if (targetException != null) {
                        failure = targetException;
                    }
                }
                System.err.println("[runtime-mock] Agent bootstrap failed open: " + failure);
                failure.printStackTrace(System.err);
            }
        }
    }

    private static Path resolveCoreJar(AgentArguments arguments) {
        Path explicit = arguments.pathValue("coreJar");
        if (explicit == null) {
            explicit = arguments.pathValue(isModernRuntime() ? "modernCoreJar" : "legacyCoreJar");
        }
        if (explicit == null) {
            explicit = systemPropertyPath("runtime.mock.core.jar");
        }
        if (explicit != null) {
            return requireFile(explicit, "Agent core jar");
        }
        Path bootstrapJar = currentJarPath();
        Path directory = Files.isDirectory(bootstrapJar) ? bootstrapJar : bootstrapJar.getParent();
        String preferred = isModernRuntime()
                ? "runtime-mock-agent-core-modern.jar"
                : "runtime-mock-agent-core-legacy.jar";
        Path sibling = directory.resolve(preferred);
        if (Files.isRegularFile(sibling)) {
            return sibling;
        }
        Path fallback = directory.resolve("runtime-mock-agent-core.jar");
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
            return Paths.get(RuntimeMockAgent.class.getProtectionDomain()
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
