package com.example.kairo.api;

/**
 * The flavour of the <em>current</em> outcome flowing through a rule chain.
 *
 * <p>As a chain executes, the outcome carried between rules is one of:
 * <ul>
 *   <li>{@link #PROCEEDING} &mdash; no terminal outcome has been established yet
 *       (enter side, or a return/throw chain where no rule has replaced the
 *       outcome);</li>
 *   <li>{@link #RETURNING} &mdash; a return value is the current outcome;</li>
 *   <li>{@link #THROWING} &mdash; a throwable is the current outcome.</li>
 * </ul>
 *
 * <p>The state is distinct from {@link PropagationMode}: a {@link PropagationMode#CONTINUE}
 * rule may observe a {@link #RETURNING} state (a prior rule replaced the return)
 * and choose to replace it again or leave it. Scripts read the current state via
 * the {@link InvocationContext} so they can tell whether they are observing the
 * original execution or a value already mutated by an earlier rule.
 */
public enum OutcomeState {

    PROCEEDING,
    RETURNING,
    THROWING
}
