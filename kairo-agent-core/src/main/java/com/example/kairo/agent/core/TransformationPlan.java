package com.example.kairo.agent.core;

import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatcher;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.returns;

/**
 * Reusable description of one enhancement pass over a single class.
 *
 * <p>Extracted from {@code KairoTransformer} so that the real agent transformation
 * and the read-only preview build the <em>same</em> plan and apply it the same way:
 * rule resolution ({@link InstrumentationRegistry#methodsOf}), method matching
 * ({@link MethodMatchers}) and Advice selection ({@link VoidMethodAdvice} /
 * {@link ValueMethodAdvice}) live here and are shared. The real path receives an
 * {@code AgentBuilder}-supplied builder; the preview path supplies an offline
 * {@code ByteBuddy.redefine} builder. Both call {@link #apply(DynamicType.Builder)}.
 *
 * <p>The plan is immutable and holds no {@code Class} or {@code ClassLoader}
 * reference, so it is safe to retain for diagnostics.
 */
public final class TransformationPlan {

    public static final TransformationPlan EMPTY = new TransformationPlan(
            Collections.emptySet(), null, null, true);

    private final Set<MethodSignature> methods;
    private final ElementMatcher.Junction<MethodDescription> voidMatcher;
    private final ElementMatcher.Junction<MethodDescription> valueMatcher;
    private final boolean empty;

    private TransformationPlan(Set<MethodSignature> methods,
                               ElementMatcher.Junction<MethodDescription> voidMatcher,
                               ElementMatcher.Junction<MethodDescription> valueMatcher,
                               boolean empty) {
        this.methods = methods;
        this.voidMatcher = voidMatcher;
        this.valueMatcher = valueMatcher;
        this.empty = empty;
    }

    /**
     * Build a plan for a class about to be transformed by a real AgentBuilder pass.
     */
    public static TransformationPlan from(InstrumentationRegistry registry, String className, ClassLoader loader) {
        return from(registry, className, com.example.kairo.core.ClassLoaderIdentity.idOf(loader));
    }

    /**
     * Build a plan from a binary class name and an opaque class-loader id. Used by
     * the preview path, which has no {@code ClassLoader} object, only the identity.
     */
    public static TransformationPlan from(InstrumentationRegistry registry, String className, String classLoaderId) {
        Set<MethodSignature> methods = registry.methodsOf(className, classLoaderId);
        if (methods == null || methods.isEmpty()) {
            return EMPTY;
        }
        ElementMatcher.Junction<MethodDescription> methodMatcher = MethodMatchers.from(methods);
        ElementMatcher.Junction<MethodDescription> voidMatcher = methodMatcher.and(returns(void.class));
        ElementMatcher.Junction<MethodDescription> valueMatcher = methodMatcher.and(not(returns(void.class)));
        return new TransformationPlan(methods, voidMatcher, valueMatcher, false);
    }

    /**
     * Apply the Advice weaving to a builder. Returns the builder unchanged when the
     * plan is empty, preserving the V1.0 no-op behaviour for non-registered types.
     */
    public DynamicType.Builder<?> apply(DynamicType.Builder<?> builder) {
        if (empty) {
            return builder;
        }
        return builder
                .visit(Advice.to(VoidMethodAdvice.class).on(voidMatcher))
                .visit(Advice.to(ValueMethodAdvice.class).on(valueMatcher));
    }

    public boolean isEmpty() {
        return empty;
    }

    public int targetMethodCount() {
        return methods.size();
    }

    /**
     * The Advice flavours this plan would apply. {@code VOID} covers void-returning
     * methods, {@code VALUE} covers value-returning methods.
     */
    public Set<String> adviceTypes() {
        if (empty) {
            return Collections.emptySet();
        }
        Set<String> types = new LinkedHashSet<>();
        types.add("VOID");
        types.add("VALUE");
        return Collections.unmodifiableSet(types);
    }

    public Set<MethodSignature> methods() {
        return methods;
    }
}
