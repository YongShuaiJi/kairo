package com.example.kairo.agent.core;

import com.example.kairo.api.bytecode.ClassIdentity;
import com.example.kairo.core.ClassLoaderIdentity;
import com.example.kairo.core.MethodDescriptor;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

public final class LoadedClassRepository {

    private final Instrumentation instrumentation;

    public LoadedClassRepository(Instrumentation instrumentation) {
        this.instrumentation = Objects.requireNonNull(instrumentation, "instrumentation");
    }

    public List<ClassInfo> search(String keyword, int limit) {
        String normalized = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(instrumentation.getAllLoadedClasses())
                .filter(this::isSearchable)
                .filter(type -> matches(type, normalized))
                .sorted(Comparator.comparing(Class::getName))
                .limit(Math.max(1, limit))
                .map(this::toClassInfo)
                .toList();
    }

    public Class<?> resolveClass(String classId) {
        return findClass(classId)
                .orElseThrow(() -> new IllegalArgumentException("Class not found: " + classId));
    }

    /**
     * Resolve a class by its {@link #classId(Class)}, returning empty instead of
     * throwing when the class is not currently loaded. A malformed {@code classId}
     * still throws {@link IllegalArgumentException} so callers can distinguish a
     * bad identifier (400) from a valid one whose class is simply not present (404).
     */
    public Optional<Class<?>> findClass(String classId) {
        String[] parts = decodeClassId(classId);
        String classLoaderId = parts[0];
        String className = parts[1];
        for (Class<?> type : instrumentation.getAllLoadedClasses()) {
            if (type.getName().equals(className)
                    && ClassLoaderIdentity.idOf(type.getClassLoader()).equals(classLoaderId)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }

    /**
     * Find a live {@code ClassLoader} by its stable id (the value
     * {@link ClassLoaderIdentity#idOf(ClassLoader)} produces), scanning the loaders of
     * all currently loaded classes. The canonical {@code "bootstrap"} id resolves to the
     * bootstrap loader, which is {@code null} &mdash; since an {@code Optional} cannot
     * carry {@code null}, callers that need to compile against the bootstrap loader should
     * branch on {@link ClassLoaderIdentity#BOOTSTRAP} first (the compile factory then
     * substitutes the agent ClassLoader as the Groovy parent, exactly as it does for a
     * target method defined by a JDK class).
     *
     * <p>Used by the script-compile command, which is given only a target loader id (no
     * class name) and must compile against that loader so business types resolve. Returns
     * empty when no loaded class is owned by that loader &mdash; the caller reports a
     * clear "loader not found" diagnostic rather than compiling against the wrong loader.
     */
    public Optional<ClassLoader> findClassLoader(String classLoaderId) {
        Objects.requireNonNull(classLoaderId, "classLoaderId");
        if (ClassLoaderIdentity.BOOTSTRAP.equals(classLoaderId)) {
            return Optional.empty();
        }
        for (Class<?> type : instrumentation.getAllLoadedClasses()) {
            ClassLoader loader = type.getClassLoader();
            if (loader != null && ClassLoaderIdentity.idOf(loader).equals(classLoaderId)) {
                return Optional.of(loader);
            }
        }
        return Optional.empty();
    }

    /**
     * Decode a {@link #classId(Class)} into the frozen {@link ClassIdentity} without
     * resolving a live {@code Class}. This is the identity-only path used by read-only
     * bytecode routes that operate on stored snapshots and journal history, which may
     * outlive the {@code Class} they were captured from. A malformed {@code classId}
     * throws {@link IllegalArgumentException}.
     */
    public ClassIdentity toClassIdentity(String classId) {
        String[] parts = decodeClassId(classId);
        return new ClassIdentity(parts[1], parts[0]);
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

    private boolean isSearchable(Class<?> type) {
        if (type.isArray() || type.isPrimitive() || type.isAnnotation() || type.isInterface()) {
            return false;
        }
        String name = type.getName();
        if (name.startsWith("java.") || name.startsWith("jdk.") || name.startsWith("sun.")
                || name.startsWith("com.sun.") || name.startsWith("net.bytebuddy.")
                || name.startsWith("groovy.") || name.startsWith("org.codehaus.groovy.")
                || name.startsWith("com.example.kairo.")) {
            return false;
        }
        return true;
    }

    private boolean matches(Class<?> type, String keyword) {
        if (keyword.isBlank()) {
            return true;
        }
        String className = type.getName().toLowerCase(Locale.ROOT);
        if (className.contains(keyword)) {
            return true;
        }
        try {
            return Arrays.stream(type.getDeclaredMethods())
                    .filter(method -> !method.isSynthetic() && !method.isBridge())
                    .anyMatch(method -> {
                        String methodName = method.getName().toLowerCase(Locale.ROOT);
                        String target = className + "#" + methodName + MethodDescriptor.of(method).toLowerCase(Locale.ROOT);
                        return methodName.contains(keyword) || target.contains(keyword);
                    });
        } catch (LinkageError | SecurityException ignored) {
            return false;
        }
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
