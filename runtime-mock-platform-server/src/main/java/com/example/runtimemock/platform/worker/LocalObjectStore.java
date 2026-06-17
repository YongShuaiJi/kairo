package com.example.runtimemock.platform.worker;

import com.example.runtimemock.platform.service.PlatformJson;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Component
public class LocalObjectStore {

    private final Path root;
    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    @Autowired
    public LocalObjectStore(@Value("${runtime-mock.platform.object-store.local-root:./data/platform-objects}") String root,
                            JdbcTemplate jdbcTemplate) {
        this(Path.of(root), jdbcTemplate, Clock.systemUTC());
    }

    LocalObjectStore(Path root, JdbcTemplate jdbcTemplate, Clock clock) {
        this.root = root.toAbsolutePath().normalize();
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    public StoredObject putJson(String workerType, String ownerType, String ownerId,
                                String artifactType, Object payload, Map<String, Object> metadata) {
        byte[] bytes = PlatformJson.bytes(payload);
        String contentHash = PlatformJson.sha256(payload);
        String objectId = "artifact-" + UUID.randomUUID();
        Instant now = clock.instant();
        Path path = root
                .resolve(workerType)
                .resolve(ownerType)
                .resolve(ownerId)
                .resolve(objectId + ".json");
        try {
            Files.createDirectories(path.getParent());
            Files.write(path, bytes);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot write worker artifact", e);
        }
        String objectUri = path.toUri().toString();
        jdbcTemplate.update("""
                insert into worker_artifact(
                    id, worker_type, owner_type, owner_id, artifact_type, object_uri,
                    content_hash, bytes_count, metadata_json, created_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, objectId, workerType, ownerType, ownerId, artifactType, objectUri,
                contentHash, bytes.length, PlatformJson.write(metadata), Timestamp.from(now));
        return new StoredObject(objectId, objectUri, contentHash, bytes.length);
    }

    public record StoredObject(String id, String objectUri, String contentHash, long bytesCount) {
    }
}
