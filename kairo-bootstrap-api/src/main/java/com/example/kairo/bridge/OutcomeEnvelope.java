package com.example.kairo.bridge;

/**
 * Dependency-free envelope carrying the outcome of an enhanced construct into
 * the V2 bridge exit. The exit location (return vs. throw) is implied by which
 * of {@code returnValue} / {@code throwable} is non-null. See
 * {@link KairoBridge#exitV2(Object, OutcomeEnvelope)}.
 */
public final class OutcomeEnvelope {

    private final Object returnValue;
    private final Throwable throwable;

    private OutcomeEnvelope(Object returnValue, Throwable throwable) {
        this.returnValue = returnValue;
        this.throwable = throwable;
    }

    public static OutcomeEnvelope ofReturn(Object returnValue) {
        return new OutcomeEnvelope(returnValue, null);
    }

    public static OutcomeEnvelope ofThrow(Throwable throwable) {
        if (throwable == null) {
            throw new IllegalArgumentException("throwable must not be null");
        }
        return new OutcomeEnvelope(null, throwable);
    }

    public Object getReturnValue() {
        return returnValue;
    }

    public Throwable getThrowable() {
        return throwable;
    }

    public boolean isThrow() {
        return throwable != null;
    }
}
