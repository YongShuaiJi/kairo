package com.example.kairo.agent.core;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.utility.JavaModule;

import java.security.ProtectionDomain;
import java.util.Set;

import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.returns;

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
        Set<MethodSignature> methods = registry.methodsOf(typeDescription.getName(), classLoader);
        if (methods.isEmpty()) {
            return builder;
        }

        ElementMatcher.Junction<MethodDescription> methodMatcher = MethodMatchers.from(methods);
        ElementMatcher.Junction<MethodDescription> voidMatcher = methodMatcher.and(returns(void.class));
        ElementMatcher.Junction<MethodDescription> valueMatcher = methodMatcher.and(not(returns(void.class)));

        return builder
                .visit(Advice.to(VoidMethodAdvice.class).on(voidMatcher))
                .visit(Advice.to(ValueMethodAdvice.class).on(valueMatcher));
    }
}
