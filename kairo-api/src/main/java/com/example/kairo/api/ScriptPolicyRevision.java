package com.example.kairo.api;

/** Immutable identity of the capability policy used to compile a script. */
public record ScriptPolicyRevision(long revision, String hash) {
    public ScriptPolicyRevision {
        if (revision < 0) {
            throw new IllegalArgumentException("revision must be >= 0");
        }
        if (hash == null || hash.isBlank()) {
            throw new IllegalArgumentException("hash must not be blank");
        }
    }
}
