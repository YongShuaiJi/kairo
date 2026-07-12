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
