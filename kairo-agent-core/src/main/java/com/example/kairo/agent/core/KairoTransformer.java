package com.example.kairo.agent.core;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.utility.JavaModule;

import java.security.ProtectionDomain;

/**
 * AgentBuilder transformer that delegates to a freshly built {@link TransformationPlan}.
 *
 * <p>All rule resolution, method matching and Advice selection live in
 * {@link TransformationPlan}; this class is now a thin adapter so that the real
 * agent transformation and the read-only preview share the exact same plan logic.
 */
public final class KairoTransformer implements AgentBuilder.Transformer {

    private final InstrumentationRegistry registry;

    public KairoTransformer(InstrumentationRegistry registry) {
        this.registry = registry;
    }

    @Override
    public DynamicType.Builder<?> transform(DynamicType.Builder<?> builder,
                                            TypeDescription typeDescription,
                                            ClassLoader classLoader,
                                            JavaModule module,
                                            ProtectionDomain protectionDomain) {
        return TransformationPlan.from(registry, typeDescription.getName(), classLoader).apply(builder);
    }
}
