package com.example.kairo.api;

/**
 * How a single rule's decision propagates through the V1.4 rule chain.
 *
 * <p>V1.4 replaces the V1.2 "first non-PROCEED decision wins" short-circuit with
 * an explicit chain state machine. Every rule expresses its outcome as a
 * {@link RuleChainDecision} carrying one of these modes. The legacy
 * {@link MockDecision} factories ({@code proceed / returnValue / throwException})
 * map onto {@link #CONTINUE} / {@link #TERMINATE} so existing scripts keep their
 * behaviour; the new modes let a script explicitly continue real execution or
 * fail the chain open/closed.
 *
 * <p>{@code FAIL_CLOSED} is <em>not</em> a default capability: it terminates the
 * chain with a failure outcome and must be explicitly allowed by application
 * policy. {@link #FAIL_OPEN} discards the current rule's modifications and lets
 * the chain continue, preserving the prior outcome.
 */
public enum PropagationMode {

    /** Adopt the rule's (possibly modified) arguments/outcome and continue to the next rule. */
    CONTINUE,

    /** Adopt the current outcome and stop the chain for this phase. */
    TERMINATE,

    /**
     * Valid only at enter / call-before locations: stop the chain and proceed to
     * real execution with the current arguments. Ignored (treated as
     * {@link #CONTINUE}) at return/throw/finally locations.
     */
    PROCEED_ORIGINAL,

    /** Discard this rule's modifications and continue the chain with the prior outcome. */
    FAIL_OPEN,

    /**
     * Discard this rule's modifications and terminate the chain with a failure
     * outcome. Requires explicit application-policy allowance; otherwise the
     * dispatcher downgrades it to {@link #FAIL_OPEN}.
     */
    FAIL_CLOSED
}
