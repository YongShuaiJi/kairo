package com.example.kairo.agent.core;

import com.example.kairo.core.ClassLoaderIdentity;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class DefaultInstrumentationRegistry implements InstrumentationRegistry {

    private final ConcurrentHashMap<String, Set<MethodSignature>> methodsByClassName = new ConcurrentHashMap<>();

    public void register(MethodSignature signature) {
        methodsByClassName.compute(signature.className(), (className, existing) -> {
            Set<MethodSignature> next = ConcurrentHashMap.newKeySet();
            if (existing != null) {
                next.addAll(existing);
            }
            next.add(signature);
            return next;
        });
    }

    public void unregister(MethodSignature signature) {
        methodsByClassName.computeIfPresent(signature.className(), (className, existing) -> {
            Set<MethodSignature> next = ConcurrentHashMap.newKeySet();
            next.addAll(existing);
            next.remove(signature);
            return next.isEmpty() ? null : next;
        });
    }

    public Set<MethodSignature> snapshot() {
        return methodsByClassName.values().stream()
                .flatMap(Set::stream)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public int typeCount() {
        return methodsByClassName.size();
    }

    public int methodCount() {
        return snapshot().size();
    }

    @Override
    public boolean containsType(String className, ClassLoader classLoader) {
        return !methodsOf(className, classLoader).isEmpty();
    }

    @Override
    public Set<MethodSignature> methodsOf(String className, ClassLoader classLoader) {
        Set<MethodSignature> methods = methodsByClassName.get(className);
        if (methods == null || methods.isEmpty()) {
            return Set.of();
        }
        String classLoaderId = ClassLoaderIdentity.idOf(classLoader);
        return methods.stream()
                .filter(method -> method.classLoaderId() == null || method.classLoaderId().equals(classLoaderId))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
