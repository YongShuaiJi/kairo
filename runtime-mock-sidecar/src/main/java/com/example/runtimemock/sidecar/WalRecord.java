package com.example.runtimemock.sidecar;

import java.time.Instant;

public record WalRecord(
        long sequence,
        Instant timestamp,
        String payloadHash,
        Object payload
) {
}
