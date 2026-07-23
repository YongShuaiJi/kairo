package com.example.kairo.api.snapshot;

/**
 * V1.7 M1-C &sect;8.3: the fixed safety bounds shared by the Agent (which builds a bounded
 * read-only runtime snapshot) and the Platform (which re-validates the same bounds before
 * persisting the snapshot). Both sides reference these constants so the agent-side truncation
 * and the platform-side validation cannot drift apart.
 *
 * <p>The bounds are deliberately small and fixed: the snapshot is a bounded diagnostic carried
 * inside the durable {@code REFRESH_RUNTIME_STATE} ack, never an unbounded dump or a new
 * large-object persistence entry. Every bounded collection is stable-sorted before truncation so
 * a reduced payload is deterministic and reproducible.
 */
public final class SnapshotBounds {

    /** The protocol version a runtime-state snapshot carries and the platform accepts. */
    public static final String PROTOCOL_VERSION = "v1";

    /** At most this many rule entries are included in a snapshot. */
    public static final int MAX_RULES = 5_000;

    /** At most this many chain entries are included in a snapshot. */
    public static final int MAX_CHAINS = 5_000;

    /** At most this many degraded-class entries are included in a snapshot. */
    public static final int MAX_DEGRADED_CLASSES = 1_000;

    /** At most this many serialized UTF-8 JSON bytes for the entire snapshot. */
    public static final int MAX_SERIALIZED_BYTES = 1_048_576;

    /** Truncation reason: a collection was reduced by its fixed entry-count limit. */
    public static final String REASON_ENTRY_COUNT_LIMIT = "ENTRY_COUNT_LIMIT";

    /** Truncation reason: a collection was further reduced so the serialized bytes fit the cap. */
    public static final String REASON_SERIALIZED_BYTE_LIMIT = "SERIALIZED_BYTE_LIMIT";

    private SnapshotBounds() {
    }
}
