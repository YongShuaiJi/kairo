package com.example.kairo.groovy;

import com.example.kairo.api.InvocationContext;
import com.example.kairo.api.MockDecision;

public interface CompiledMockScript {

    String ruleId();

    long version();

    String scriptHash();

    MockDecision execute(InvocationContext context);

    /**
     * Release global runtime caches that may retain the generated script class.
     *
     * <p>The script remains executable so a dispatch that already captured an immutable
     * rule-chain snapshot can finish safely while the rule is being unloaded.
     */
    default void releaseClassLoaderCaches() {
        // Non-Groovy implementations do not own generated classes.
    }
}
