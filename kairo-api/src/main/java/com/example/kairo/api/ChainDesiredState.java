package com.example.kairo.api;

/**
 * Desired state of a rule chain for one enhancement target.
 *
 * <p>{@link #ACTIVE} means the chain carries one or more rules;
 * {@link #EMPTY} means the target should have no rules attached (the chain has
 * been fully unloaded). An EMPTY desired state is distinct from "no record": it
 * is an explicit instruction to remove every rule at the target, which the
 * Agent honours by precise unload (regenerating the remaining Kairo plan) rather
 * than a coarse {@code RESET_ALL}.
 */
public enum ChainDesiredState {
    ACTIVE,
    EMPTY
}
