package com.example.kairo.api;

import java.util.Objects;

/** Ordered script capability level. */
public enum CapabilityProfile {
    SAFE,
    EXTENDED,
    UNRESTRICTED;

    /** Returns the most restrictive of all three policy decisions. */
    public static CapabilityProfile effective(CapabilityProfile platformMax,
                                              CapabilityProfile applicationMax,
                                              CapabilityProfile requested) {
        Objects.requireNonNull(platformMax, "platformMax");
        Objects.requireNonNull(applicationMax, "applicationMax");
        Objects.requireNonNull(requested, "requested");
        return values()[Math.min(platformMax.ordinal(),
                Math.min(applicationMax.ordinal(), requested.ordinal()))];
    }
}
