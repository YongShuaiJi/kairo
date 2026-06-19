package com.example.runtimemock.storage;

import java.util.Map;
import java.util.Objects;

public record PutObjectRequest(
        String objectKey,
        byte[] content,
        String contentType,
        String contentHash,
        Map<String, String> metadata
) {
    public PutObjectRequest {
        Objects.requireNonNull(objectKey, "objectKey");
        Objects.requireNonNull(content, "content");
        contentType = contentType == null || contentType.isBlank()
                ? "application/octet-stream"
                : contentType;
        contentHash = contentHash == null ? "" : contentHash;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        content = content.clone();
    }

    @Override
    public byte[] content() {
        return content.clone();
    }
}
