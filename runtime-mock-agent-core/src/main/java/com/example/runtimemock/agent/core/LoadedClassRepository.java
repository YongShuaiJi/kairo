package com.example.runtimemock.agent.core;

import com.example.runtimemock.core.ClassLoaderIdentity;
import com.example.runtimemock.core.MethodDescriptor;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public final class LoadedClassRepository {

    private final Instrumentation instrumentation;

    public LoadedClassRepository(Instrumentation instrumentation) {
        this.instrumentation = Objects.requireNonNull(instrumentation, "instrumentation");
    }

    public List<ClassInfo> search(String keyword, int limit) {
        String normalized = keyword == null ? "" : keyword.trim();
        return Arrays.stream(instrumentation.getAllLoadedClasses())
                .filter(type -> isSearchable(type, normalized))
                .sorted(Comparator.comparing(Class::getName))
                .limit(Math.max(1, limit))
                .map(this::toClassInfo)
                .toList();
    }

    public Class<?> resolveClass(String classId) {
        String[] parts = decodeClassId(classId);
        String classLoaderId = parts[0];
        String className = parts[1];
        return Arrays.stream(instrumentation.getAllLoadedClasses())
                .filter(type -> type.getName().equals(className))
                .filter(type -> ClassLoaderIdentity.idOf(type.getClassLoader()).equals(classLoaderId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Class not found: " + classId));
    }

    public List<MethodInfo> methods(String classId) {
        Class<?> type = resolveClass(classId);
        return Arrays.stream(type.getDeclaredMethods())
                .filter(method -> !method.isSynthetic() && !method.isBridge())
                .sorted(Comparator.comparing(Method::getName).thenComparing(MethodDescriptor::of))
                .map(this::toMethodInfo)
                .toList();
    }

    public Method resolveMethod(String classId, String methodName, String methodDescriptor) {
        Class<?> type = resolveClass(classId);
        return resolveMethod(type, methodName, methodDescriptor);
    }

    public Method resolveMethodTarget(String classIdOrName, String methodName, String methodDescriptor) {
        if (classIdOrName == null || classIdOrName.isBlank()) {
            throw new IllegalArgumentException("classId or className is required");
        }
        try {
            return resolveMethod(classIdOrName, methodName, methodDescriptor);
        } catch (IllegalArgumentException invalidClassId) {
            return Arrays.stream(instrumentation.getAllLoadedClasses())
                    .filter(type -> type.getName().equals(classIdOrName))
                    .map(type -> {
                        try {
                            return resolveMethod(type, methodName, methodDescriptor);
                        } catch (IllegalArgumentException ignored) {
                            return null;
                        }
                    })
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Method not found: "
                            + classIdOrName + "#" + methodName + methodDescriptor));
        }
    }

    private Method resolveMethod(Class<?> type, String methodName, String methodDescriptor) {
        return Arrays.stream(type.getDeclaredMethods())
                .filter(method -> method.getName().equals(methodName))
                .filter(method -> MethodDescriptor.of(method).equals(methodDescriptor))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Method not found: "
                        + type.getName() + "#" + methodName + methodDescriptor));
    }

    public ClassInfo toClassInfo(Class<?> type) {
        ClassLoader loader = type.getClassLoader();
        String loaderId = ClassLoaderIdentity.idOf(loader);
        return new ClassInfo(
                classId(type),
                type.getName(),
                loaderId,
                loader == null ? "bootstrap" : loader.getClass().getName(),
                instrumentation.isModifiableClass(type)
        );
    }

    public String classId(Class<?> type) {
        String raw = ClassLoaderIdentity.idOf(type.getClassLoader()) + "|" + type.getName();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public String classId(String className, String classLoaderId) {
        String raw = classLoaderId + "|" + className;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private String[] decodeClassId(String classId) {
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(classId), StandardCharsets.UTF_8);
            int separator = decoded.indexOf('|');
            if (separator <= 0 || separator == decoded.length() - 1) {
                throw new IllegalArgumentException("Invalid classId: " + classId);
            }
            return new String[]{decoded.substring(0, separator), decoded.substring(separator + 1)};
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid classId: " + classId, e);
        }
    }

    private boolean isSearchable(Class<?> type, String keyword) {
        if (type.isArray() || type.isPrimitive() || type.isAnnotation() || type.isInterface()) {
            return false;
        }
        String name = type.getName();
        if (name.startsWith("java.") || name.startsWith("jdk.") || name.startsWith("sun.")
                || name.startsWith("com.sun.") || name.startsWith("net.bytebuddy.")
                || name.startsWith("groovy.") || name.startsWith("org.codehaus.groovy.")
                || name.startsWith("com.example.runtimemock.")) {
            return false;
        }
        return keyword.isBlank() || name.contains(keyword);
    }

    private MethodInfo toMethodInfo(Method method) {
        return new MethodInfo(
                method.getName(),
                MethodDescriptor.of(method),
                method.getReturnType().getName(),
                Arrays.stream(method.getParameterTypes()).map(Class::getName).toList(),
                Arrays.stream(method.getExceptionTypes()).map(Class::getName).toList(),
                method.getModifiers(),
                Modifier.isStatic(method.getModifiers()),
                Modifier.isPrivate(method.getModifiers())
        );
    }
}
