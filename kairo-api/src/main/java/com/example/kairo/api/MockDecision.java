package com.example.kairo.api;

import java.util.Objects;

/**
 * A rule's decision about how the current invocation should proceed.
 *
 * <p>V1.2 exposed three legacy factories &mdash; {@link #proceed()},
 * {@link #returnValue(Object)} and {@link #throwException(Throwable)} &mdash;
 * which the V1.4 dispatcher maps onto {@link RuleChainDecision} via
 * {@link RuleChainDecision#from(MockDecision)}:
 * {@code proceed} becomes {@link PropagationMode#CONTINUE}, {@code returnValue}
 * and {@code throwException} become {@link PropagationMode#TERMINATE}.
 *
 * <p>V1.4 adds explicit propagation through {@link #propagationMode()}: a script
 * can {@link #replaceReturnValue(Object) replace the return value and continue},
 * {@link #proceedOriginal() stop the enter-side chain and run the real body}, or
 * {@link #failOpen()} / {@link #failClosed(Throwable)} the chain. The legacy
 * factories leave {@code propagationMode} unset so the dispatcher derives it
 * from {@link #type()}, preserving the exact V1.2 behaviour for existing
 * scripts.
 */
public final class MockDecision {

    public enum Type {
        PROCEED,
        RETURN,
        THROW
    }

    private static final MockDecision PROCEED = new MockDecision(Type.PROCEED, null, null, null, null);

    private final Type type;
    private final PropagationMode propagationMode;
    private final Object[] arguments;
    private final Object returnValue;
    private final Throwable throwable;

    private MockDecision(Type type, PropagationMode propagationMode, Object[] arguments,
                         Object returnValue, Throwable throwable) {
        this.type = Objects.requireNonNull(type, "type");
        this.propagationMode = propagationMode;
        this.arguments = arguments;
        this.returnValue = returnValue;
        this.throwable = throwable;
    }

    public static MockDecision proceed() {
        return PROCEED;
    }

    public static MockDecision proceed(Object[] arguments) {
        return new MockDecision(Type.PROCEED, null, Objects.requireNonNull(arguments, "arguments"), null, null);
    }

    public static MockDecision returnValue(Object value) {
        return new MockDecision(Type.RETURN, null, null, value, null);
    }

    public static MockDecision throwException(Throwable throwable) {
        return new MockDecision(Type.THROW, null, null, null, Objects.requireNonNull(throwable, "throwable"));
    }

    // -------------------------------------------------------- V1.4 explicit propagation

    /**
     * Continue the chain adopting the supplied arguments on the enter / call-before
     * side. Equivalent to {@link #proceed(Object[])} but with an explicit
     * {@link PropagationMode#CONTINUE}.
     */
    public static MockDecision continueWithArguments(Object[] arguments) {
        return new MockDecision(Type.PROCEED, PropagationMode.CONTINUE,
                Objects.requireNonNull(arguments, "arguments"), null, null);
    }

    /**
     * Replace the current outcome with a return value and continue the chain so
     * later rules observe the replaced value. Maps to
     * {@link RuleChainDecision#continueReturning(Object)}.
     */
    public static MockDecision replaceReturnValue(Object value) {
        return new MockDecision(Type.RETURN, PropagationMode.CONTINUE, null, value, null);
    }

    /**
     * Replace the current outcome with a throwable and continue the chain, or
     * recover a throwing outcome back to a return value when paired with a
     * {@link #replaceReturnValue(Object)} from a later rule. Maps to
     * {@link RuleChainDecision#continueThrowing(Throwable)}.
     */
    public static MockDecision replaceThrowable(Throwable throwable) {
        return new MockDecision(Type.THROW, PropagationMode.CONTINUE, null, null,
                Objects.requireNonNull(throwable, "throwable"));
    }

    /**
     * Valid only at enter / call-before locations: stop the chain and proceed to
     * real execution with the current arguments.
     */
    public static MockDecision proceedOriginal() {
        return new MockDecision(Type.PROCEED, PropagationMode.PROCEED_ORIGINAL, null, null, null);
    }

    /**
     * Stop the enter-side chain and proceed to real execution with the supplied
     * arguments.
     */
    public static MockDecision proceedOriginal(Object[] arguments) {
        return new MockDecision(Type.PROCEED, PropagationMode.PROCEED_ORIGINAL,
                Objects.requireNonNull(arguments, "arguments"), null, null);
    }

    /** Discard this rule's modifications and continue the chain with the prior outcome. */
    public static MockDecision failOpen() {
        return new MockDecision(Type.PROCEED, PropagationMode.FAIL_OPEN, null, null, null);
    }

    /**
     * Stop the chain with a failure throwable. Requires explicit application
     * policy allowance; the dispatcher downgrades to {@link #failOpen()} when
     * not allowed.
     */
    public static MockDecision failClosed(Throwable throwable) {
        return new MockDecision(Type.THROW, PropagationMode.FAIL_CLOSED, null, null,
                Objects.requireNonNull(throwable, "throwable"));
    }

    public Type type() {
        return type;
    }

    /**
     * The explicit V1.4 propagation mode, or {@code null} when the decision was
     * built with a legacy factory and the dispatcher should derive the mode from
     * {@link #type()}.
     */
    public PropagationMode propagationMode() {
        return propagationMode;
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
