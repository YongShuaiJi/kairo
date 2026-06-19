package com.example.runtimemock.storage;

import java.util.Map;

public record StoredObject(
        String provider,
        String bucket,
        String objectKey,
        String objectUri,
        String versionId,
        String eTag,
        String contentHash,
        long bytesCount,
        Map<String, String> metadata
) {
    public StoredObject {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
