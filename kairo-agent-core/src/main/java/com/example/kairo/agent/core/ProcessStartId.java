package com.example.kairo.agent.core;

/**
 * V1.7 M1-C &sect;8.3: the single, centralized derivation of an Agent's
 * {@code processStartId}. Both the Agent registration path (which first sends the id to the
 * Platform) and the runtime-state snapshot path (which echoes it back inside the
 * {@code REFRESH_RUNTIME_STATE} ack) resolve the id through this one method, so the two can never
 * drift apart and a late snapshot from an old process can be reliably detected on the Platform.
 *
 * <p>The formula mirrors the original registration formula exactly: an explicit override is
 * returned verbatim when one is configured (including a blank one), otherwise the id is derived as
 * {@code host:pid:jvmStartTimeMillis} from {@link JvmInfo}. A blank override is <em>not</em>
 * silently replaced with a derived id here; normal configuration/schema validation rejects an
 * invalid blank override rather than changing the agent's identity. The Platform compares the
 * snapshot's resolved id against the {@code instance.process_start_id} written during registration;
 * a mismatch means the snapshot is from a stale process and must be rejected.
 */
public final class ProcessStartId {

    private ProcessStartId() {
    }

    /**
     * Resolve the process-start id for the supplied override and JVM info.
     *
     * @param override the configured {@code platformProcessStartId} override, or {@code null} to
     *                 derive the id from the JVM info
     * @param jvmInfo  the Agent's JVM info (host, pid, start time)
     * @return the resolved process-start id, never {@code null}
     */
    public static String resolve(String override, JvmInfo jvmInfo) {
        if (override != null) {
            return override;
        }
        return jvmInfo.host() + ":" + jvmInfo.pid() + ":" + jvmInfo.startTimeMillis();
    }
}
