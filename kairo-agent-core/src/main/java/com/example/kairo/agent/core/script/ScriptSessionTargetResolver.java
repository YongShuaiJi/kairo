package com.example.kairo.agent.core.script;

import com.example.kairo.api.MethodSelector;

/**
 * Resolves a {@link MethodSelector} carried by a {@link com.example.kairo.api.ScriptSessionSpec}
 * into a live {@link ScriptSessionTarget}. Kept as a functional interface so the session manager
 * stays decoupled from {@code LoadedClassRepository} (and thus from {@code Instrumentation}),
 * letting the lifecycle be unit-tested without attaching an agent.
 */
@FunctionalInterface
public interface ScriptSessionTargetResolver {

    /**
     * Resolve the target. Throws {@link IllegalArgumentException} (or a subclass) when the target
     * class is not loaded or the method cannot be found, so creation fails fast with a clear
     * cause rather than deferring the error to compile time.
     */
    ScriptSessionTarget resolve(MethodSelector target);
}
