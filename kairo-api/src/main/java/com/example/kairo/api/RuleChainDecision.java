package com.example.kairo.api;

import java.util.Objects;

/**
 * The V1.4 rule-chain decision the dispatcher reasons about.
 *
 * <p>Replaces the V1.2 "first non-PROCEED decision wins" short-circuit with an
 * explicit, auditable chain state machine. A decision combines a
 * {@link PropagationMode} with an optional outcome replacement (arguments on the
 * enter side, a return value or throwable on the exit side). The outcome fields
 * are independent of the mode so a rule can <em>replace</em> the current return
 * value and still {@link PropagationMode#CONTINUE} &mdash; letting later rules
 * observe the replaced value &mdash; or replace and
 * {@link PropagationMode#TERMINATE}.
 *
 * <p>Scripts still return {@link MockDecision}; {@link #from(MockDecision)} maps
 * the legacy {@code proceed / returnValue / throwException} factories onto
 * {@link PropagationMode#CONTINUE} / {@link PropagationMode#TERMINATE} so
 * existing scripts keep their exact V1.2 behaviour. The mapping is frozen here,
 * not guessed at call sites.
 */
public final class RuleChainDecision {

    private final PropagationMode propagationMode;
    private final OutcomeState outcomeState;
    private final Object[] arguments;
    private final Object returnValue;
    private final Throwable throwable;

    private RuleChainDecision(PropagationMode propagationMode, OutcomeState outcomeState,
                              Object[] arguments, Object returnValue, Throwable throwable) {
        this.propagationMode = Objects.requireNonNull(propagationMode, "propagationMode");
        this.outcomeState = Objects.requireNonNull(outcomeState, "outcomeState");
        this.arguments = arguments;
        this.returnValue = returnValue;
        this.throwable = throwable;
    }

    /**
     * Continue the chain without changing the current outcome or arguments.
     */
    public static RuleChainDecision continueChain() {
        return new RuleChainDecision(PropagationMode.CONTINUE, OutcomeState.PROCEEDING, null, null, null);
    }

    /**
     * Continue the chain adopting the supplied arguments (enter / call-before
     * side). The arguments are validated by the dispatcher before adoption.
     */
    public static RuleChainDecision continueWithArguments(Object[] arguments) {
        return new RuleChainDecision(PropagationMode.CONTINUE, OutcomeState.PROCEEDING, arguments, null, null);
    }

    /**
     * Continue the chain, replacing the current outcome with a return value.
     * Later rules observe the replaced value.
     */
    public static RuleChainDecision continueReturning(Object returnValue) {
        return new RuleChainDecision(PropagationMode.CONTINUE, OutcomeState.RETURNING, null, returnValue, null);
    }

    /**
     * Continue the chain, replacing the current outcome with a throwable, or
     * recovering a throwable back to a return value when the prior outcome was
     * throwing.
     */
    public static RuleChainDecision continueThrowing(Throwable throwable) {
        return new RuleChainDecision(PropagationMode.CONTINUE, OutcomeState.THROWING, null, null, throwable);
    }

    /**
     * Stop the chain and proceed to real execution with the current arguments.
     * Only meaningful at enter / call-before locations; the dispatcher ignores
     * it elsewhere and treats it as {@link #continueChain()}.
     */
    public static RuleChainDecision proceedOriginal() {
        return new RuleChainDecision(PropagationMode.PROCEED_ORIGINAL, OutcomeState.PROCEEDING, null, null, null);
    }

    /**
     * Stop the chain and proceed to real execution with the supplied arguments.
     */
    public static RuleChainDecision proceedOriginal(Object[] arguments) {
        return new RuleChainDecision(PropagationMode.PROCEED_ORIGINAL, OutcomeState.PROCEEDING, arguments, null, null);
    }

    /** Stop the chain, adopting a return value as the outcome. */
    public static RuleChainDecision terminateReturning(Object returnValue) {
        return new RuleChainDecision(PropagationMode.TERMINATE, OutcomeState.RETURNING, null, returnValue, null);
    }

    /** Stop the chain, adopting a throwable as the outcome. */
    public static RuleChainDecision terminateThrowing(Throwable throwable) {
        return new RuleChainDecision(PropagationMode.TERMINATE, OutcomeState.THROWING, null, null, throwable);
    }

    /** Stop the chain with the current outcome unchanged. */
    public static RuleChainDecision terminate() {
        return new RuleChainDecision(PropagationMode.TERMINATE, OutcomeState.PROCEEDING, null, null, null);
    }

    /** Discard this rule's modifications and continue with the prior outcome. */
    public static RuleChainDecision failOpen() {
        return new RuleChainDecision(PropagationMode.FAIL_OPEN, OutcomeState.PROCEEDING, null, null, null);
    }

    /**
     * Stop the chain with a failure throwable. Requires explicit application
     * policy allowance; the dispatcher downgrades to {@link #failOpen()} when
     * not allowed.
     */
    public static RuleChainDecision failClosed(Throwable throwable) {
        return new RuleChainDecision(PropagationMode.FAIL_CLOSED, OutcomeState.THROWING, null, null, throwable);
    }

    /**
     * Map a legacy {@link MockDecision} onto the chain model.
     *
     * <p>{@code proceed} (with or without arguments) maps to
     * {@link PropagationMode#CONTINUE}; {@code returnValue} and
     * {@code throwException} map to {@link PropagationMode#TERMINATE}. When the
     * supplied decision already carries an explicit V1.4 propagation mode
     * (set by the new {@code MockDecision} factories), that mode wins and only
     * the outcome fields are read from the legacy decision.
     */
    public static RuleChainDecision from(MockDecision decision) {
        if (decision == null) {
            return continueChain();
        }
        PropagationMode mode = decision.propagationMode();
        if (mode != null) {
            OutcomeState state = outcomeOf(decision, mode);
            return new RuleChainDecision(mode, state, decision.arguments(),
                    decision.returnValue(), decision.throwable());
        }
        return switch (decision.type()) {
            case PROCEED -> decision.hasArguments()
                    ? continueWithArguments(decision.arguments())
                    : continueChain();
            case RETURN -> terminateReturning(decision.returnValue());
            case THROW -> terminateThrowing(decision.throwable());
        };
    }

    private static OutcomeState outcomeOf(MockDecision decision, PropagationMode mode) {
        if (decision.returnValue() != null) {
            return OutcomeState.RETURNING;
        }
        if (decision.throwable() != null) {
            return OutcomeState.THROWING;
        }
        return mode == PropagationMode.FAIL_CLOSED ? OutcomeState.THROWING : OutcomeState.PROCEEDING;
    }

    public PropagationMode propagationMode() {
        return propagationMode;
    }

    public OutcomeState outcomeState() {
        return outcomeState;
    }

    /** Arguments the rule wants adopted on the enter / call-before side; {@code null} when unchanged. */
    public Object[] arguments() {
        return arguments;
    }

    public boolean hasArguments() {
        return arguments != null;
    }

    public Object returnValue() {
        return returnValue;
    }

    public Throwable throwable() {
        return throwable;
    }

    /**
     * Whether this decision carries an outcome replacement (a return value or
     * throwable) that the dispatcher should adopt into the current outcome,
     * regardless of whether it continues or terminates.
     */
    public boolean replacesOutcome() {
        return returnValue != null || throwable != null;
    }

    @Override
    public String toString() {
        return propagationMode + "/" + outcomeState
                + (returnValue != null ? " return=" + returnValue : "")
                + (throwable != null ? " throw=" + throwable : "")
                + (arguments != null ? " args" : "");
    }
}
