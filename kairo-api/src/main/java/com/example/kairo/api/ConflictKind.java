package com.example.kairo.api;

/**
 * Kind of static conflict detected for a rule chain. The set is fixed by the
 * V1.4 contract (&sect;2.4); the analyzer maps each detected condition to one
 * of these kinds so callers can render deterministic diagnostics.
 */
public enum ConflictKind {

    /** Two or more rules unconditionally terminate the same phase (e.g. both RETURN). */
    MULTIPLE_UNCONDITIONAL_TERMINATE,

    /** A rule can never run because an earlier unconditional rule always pre-empts it. */
    UNREACHABLE_RULE,

    /** Two or more rules in the same mutex group are simultaneously enabled. */
    MUTEX_GROUP_OVERLAP,

    /** Two or more rules exclusively replace the outcome at the same call site. */
    EXCLUSIVE_CALL_SITE_REPLACEMENT,

    /** A rule's capability tier exceeds the application's configured limit. */
    CAPABILITY_TIER_EXCEEDS_APP_LIMIT,

    /** The target transformation revision no longer matches the loaded class. */
    TARGET_REVISION_DRIFT,

    /** A call-site fingerprint captured at publish time no longer matches. */
    CALL_SITE_FINGERPRINT_DRIFT,

    /** Business-condition overlap between two conditional rules could not be decided statically. */
    POTENTIAL_CONDITION_OVERLAP
}
