package com.example.kairo.agent.core;

import com.example.kairo.api.EnhancementLocation;
import com.example.kairo.api.EnhancementTarget;
import com.example.kairo.api.MethodSelector;
import com.example.kairo.core.ClassLoaderIdentity;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Default {@link InstrumentationRegistry} backed by a concurrent map keyed by
 * class name. Each class maps to a refcounted set of {@link EnhancementTarget}s:
 * a target shared by a mock rule and a recording session is only removed once
 * both unregister, so stopping a recording never strips a still-active rule's
 * instrumentation. The transformation plan and retransform decisions are derived
 * from the live target set.
 */
public final class DefaultInstrumentationRegistry implements InstrumentationRegistry {

    private final ConcurrentHashMap<String, ConcurrentHashMap<EnhancementTarget, AtomicInteger>> targetsByClassName = new ConcurrentHashMap<>();

    public void register(EnhancementTarget target) {
        targetsByClassName.compute(target.method().className(), (className, existing) -> {
            ConcurrentHashMap<EnhancementTarget, AtomicInteger> next = new ConcurrentHashMap<>();
            if (existing != null) {
                next.putAll(existing);
            }
            next.computeIfAbsent(target, t -> new AtomicInteger(0)).incrementAndGet();
            return next;
        });
    }

    public void unregister(EnhancementTarget target) {
        targetsByClassName.computeIfPresent(target.method().className(), (className, existing) -> {
            ConcurrentHashMap<EnhancementTarget, AtomicInteger> next = new ConcurrentHashMap<>();
            next.putAll(existing);
            AtomicInteger count = next.get(target);
            if (count != null && count.decrementAndGet() <= 0) {
                next.remove(target);
            }
            return next.isEmpty() ? null : next;
        });
    }

    /**
     * V1.1/V1.2 compat: register a plain method signature as a {@code METHOD_ENTER}
     * target. The low-level bytecode-visibility paths (and the integration tests that
     * drive them) address the registry by method signature; projecting that onto a
     * method-enter target keeps them weaving the same enter/exit method Advice as
     * before without exposing the target model.
     */
    public void register(MethodSignature signature) {
        register(toMethodEnterTarget(signature));
    }

    public void unregister(MethodSignature signature) {
        unregister(toMethodEnterTarget(signature));
    }

    private static EnhancementTarget toMethodEnterTarget(MethodSignature signature) {
        MethodSelector selector = new MethodSelector(
                signature.className(),
                signature.classLoaderId(),
                signature.methodName(),
                signature.methodDescriptor());
        return EnhancementTarget.of(selector, EnhancementLocation.METHOD_ENTER);
    }

