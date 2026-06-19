package com.example.runtimemock.platform.storage;

import com.example.runtimemock.storage.ObjectStorage;
import com.example.runtimemock.storage.minio.MinioObjectStorage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnExpression(
        "'${runtime-mock.platform.worker.enabled:false}' == 'true' || "
                + "'${runtime-mock.platform.recording.ingestion.enabled:false}' == 'true'")
public class ObjectStorageConfiguration {

    @Bean
    ObjectStorage objectStorage(ObjectStorageProperties properties) {
        return switch (properties.getProvider().toLowerCase()) {
            case "memory" -> new InMemoryObjectStorage();
            case "minio" -> {
                if (properties.getSecretKey() == null || properties.getSecretKey().isBlank()) {
                    throw new IllegalStateException(
                            "runtime-mock.platform.object-storage.secret-key is required for MinIO");
                }
                yield new MinioObjectStorage(
                        properties.getEndpoint(),
                        properties.getAccessKey(),
                        properties.getSecretKey(),
                        properties.getBucket(),
                        properties.isCreateBucket()
                );
            }
            default -> throw new IllegalStateException(
                    "Unsupported object storage provider: " + properties.getProvider());
        };
    }
}
