package com.example.kairo.api;

public interface InvocationContext {

    InvokePhase phase();

    /**
     * Authoritative V1.3 enhancement location. Defaults to the location projected
     * from {@link #phase()} so V1.2 contexts &mdash; which only carry a phase &mdash;
     * still resolve to a concrete location.
     */
    default EnhancementLocation location() {
        return EnhancementLocation.fromPhase(phase());
    }

    Object[] arguments();

    Object target();

    Object result();

    Throwable throwable();

    MethodMetadata method();

    MockApi mockApi();

    ScriptLog log();

    // -------------------------------------------------------- V1.4 original / current

    /**
     * The arguments as they entered the enhanced construct, before any BEFORE
     * rule mutated them. V1.4 requires scripts to explicitly read
     * {@link #arguments()} (current, possibly mutated by earlier rules) or
     * {@code originalArguments()} rather than guessing which they hold.
     * Defaults to {@link #arguments()} for contexts that predate V1.4.
     */
    default Object[] originalArguments() {
        return arguments();
    }

    /**
     * The result produced by the original body before return-side rules ran.
     * Defaults to {@link #result()} for contexts that predate V1.4.
     */
    default Object originalResult() {
        return result();
    }

    /**
     * The throwable produced by the original body before return-side rules ran.
     * Defaults to {@link #throwable()} for contexts that predate V1.4.
     */
    default Throwable originalThrowable() {
        return throwable();
    }

    /**
     * The current outcome flavour flowing through the chain at this rule's
     * execution: {@link OutcomeState#PROCEEDING}, {@link OutcomeState#RETURNING}
     * or {@link OutcomeState#THROWING}. Defaults to PROCEEDING.
     */
    default OutcomeState outcomeState() {
        return OutcomeState.PROCEEDING;
    }

    // -------------------------------------------------------- V1.3 call-site context

    /**
     * The caller method metadata, available only inside call-site locations.
     * Returns {@code null} for method and constructor locations.
     */
    default MethodMetadata caller() {
        return null;
    }

    /**
     * The resolved call-site selector, available only inside call-site locations.
     * Returns {@code null} for method and constructor locations.
     */
    default CallSiteSelector callSite() {
        return null;
    }

    /**
     * Arguments to the callee invocation, available only inside call-site
     * locations. Returns {@code null} elsewhere.
     */
    default Object[] callArguments() {
        return null;
    }

    /**
     * Result of the callee invocation, available on call-site return/finally
     * locations. Returns {@code null} on enter-side and throw-side locations.
     */
    default Object callResult() {
        return null;
    }

    /**
     * Throwable thrown by the callee invocation, available on call-site
     * throw/finally locations. Returns {@code null} on enter-side and
     * return-side locations.
     */
    default Throwable callThrowable() {
        return null;
    }
}
