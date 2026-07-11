package com.example.kairo.platform.service;

import com.example.kairo.platform.persistence.mapper.BytecodeMetadataMapper;
import org.springframework.stereotype.Service;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;

/** Stores only bytecode transformation metadata; class bytes are deliberately absent. */
@Service
public class BytecodeMetadataService {

    public static final int MAX_DIAGNOSTICS_CHARS = 32_768;
    private final BytecodeMetadataMapper mapper;

    public BytecodeMetadataService(BytecodeMetadataMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional
    public void upsert(BytecodeMetadata metadata) {
        Objects.requireNonNull(metadata, "metadata");
        validate(metadata);
        if (mapper.update(metadata) == 0) {
            try {
                mapper.insert(metadata);
            } catch (DuplicateKeyException concurrentInsert) {
                mapper.update(metadata);
            }
        }
    }

    public List<BytecodeMetadata> findByClassIdentity(String runtimeInstanceId,
                                                       String binaryClassName,
                                                       String classLoaderId) {
        requireText(runtimeInstanceId, "runtimeInstanceId");
        requireText(binaryClassName, "binaryClassName");
        requireText(classLoaderId, "classLoaderId");
        return List.copyOf(mapper.findByClassIdentity(runtimeInstanceId, binaryClassName, classLoaderId));
    }

    private static void validate(BytecodeMetadata value) {
        requireText(value.runtimeInstanceId(), "runtimeInstanceId");
        requireText(value.agentId(), "agentId");
        requireText(value.binaryClassName(), "binaryClassName");
        requireText(value.classLoaderId(), "classLoaderId");
        requireText(value.snapshotKind(), "snapshotKind");
        requireText(value.transformationStatus(), "transformationStatus");
        if (value.revision() < 0 || value.sizeBytes() != null && value.sizeBytes() < 0) {
            throw new IllegalArgumentException("revision and size must be non-negative");
        }
        if (value.diagnosticsJson() == null || value.diagnosticsJson().length() > MAX_DIAGNOSTICS_CHARS) {
            throw new IllegalArgumentException("diagnosticsJson exceeds " + MAX_DIAGNOSTICS_CHARS + " characters");
        }
        Objects.requireNonNull(value.observedAt(), "observedAt");
        Objects.requireNonNull(value.createdAt(), "createdAt");
        Objects.requireNonNull(value.updatedAt(), "updatedAt");
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }

    public record BytecodeMetadata(String runtimeInstanceId, String agentId,
                                   String binaryClassName, String classLoaderId,
                                   long revision, String snapshotKind, String bytecodeHash,
                                   Long sizeBytes, String transformationStatus,
                                   String diagnosticsJson, Timestamp observedAt,
                                   Timestamp createdAt, Timestamp updatedAt) {
    }
}
