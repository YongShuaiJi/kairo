package com.example.kairo.agent.core;

import java.util.Objects;

public final class MethodSignature {

    private final String className;
    private final String classLoaderId;
    private final String methodName;
    private final String methodDescriptor;

    public MethodSignature(String className, String classLoaderId, String methodName, String methodDescriptor) {
        this.className = Objects.requireNonNull(className, "className");
        this.classLoaderId = classLoaderId;
        this.methodName = Objects.requireNonNull(methodName, "methodName");
        this.methodDescriptor = Objects.requireNonNull(methodDescriptor, "methodDescriptor");
    }

    public String className() {
        return className;
    }

    public String classLoaderId() {
        return classLoaderId;
    }

    public String methodName() {
        return methodName;
    }

    public String methodDescriptor() {
        return methodDescriptor;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof MethodSignature that)) {
            return false;
        }
        return className.equals(that.className)
                && Objects.equals(classLoaderId, that.classLoaderId)
                && methodName.equals(that.methodName)
                && methodDescriptor.equals(that.methodDescriptor);
    }

    @Override
    public int hashCode() {
        return Objects.hash(className, classLoaderId, methodName, methodDescriptor);
    }
}
