package com.example.kairo.api;

import java.util.Objects;

public final class MockDecision {

    public enum Type {
        PROCEED,
        RETURN,
        THROW
    }

    private static final MockDecision PROCEED = new MockDecision(Type.PROCEED, null, null, null);

    private final Type type;
    private final Object[] arguments;
    private final Object returnValue;
    private final Throwable throwable;

    private MockDecision(Type type, Object[] arguments, Object returnValue, Throwable throwable) {
        this.type = Objects.requireNonNull(type, "type");
        this.arguments = arguments;
        this.returnValue = returnValue;
        this.throwable = throwable;
    }

    public static MockDecision proceed() {
        return PROCEED;
    }

    public static MockDecision proceed(Object[] arguments) {
        return new MockDecision(Type.PROCEED, Objects.requireNonNull(arguments, "arguments"), null, null);
    }

    public static MockDecision returnValue(Object value) {
        return new MockDecision(Type.RETURN, null, value, null);
    }

    public static MockDecision throwException(Throwable throwable) {
        return new MockDecision(Type.THROW, null, null, Objects.requireNonNull(throwable, "throwable"));
    }

    public Type type() {
        return type;
    }

    public Object[] arguments() {
        return arguments;
    }

    public Object returnValue() {
        return returnValue;
    }

    public Throwable throwable() {
        return throwable;
    }

    public boolean hasArguments() {
        return arguments != null;
    }
}
