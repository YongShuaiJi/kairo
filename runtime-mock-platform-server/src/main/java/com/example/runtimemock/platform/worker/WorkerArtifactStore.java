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

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
        return new ArtifactObject(objectId, stored.objectUri(), contentHash, stored.bytesCount(),
                Map.copyOf(databaseMetadata));
    }

    public List<Map<String, Object>> readRows(String objectUri, String objectType,
                                              Map<String, Object> metadata) {
        byte[] ciphertext;
        try (InputStream input = objectStorage.get(objectKey(objectUri))) {
            ciphertext = input.readAllBytes();
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot read dataset object " + objectUri, exception);
        }
        Map<String, String> encryptionMetadata = encryptionMetadata(metadata);
        String scope = String.valueOf(metadata.getOrDefault(
                "encryptionScope",
                encryptionMetadata.getOrDefault("encryption-scope", "")
        ));
        if (scope.isBlank()) {
            throw new IllegalStateException("Dataset object is missing encryption scope: " + objectUri);
        }
        byte[] plaintext = encryptionService.decrypt(ciphertext, encryptionMetadata, scope);
        String content = new String(plaintext, StandardCharsets.UTF_8);
        if (objectType.contains("JSONL")) {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (String line : content.split("\\R")) {
                if (!line.isBlank()) {
                    rows.add(PlatformJson.readMap(line));
                }
            }
            return rows;
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object item : PlatformJson.readList(content)) {
            if (!(item instanceof Map<?, ?> map)) {
                throw new IllegalStateException("Dataset row must be a JSON object: " + objectUri);
            }
            rows.add(PlatformJson.stringKeyMap(map));
        }
        return rows;
    }

    private Map<String, String> encryptionMetadata(Map<String, Object> metadata) {
        Object encryption = metadata.get("encryption");
        Map<?, ?> source = encryption instanceof Map<?, ?> map ? map : metadata;
        Map<String, String> values = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (value != null) {
                values.put(String.valueOf(key), String.valueOf(value));
            }
        });
        return values;
    }

    private String objectKey(String objectUri) {
        URI uri = URI.create(objectUri);
        String path = uri.getPath();
        if (path == null || path.isBlank() || "/".equals(path)) {
            throw new IllegalArgumentException("Object URI has no key: " + objectUri);
        }
        return path.startsWith("/") ? path.substring(1) : path;
    }

    public record ArtifactObject(String id, String objectUri, String contentHash, long bytesCount,
                                 Map<String, Object> metadata) {
    }
}
