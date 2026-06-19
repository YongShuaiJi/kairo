package com.example.runtimemock.platform.worker;

import com.example.runtimemock.platform.crypto.EnvelopeEncryptionService;
import com.example.runtimemock.platform.service.PlatformJson;
import com.example.runtimemock.storage.ObjectStorage;
import com.example.runtimemock.storage.PutObjectRequest;
import com.example.runtimemock.storage.StoredObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Component
@ConditionalOnProperty(prefix = "runtime-mock.platform.worker", name = "enabled", havingValue = "true")
public class WorkerArtifactStore {

    private final ObjectStorage objectStorage;
    private final EnvelopeEncryptionService encryptionService;
    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    @Autowired
    public WorkerArtifactStore(ObjectStorage objectStorage, EnvelopeEncryptionService encryptionService,
                               JdbcTemplate jdbcTemplate) {
        this(objectStorage, encryptionService, jdbcTemplate, Clock.systemUTC());
    }

    WorkerArtifactStore(ObjectStorage objectStorage, EnvelopeEncryptionService encryptionService,
                        JdbcTemplate jdbcTemplate, Clock clock) {
        this.objectStorage = objectStorage;
        this.encryptionService = encryptionService;
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    public ArtifactObject putJson(String workerType, String ownerType, String ownerId,
                                  String artifactType, Object payload, Map<String, Object> metadata) {
        byte[] plaintext = PlatformJson.bytes(payload);
        String contentHash = PlatformJson.sha256(payload);
        String objectId = "artifact-" + UUID.randomUUID();
        String objectKey = String.join("/", workerType, ownerType, ownerId, objectId + ".json.enc");
        String encryptionScope = ownerType + ":" + ownerId;
        EnvelopeEncryptionService.EncryptedPayload encrypted = encryptionService.encrypt(plaintext, encryptionScope);
        Map<String, String> objectMetadata = new LinkedHashMap<>(encrypted.metadata());
        objectMetadata.put("plaintext-sha256", contentHash);
        objectMetadata.put("artifact-type", artifactType);
        StoredObject stored = objectStorage.put(new PutObjectRequest(
                objectKey,
                encrypted.content(),
                "application/octet-stream",
                contentHash,
                objectMetadata
        ));
        Instant now = clock.instant();
        Map<String, Object> databaseMetadata = new LinkedHashMap<>(metadata);
        databaseMetadata.put("provider", stored.provider());
        databaseMetadata.put("bucket", stored.bucket());
        databaseMetadata.put("objectKey", stored.objectKey());
        databaseMetadata.put("versionId", stored.versionId());
        databaseMetadata.put("encryption", encrypted.metadata());
        jdbcTemplate.update("""
                insert into worker_artifact(
                    id, worker_type, owner_type, owner_id, artifact_type, object_uri,
                    content_hash, bytes_count, metadata_json, created_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, objectId, workerType, ownerType, ownerId, artifactType, stored.objectUri(),
                contentHash, stored.bytesCount(), PlatformJson.write(databaseMetadata), Timestamp.from(now));
        return new ArtifactObject(objectId, stored.objectUri(), contentHash, stored.bytesCount());
    }

    public record ArtifactObject(String id, String objectUri, String contentHash, long bytesCount) {
    }
}
