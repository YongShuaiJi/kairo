package com.example.kairo.platform.command;

import java.util.Set;

/**
 * V1.7 &sect;4.2 fixed Agent command classification. Every V1 command is either
 * <strong>DURABLE</strong> (safely recoverable and retryable across a Platform restart
 * against the same persistent database) or <strong>TRANSIENT</strong> (must enter a
 * machine-readable failure state when its in-memory waiter or context is lost on restart,
 * never silently replayed). The split is a fixed design decision, not an implementation
 * choice: reclassifying a command is a breaking change.
 *
 * <p>The classification is the single source of truth consulted by the M1-B startup
 * recovery ({@link CommandStartupRecoveryService}). It deliberately lives in the
 * platform command package (within the M1-B allowed scope) rather than in
 * {@code kairo-api}; the Agent does not act on the classification in M1-B, so sharing it
 * across the wire is not required.
 *
 * <p>The two sets together partition {@code KairoCommandCapabilities.V1}: every frozen V1
 * command is exactly one of DURABLE or TRANSIENT. This invariant is guarded by
 * {@code AgentCommandClassificationTest}.
 */
public final class AgentCommandClassification {

    /**
     * &sect;4.2 DURABLE: payload fully persisted; execution relies on idempotency key,
     * dispatch epoch and (where applicable) expected/desired revision+hash. A restart
     * keeps a PENDING DURABLE command claimable and an expired DISPATCHED DURABLE command
     * redispatchable under M1-A.
     */
    public static final Set<String> DURABLE = Set.of(
            "APPLY_RULE", "APPLY_CHAIN", "DISABLE_ALL", "ENABLE_ALL",
            "RESET_CLASS", "RESET_ALL", "STOP_AGENT", "REFRESH_RUNTIME_STATE"
    );

    /**
     * &sect;4.2 TRANSIENT explicit set. The {@code BYTECODE_*} and {@code SCRIPT_*}
     * families are matched by prefix below. These commands may depend on in-memory script
     * source, class bytes, a request waiter or an instantaneous JVM observation; after a
     * Platform restart they fail with {@link #TRANSIENT_CONTEXT_LOST_CODE} rather than
     * replay, so transient sensitive material is never persisted to recover them.
     */
    private static final Set<String> TRANSIENT_EXPLICIT = Set.of(
            "START_RECORDING", "STOP_RECORDING",
            "DISCOVER_TARGETS", "LIST_LOADERS", "LIST_CALL_SITES", "RESOLVE_TARGET"
    );

    /** The command-type prefixes whose every member is TRANSIENT (&sect;4.2). */
    private static final Set<String> TRANSIENT_PREFIXES = Set.of("BYTECODE_", "SCRIPT_");

    /**
     * The fixed machine-readable failure code stored in {@code agent_command.error_message}
     * when an orphan TRANSIENT command is failed on Platform restart (&sect;8.2#5). Stored in
     * the existing {@code error_message} text column - no new migration, mirroring M1-A's
     * {@code AGENT_COMMAND_MAX_ATTEMPTS_EXHAUSTED} storage convention. Not registered in
     * {@code KairoErrorCatalog} (it is a persisted command-failure code, not an HTTP
     * {@code ApiError.code}).
     */
    public static final String TRANSIENT_CONTEXT_LOST_CODE = "TRANSIENT_COMMAND_CONTEXT_LOST";

    private AgentCommandClassification() {
    }

    /** Whether the command is DURABLE (recoverable/retryable across restart). */
    public static boolean isDurable(String commandType) {
        return commandType != null && DURABLE.contains(commandType);
    }

    /**
     * Whether the command is TRANSIENT (must fail on restart when its context is lost). A
     * DURABLE command is never TRANSIENT; a {@code null}/blank type is neither.
     */
    public static boolean isTransient(String commandType) {
        if (commandType == null || commandType.isBlank()) {
            return false;
        }
        if (DURABLE.contains(commandType)) {
            return false;
        }
        if (TRANSIENT_EXPLICIT.contains(commandType)) {
            return true;
        }
        for (String prefix : TRANSIENT_PREFIXES) {
            if (commandType.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
