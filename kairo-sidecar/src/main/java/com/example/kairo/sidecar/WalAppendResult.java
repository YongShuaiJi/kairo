package com.example.kairo.sidecar;

import java.time.Instant;

public record WalAppendResult(
        long sequence,
        Instant timestamp,
        String payloadHash,
        long plaintextBytes,
        long ciphertextBytes,
        boolean duplicate
) {
}
