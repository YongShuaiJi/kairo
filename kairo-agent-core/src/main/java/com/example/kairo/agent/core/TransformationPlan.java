package com.example.kairo.agent.core;

import com.example.kairo.api.EnhancementTarget;
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
 * <p>V1.3 makes the plan target-aware: it partitions the registered
 * {@link EnhancementTarget}s for a class into method, constructor and call-site
 * targets and applies the corresponding weaving &mdash; method {@link Advice}
 * (V1.2 void/value), constructor {@link Advice} and a call-site ASM visitor.
 * The real agent transformation and the read-only preview build the same plan
 * and call {@link #apply(DynamicType.Builder)} the same way, so V1.1's preview
 * contract and V1.2's method behaviour are preserved exactly while new locations
 * flow through the same plan.
 *
 * <p>The plan is immutable and built transiently for one transformation or preview
 * pass. It may carry the owning {@code ClassLoader} of the class being transformed
 * so the call-site visitor can re-read the original bytes for its maxLocals
 * pre-scan; the plan is not retained after the pass, so the loader is not pinned.
 */
public final class TransformationPlan {

    public static final TransformationPlan EMPTY = new TransformationPlan(
            Collections.emptySet(), Collections.emptySet(), Collections.emptySet(),
            null, null, null, true, null);

    private final Set<MethodSignature> methodTargets;
    private final Set<MethodSignature> constructorTargets;
    private final Set<EnhancementTarget> callSiteTargets;
    private final ElementMatcher.Junction<MethodDescription> voidMatcher;
    private final ElementMatcher.Junction<MethodDescription> valueMatcher;
    private final ElementMatcher.Junction<MethodDescription> constructorMatcher;
    private final boolean empty;
    private final ClassLoader classLoader;

    private TransformationPlan(Set<MethodSignature> methodTargets,
                               Set<MethodSignature> constructorTargets,
                               Set<EnhancementTarget> callSiteTargets,
                               ElementMatcher.Junction<MethodDescription> voidMatcher,
                               ElementMatcher.Junction<MethodDescription> valueMatcher,
                               ElementMatcher.Junction<MethodDescription> constructorMatcher,
                               boolean empty, ClassLoader classLoader) {
        this.methodTargets = methodTargets;
        this.constructorTargets = constructorTargets;
        this.callSiteTargets = callSiteTargets;
        this.voidMatcher = voidMatcher;
        this.valueMatcher = valueMatcher;
        this.constructorMatcher = constructorMatcher;
        this.empty = empty;
        this.classLoader = classLoader;
    }

    public static TransformationPlan from(InstrumentationRegistry registry, String className, ClassLoader loader) {
        return from(registry, className, com.example.kairo.core.ClassLoaderIdentity.idOf(loader), loader);
    }

    public static TransformationPlan from(InstrumentationRegistry registry, String className, String classLoaderId) {
        return from(registry, className, classLoaderId, null);
    }

    private static TransformationPlan from(InstrumentationRegistry registry, String className,
                                           String classLoaderId, ClassLoader loader) {
        Set<EnhancementTarget> targets = registry.targetsOf(className, classLoaderId);
        if (targets == null || targets.isEmpty()) {
            return EMPTY;
        }
        Set<MethodSignature> methodTargets = new LinkedHashSet<>();
        Set<MethodSignature> constructorTargets = new LinkedHashSet<>();
        Set<EnhancementTarget> callSiteTargets = new LinkedHashSet<>();
        for (EnhancementTarget target : targets) {
            MethodSignature signature = new MethodSignature(
                    target.method().className(),
                    target.method().classLoaderId(),
                    target.method().methodName(),
                    target.method().methodDescriptor());
            if (target.location().isCallSiteLocation()) {
                callSiteTargets.add(target);
            } else if (target.location().isConstructorLocation()) {
                constructorTargets.add(signature);
            } else {
                methodTargets.add(signature);
            }
        }
        if (methodTargets.isEmpty() && constructorTargets.isEmpty() && callSiteTargets.isEmpty()) {
            return EMPTY;
        }
        ElementMatcher.Junction<MethodDescription> methodMatcher = MethodMatchers.methods(methodTargets);
        ElementMatcher.Junction<MethodDescription> voidMatcher = methodMatcher.and(returns(void.class));
        ElementMatcher.Junction<MethodDescription> valueMatcher = methodMatcher.and(not(returns(void.class)));
        ElementMatcher.Junction<MethodDescription> constructorMatcher = MethodMatchers.constructors(constructorTargets);
        return new TransformationPlan(
                Collections.unmodifiableSet(methodTargets),
                Collections.unmodifiableSet(constructorTargets),
                Collections.unmodifiableSet(callSiteTargets),
                voidMatcher, valueMatcher, constructorMatcher, false, loader);
    }

    public DynamicType.Builder<?> apply(DynamicType.Builder<?> builder) {
        if (empty) {
            return builder;
        }
        DynamicType.Builder<?> next = builder;
        if (!methodTargets.isEmpty()) {
            next = next
                    .visit(Advice.to(VoidMethodAdvice.class).on(voidMatcher))
                    .visit(Advice.to(ValueMethodAdvice.class).on(valueMatcher));
        }
        if (!constructorTargets.isEmpty()) {
            next = next
                    .visit(Advice.to(ConstructorAdvice.class).on(constructorMatcher))
                    .visit(new ConstructorThrowVisitorWrapper(constructorTargets));
        }
        if (!callSiteTargets.isEmpty()) {
            next = next.visit(CallSiteVisitorWrapper.forTargets(callSiteTargets, classLoader));
        }
        return next;
    }

    public boolean isEmpty() {
        return empty;
    }

    public int targetMethodCount() {
        return methodTargets.size() + constructorTargets.size();
    }

    public int callSiteCount() {
        return callSiteTargets.size();
    }

    public Set<String> adviceTypes() {
        if (empty) {
            return Collections.emptySet();
        }
        Set<String> types = new LinkedHashSet<>();
        if (!methodTargets.isEmpty()) {
            types.add("VOID");
            types.add("VALUE");
        }
        if (!constructorTargets.isEmpty()) {
            types.add("CONSTRUCTOR");
        }
        if (!callSiteTargets.isEmpty()) {
            types.add("CALL_SITE");
        }
        return Collections.unmodifiableSet(types);
    }

    public Set<MethodSignature> methods() {
        Set<MethodSignature> all = new LinkedHashSet<>(methodTargets);
        all.addAll(constructorTargets);
        return Collections.unmodifiableSet(all);
    }

    public Set<EnhancementTarget> callSiteTargets() {
        return callSiteTargets;
    }
}
