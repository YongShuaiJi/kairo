package com.example.kairo.api;

/**
 * Authoritative model of <em>where</em> an enhancement is applied inside a class.
 *
 * <p>V1.3 unifies the V1.2 {@link InvokePhase} (BEFORE / RETURN / THROWS) with new
 * constructor and call-site locations under a single enum. The three legacy phases
 * are preserved as a compatibility projection: every location maps to a legacy
 * {@link InvokePhase} so existing rules, JSON and dispatch paths keep working
 * unchanged, and rules authored without an explicit location derive one from
 * their legacy phase.
 *
 * <p>Locations are grouped by the construct they attach to:
 * <ul>
 *   <li><b>Method</b> &mdash; {@link #METHOD_ENTER}, {@link #METHOD_RETURN},
 *       {@link #METHOD_THROW}, {@link #METHOD_FINALLY};</li>
 *   <li><b>Constructor</b> &mdash; {@link #CONSTRUCTOR_AFTER_SUPER},
 *       {@link #CONSTRUCTOR_RETURN}, {@link #CONSTRUCTOR_THROW};</li>
 *   <li><b>Call site</b> &mdash; {@link #CALL_BEFORE}, {@link #CALL_RETURN},
 *       {@link #CALL_THROW}.</li>
 * </ul>
 *
 * <p>The {@code *FINALLY} location is the only one that observes both exit paths
 * (normal return and throwable); by V1.3 contract it is observe-and-record only.
 */
public enum EnhancementLocation {

    METHOD_ENTER,
    METHOD_RETURN,
    METHOD_THROW,
    METHOD_FINALLY,
    CONSTRUCTOR_AFTER_SUPER,
    CONSTRUCTOR_RETURN,
    CONSTRUCTOR_THROW,
    CALL_BEFORE,
    CALL_RETURN,
    CALL_THROW;

    /**
     * Project a legacy {@link InvokePhase} onto the authoritative location model.
     * BEFORE maps to the method enter location, RETURN to the method return
     * location and THROWS to the method throw location. Legacy rules therefore
     * land on the exact same execution points they occupied in V1.2.
     */
    public static EnhancementLocation fromPhase(InvokePhase phase) {
        if (phase == null) {
            throw new IllegalArgumentException("phase must not be null");
        }
        return switch (phase) {
            case BEFORE -> METHOD_ENTER;
            case RETURN -> METHOD_RETURN;
            case THROWS -> METHOD_THROW;
        };
    }

    /**
     * The legacy {@link InvokePhase} this location projects to, for backward
     * compatibility with V1.2 serialization, dispatch bucketing and rule JSON.
     * Enter-side locations project to BEFORE, return-side (including FINALLY) to
     * RETURN, and throw-side to THROWS.
     */
    public InvokePhase toLegacyPhase() {
        return switch (this) {
            case METHOD_ENTER, CONSTRUCTOR_AFTER_SUPER, CALL_BEFORE -> InvokePhase.BEFORE;
            case METHOD_RETURN, METHOD_FINALLY, CONSTRUCTOR_RETURN, CALL_RETURN -> InvokePhase.RETURN;
            case METHOD_THROW, CONSTRUCTOR_THROW, CALL_THROW -> InvokePhase.THROWS;
        };
    }

    public boolean isMethodLocation() {
        return this == METHOD_ENTER || this == METHOD_RETURN || this == METHOD_THROW || this == METHOD_FINALLY;
    }

    public boolean isConstructorLocation() {
        return this == CONSTRUCTOR_AFTER_SUPER || this == CONSTRUCTOR_RETURN || this == CONSTRUCTOR_THROW;
    }

    public boolean isCallSiteLocation() {
        return this == CALL_BEFORE || this == CALL_RETURN || this == CALL_THROW;
    }

    /** Locations that fire on the enter side of their construct (before the body / call). */
    public boolean isEnterLocation() {
        return this == METHOD_ENTER || this == CONSTRUCTOR_AFTER_SUPER || this == CALL_BEFORE;
    }

    /** Locations that fire only on the normal-return exit path. */
    public boolean isReturnLocation() {
        return this == METHOD_RETURN || this == CONSTRUCTOR_RETURN || this == CALL_RETURN;
    }

    /** Locations that fire only on the throwable exit path. */
    public boolean isThrowLocation() {
        return this == METHOD_THROW || this == CONSTRUCTOR_THROW || this == CALL_THROW;
    }

    /** FINALLY observes both exit paths and, by V1.3 contract, may not mutate the outcome. */
    public boolean isFinallyLocation() {
        return this == METHOD_FINALLY;
    }

    /**
     * Whether a script attached at this location is permitted to mutate the
     * observed outcome. Enter locations may short-circuit (return/throw ahead of
     * the body); return and throw locations may replace the result or throwable.
     * FINALLY is observe-only &mdash; V1.4 will revisit mutation priorities.
     */
    public boolean mayMutateOutcome() {
        return !isFinallyLocation();
    }
}
