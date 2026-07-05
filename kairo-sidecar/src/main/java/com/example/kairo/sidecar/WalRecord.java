package com.example.kairo.sidecar;

import java.time.Instant;

public record WalRecord(
        long sequence,
        Instant timestamp,
        String payloadHash,
        Object payload
) {
}
