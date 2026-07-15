package com.example.kairo.api.write;

/**
 * Machine-readable risk level for a write operation (V1.6 &sect;2.3
 * "机器可读风险等级"). Every write response and Operation carries one.
 */
public enum RiskLevel {
    /** Read-only or reversible local operation, no JVM side effects. */
    LOW,
    /** Temporary, auto-reverting JVM change scoped to one instance/session. */
    MEDIUM,
    /** Persistent rule version or rollout affecting one or more instances. */
    HIGH,
    /** Production rollout, mass target, or irreversible destructive operation. */
    CRITICAL
}
