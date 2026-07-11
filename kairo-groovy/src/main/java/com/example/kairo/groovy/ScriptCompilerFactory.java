package com.example.kairo.groovy;

import com.example.kairo.api.MockRule;

import java.lang.reflect.Method;

/**
 * Compiles a rule's script against the ClassLoader that owns the target method, so business
 * types that are only visible to that loader resolve at compile time and run time.
 *
 * <p>Implementations own the compilation cache lifecycle: the cache weakly associates compiled
 * scripts with their target ClassLoader, so recompiling for a loader that has since been
 * discarded does not pin it. The interface lives in {@code kairo-groovy} so the core rule
 * publisher can depend on it without depending on the agent layer; the ClassLoader-aware
 * implementation itself lives in the agent runtime.
 */
public interface ScriptCompilerFactory {

    /**
     * Compile the script declared by {@code rule} for the method {@code targetMethod}. The
     * method's declaring class determines the target ClassLoader; the rule's capability profile
     * and policy revision select the security policy and revision recorded in the metadata.
     */
    CompiledMockScript compile(Method targetMethod, MockRule rule);
}
