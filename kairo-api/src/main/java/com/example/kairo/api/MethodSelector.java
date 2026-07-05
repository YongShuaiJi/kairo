package com.example.kairo.api;

import java.util.Objects;

public final class MethodSelector {

    private final String className;
    private final String classLoaderId;
    private final String methodName;
    private final String methodDescriptor;

    public MethodSelector(String className, String classLoaderId, String methodName, String methodDescriptor) {
        this.className = requireText(className, "className");
        this.classLoaderId = classLoaderId;
        this.methodName = requireText(methodName, "methodName");
        this.methodDescriptor = requireText(methodDescriptor, "methodDescriptor");
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    public static Builder builder() {
        return new Builder();
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
        if (!(o instanceof MethodSelector that)) {
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

    public static final class Builder {
        private String className;
        private String classLoaderId;
        private String methodName;
        private String methodDescriptor;

        private Builder() {
        }

        public Builder className(String className) {
            this.className = className;
            return this;
        }

        public Builder classLoaderId(String classLoaderId) {
            this.classLoaderId = classLoaderId;
            return this;
        }

        public Builder methodName(String methodName) {
            this.methodName = methodName;
            return this;
        }

        public Builder methodDescriptor(String methodDescriptor) {
            this.methodDescriptor = methodDescriptor;
            return this;
        }

        public MethodSelector build() {
            return new MethodSelector(className, classLoaderId, methodName, methodDescriptor);
        }
    }
}
