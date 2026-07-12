package com.example.kairo.api;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Objects;

public final class MethodMetadata {

    private final Class<?> declaringClass;
    private final String name;
    private final String descriptor;
    private final Class<?> returnType;
    private final Class<?>[] parameterTypes;
    private final Class<?>[] exceptionTypes;
    private final Method reflectMethod;
    private final Constructor<?> reflectConstructor;

    public MethodMetadata(Method reflectMethod, String descriptor) {
        this(reflectMethod.getDeclaringClass(),
                reflectMethod.getName(),
                descriptor,
                reflectMethod.getReturnType(),
                reflectMethod.getParameterTypes().clone(),
                reflectMethod.getExceptionTypes().clone(),
                Objects.requireNonNull(reflectMethod, "reflectMethod"),
                null);
    }

    /**
     * Build metadata for a constructor. A constructor's "return type" is the
     * declaring class (the newly constructed object), its name is
     * {@code "<init>"} and {@link #reflectMethod()} returns {@code null}; use
     * {@link #reflectConstructor()} to recover the reflective constructor.
     */
    public static MethodMetadata forConstructor(Constructor<?> constructor, String descriptor) {
        Constructor<?> ctor = Objects.requireNonNull(constructor, "constructor");
        return new MethodMetadata(ctor.getDeclaringClass(),
                "<init>",
                Objects.requireNonNull(descriptor, "descriptor"),
                ctor.getDeclaringClass(),
                ctor.getParameterTypes().clone(),
                ctor.getExceptionTypes().clone(),
                null,
                ctor);
    }

    /**
     * Build method metadata from explicit components, used by call-site
     * enhancement where the caller is identified by descriptor only and no live
     * reflective {@code Method} is available on the hot path.
     */
    public static MethodMetadata forMethod(Class<?> declaringClass, String name, String descriptor,
                                           Class<?> returnType, Class<?>[] parameterTypes,
                                           Class<?>[] exceptionTypes) {
        return new MethodMetadata(Objects.requireNonNull(declaringClass, "declaringClass"),
                Objects.requireNonNull(name, "name"),
                Objects.requireNonNull(descriptor, "descriptor"),
                Objects.requireNonNull(returnType, "returnType"),
                parameterTypes == null ? new Class<?>[0] : parameterTypes.clone(),
                exceptionTypes == null ? new Class<?>[0] : exceptionTypes.clone(),
                null,
                null);
    }

    private MethodMetadata(Class<?> declaringClass, String name, String descriptor,
                           Class<?> returnType, Class<?>[] parameterTypes, Class<?>[] exceptionTypes,
                           Method reflectMethod, Constructor<?> reflectConstructor) {
        this.declaringClass = declaringClass;
        this.name = name;
        this.descriptor = descriptor;
        this.returnType = returnType;
        this.parameterTypes = parameterTypes;
        this.exceptionTypes = exceptionTypes;
        this.reflectMethod = reflectMethod;
        this.reflectConstructor = reflectConstructor;
    }

    public Class<?> declaringClass() {
        return declaringClass;
    }

    public String name() {
        return name;
    }

    public String descriptor() {
        return descriptor;
    }

    public Class<?> returnType() {
        return returnType;
    }

    public Class<?>[] parameterTypes() {
        return parameterTypes.clone();
    }

    public Class<?>[] exceptionTypes() {
        return exceptionTypes.clone();
    }

    public Method reflectMethod() {
        return reflectMethod;
    }

    public Constructor<?> reflectConstructor() {
        return reflectConstructor;
    }

    public boolean isConstructor() {
        return reflectConstructor != null;
    }

    public ClassLoader targetClassLoader() {
        return declaringClass.getClassLoader();
    }

    @Override
    public String toString() {
        return declaringClass.getName() + "#" + name + descriptor
                + " throws " + Arrays.toString(exceptionTypes);
    }
}