    public Set<EnhancementTarget> snapshot() {
        return targetsByClassName.values().stream()
                .flatMap(map -> map.keySet().stream())
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * V1.5 &sect;3.2: remove every target whose method selector carries the
     * collected loader's id. Targets whose selector has a {@code null} loader id
     * (legacy selectors) are left untouched. Called by the
     * {@code ClassLoaderRepository} cleaner after the loader is garbage-collected.
     *
     * @return the number of targets removed
     */
    public int clearForLoader(String classLoaderId) {
        if (classLoaderId == null) {
            return 0;
        }
        int[] removed = new int[1];
        for (String className : List.copyOf(targetsByClassName.keySet())) {
            targetsByClassName.computeIfPresent(className, (name, existing) -> {
                ConcurrentHashMap<EnhancementTarget, AtomicInteger> next = new ConcurrentHashMap<>();
                next.putAll(existing);
                var it = next.entrySet().iterator();
                while (it.hasNext()) {
                    var entry = it.next();
                    String targetLoaderId = entry.getKey().method().classLoaderId();
                    if (classLoaderId.equals(targetLoaderId)) {
                        it.remove();
                        removed[0]++;
                    }
                }
                return next.isEmpty() ? null : next;
            });
        }
        return removed[0];
    }

    public int typeCount() {
        return targetsByClassName.size();
    }

    public int methodCount() {
        return (int) snapshot().stream()
                .map(target -> new MethodSignature(
                        target.method().className(),
                        target.method().classLoaderId(),
                        target.method().methodName(),
                        target.method().methodDescriptor()))
                .distinct()
                .count();
    }

    public int targetCount() {
        return snapshot().size();
    }

    @Override
    public boolean containsType(String className, ClassLoader classLoader) {
        return containsType(className, ClassLoaderIdentity.idOf(classLoader));
    }

    @Override
    public boolean containsType(String className, String classLoaderId) {
        return !targetsOf(className, classLoaderId).isEmpty();
    }

    @Override
    public Set<EnhancementTarget> targetsOf(String className, ClassLoader classLoader) {
        return targetsOf(className, ClassLoaderIdentity.idOf(classLoader));
    }

    @Override
    public Set<EnhancementTarget> targetsOf(String className, String classLoaderId) {
        ConcurrentHashMap<EnhancementTarget, AtomicInteger> targets = targetsByClassName.get(className);
        if (targets == null || targets.isEmpty()) {
            return Set.of();
        }
        return targets.keySet().stream()
                .filter(target -> {
                    String targetLoaderId = target.method().classLoaderId();
                    return targetLoaderId == null || targetLoaderId.equals(classLoaderId);
                })
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public Set<MethodSignature> methodsOf(String className, ClassLoader classLoader) {
        return methodsOf(className, ClassLoaderIdentity.idOf(classLoader));
    }

    @Override
    public Set<MethodSignature> methodsOf(String className, String classLoaderId) {
        return targetsOf(className, classLoaderId).stream()
                .map(target -> new MethodSignature(
                        target.method().className(),
                        target.method().classLoaderId(),
                        target.method().methodName(),
                        target.method().methodDescriptor()))
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Compact footprint of what must be woven for one method/constructor: whether
     * method Advice is needed, whether constructor Advice is needed, and the
     * sorted set of call-site selector cores. The agent retransforms a class only
     * when this footprint changes, so updating a method-phase rule on an already
     * instrumented method does not re-weave (preserving V1.2 behaviour), while
     * adding or removing a call-site target does.
     */
    public WeaveFootprint footprintOf(String className, String classLoaderId,
                                      String memberName, String descriptor) {
        boolean hasMethod = false;
        boolean hasConstructor = false;
        Set<String> callSiteKeys = new LinkedHashSet<>();
        for (EnhancementTarget target : targetsOf(className, classLoaderId)) {
            if (!target.method().methodName().equals(memberName)
                    || !target.method().methodDescriptor().equals(descriptor)) {
                continue;
            }
            EnhancementLocation location = target.location();
            if (location.isCallSiteLocation()) {
                callSiteKeys.add(callSiteKey(target));
            } else if (location.isConstructorLocation()) {
                hasConstructor = true;
            } else {
                hasMethod = true;
            }
        }
        return new WeaveFootprint(hasMethod, hasConstructor, sorted(callSiteKeys));
    }

    private static String callSiteKey(EnhancementTarget target) {
        var selector = target.callSiteSelector();
        return selector.owner() + "#" + selector.name() + selector.descriptor()
                + "@" + selector.opcode() + "#" + selector.occurrenceIndex();
    }

    private static List<String> sorted(Set<String> keys) {
        List<String> list = new ArrayList<>(keys);
        java.util.Collections.sort(list);
        return list;
    }

    /**
     * Immutable weave footprint of one method/constructor. Equality drives the
     * retransform decision in {@code AgentRuntime}.
     */
    public static final class WeaveFootprint {
        private final boolean hasMethodAdvice;
        private final boolean hasConstructorAdvice;
        private final List<String> callSiteKeys;

        WeaveFootprint(boolean hasMethodAdvice, boolean hasConstructorAdvice, List<String> callSiteKeys) {
            this.hasMethodAdvice = hasMethodAdvice;
            this.hasConstructorAdvice = hasConstructorAdvice;
            this.callSiteKeys = List.copyOf(callSiteKeys);
        }

        public boolean needsWeaving() {
            return hasMethodAdvice || hasConstructorAdvice || !callSiteKeys.isEmpty();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof WeaveFootprint that)) {
                return false;
            }
            return hasMethodAdvice == that.hasMethodAdvice
                    && hasConstructorAdvice == that.hasConstructorAdvice
                    && callSiteKeys.equals(that.callSiteKeys);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(hasMethodAdvice, hasConstructorAdvice, callSiteKeys);
        }
    }
}
