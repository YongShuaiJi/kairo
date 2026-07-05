package com.example.kairo.agent.bootstrap;

import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;

public final class BootstrapJarInstaller {

    private BootstrapJarInstaller() {
    }

    public static void install(Instrumentation instrumentation, Path jarPath) {
        if (jarPath == null) {
            return;
        }
        if (!Files.isRegularFile(jarPath)) {
            throw new IllegalArgumentException("Bootstrap jar does not exist: " + jarPath);
        }
        try {
            instrumentation.appendToBootstrapClassLoaderSearch(new JarFile(jarPath.toFile()));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot append bootstrap jar: " + jarPath, e);
        }
    }
}
