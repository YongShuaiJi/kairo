package com.example.kairo.api.bytecode;

/**
 * Lifecycle status of a single transformation attempt for one class, as
 * recorded in the {@code TransformationJournal}. The status flows
 * {@code STARTED -> SUCCEEDED|FAILED}, with {@code VERIFIED},
 * {@code RECOVERED} and {@code SKIPPED} marking verification, rollback and
 * no-op outcomes.
 */
public enum TransformationStatus {
    STARTED,
    SUCCEEDED,
    FAILED,
    VERIFIED,
    RECOVERED,
    SKIPPED
}
