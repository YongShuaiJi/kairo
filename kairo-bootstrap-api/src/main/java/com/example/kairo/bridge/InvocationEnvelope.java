package com.example.kairo.bridge;

/**
 * Lightweight, dependency-free envelope carrying one V1.3 enhancement event
 * into the bridge. The V1 {@code enter(Class, Method, Object, Object[])}
 * signature cannot represent constructors (no {@code java.lang.reflect.Method})
 * or call sites (an invoke instruction inside a method), so the bridge gains a
 * versioned {@link KairoBridge#enterV2(InvocationEnvelope)} entry point that
 * takes this envelope. Method-location advice keeps using the lean V1 entry
 * point; constructor and call-site advice use V2.
 *
 * <p>The envelope intentionally carries only JDK types. Rich V1.3 types
 * ({@code EnhancementLocation}, {@code InvokeOpcode}, ...) live in
 * {@code kairo-api}, which {@code kairo-bootstrap-api} does not depend on, so
 * the location and opcode are passed as their stable name / int and reified by
 * the agent-side dispatcher.
 */
public final class InvocationEnvelope {

    private final String location;
    private final Class<?> declaringClass;
    private final String memberName;
    private final String descriptor;
    private final boolean constructor;
    private final Object target;
    private final Object[] arguments;
    private final String callOwner;
    private final String callName;
    private final String callDescriptor;
    private final int callOpcode;
    private final int callOccurrenceIndex;
    private final Object[] callArguments;

    private InvocationEnvelope(Builder builder) {
        this.location = requireText(builder.location, "location");
        this.declaringClass = requireNonNull(builder.declaringClass, "declaringClass");
        this.memberName = requireText(builder.memberName, "memberName");
        this.descriptor = requireText(builder.descriptor, "descriptor");
        this.constructor = builder.constructor;
        this.target = builder.target;
        this.arguments = builder.arguments;
        this.callOwner = builder.callOwner;
        this.callName = builder.callName;
        this.callDescriptor = builder.callDescriptor;
        this.callOpcode = builder.callOpcode;
        this.callOccurrenceIndex = builder.callOccurrenceIndex;
        this.callArguments = builder.callArguments;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.length() == 0) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static <T> T requireNonNull(T value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        return value;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getLocation() {
        return location;
    }

    public Class<?> getDeclaringClass() {
        return declaringClass;
    }

    public String getMemberName() {
        return memberName;
    }

    public String getDescriptor() {
        return descriptor;
    }

    public boolean isConstructor() {
        return constructor;
    }

    public Object getTarget() {
        return target;
    }

    public Object[] getArguments() {
        return arguments;
    }

    public String getCallOwner() {
        return callOwner;
    }

    public String getCallName() {
        return callName;
    }

    public String getCallDescriptor() {
        return callDescriptor;
    }

    public int getCallOpcode() {
        return callOpcode;
    }

    public int getCallOccurrenceIndex() {
        return callOccurrenceIndex;
    }

    public Object[] getCallArguments() {
        return callArguments;
    }

    public boolean isCallSite() {
        return callName != null;
    }

    public static final class Builder {
        private String location;
        private Class<?> declaringClass;
        private String memberName;
        private String descriptor;
        private boolean constructor;
        private Object target;
        private Object[] arguments;
        private String callOwner;
        private String callName;
        private String callDescriptor;
        private int callOpcode;
        private int callOccurrenceIndex;
        private Object[] callArguments;

        private Builder() {
        }

        public Builder location(String location) {
            this.location = location;
            return this;
        }

        public Builder declaringClass(Class<?> declaringClass) {
            this.declaringClass = declaringClass;
            return this;
        }

        public Builder memberName(String memberName) {
            this.memberName = memberName;
            return this;
        }

        public Builder descriptor(String descriptor) {
            this.descriptor = descriptor;
            return this;
        }

        public Builder constructor(boolean constructor) {
            this.constructor = constructor;
            return this;
        }

        public Builder target(Object target) {
            this.target = target;
            return this;
        }

        public Builder arguments(Object[] arguments) {
            this.arguments = arguments;
            return this;
        }

        public Builder callOwner(String callOwner) {
            this.callOwner = callOwner;
            return this;
        }

        public Builder callName(String callName) {
            this.callName = callName;
            return this;
        }

        public Builder callDescriptor(String callDescriptor) {
            this.callDescriptor = callDescriptor;
            return this;
        }

        public Builder callOpcode(int callOpcode) {
            this.callOpcode = callOpcode;
            return this;
        }

        public Builder callOccurrenceIndex(int callOccurrenceIndex) {
            this.callOccurrenceIndex = callOccurrenceIndex;
            return this;
        }

        public Builder callArguments(Object[] callArguments) {
            this.callArguments = callArguments;
            return this;
        }

        public InvocationEnvelope build() {
            return new InvocationEnvelope(this);
        }
    }
}
