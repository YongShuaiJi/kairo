package com.example.kairo.agent.core;

import java.util.Set;

/**
 * Snapshot of a class's Java module state (V1.5 &sect;4.5).
 *
 * <p>Reports whether the class is in a named module, the module name, the
 * packages it exports and to whom, and the packages it opens. The agent uses
 * this to explain {@code Advice}/{@code Bridge} access failures (a package not
 * open to Kairo's module) and to open the minimal set when
 * {@link java.lang.instrument.Instrumentation#redefineModule} is required.
 */
public record ModuleInfo(
        String moduleName,
        boolean named,
        String classLoaderName,
        Set<String> packages,
        boolean classDefined
) {
}
