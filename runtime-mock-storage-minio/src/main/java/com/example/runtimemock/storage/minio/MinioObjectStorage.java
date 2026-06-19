package com.example.runtimemock.storage.minio;

import com.example.runtimemock.storage.ObjectStorage;
import com.example.runtimemock.storage.ObjectStorageException;
import com.example.runtimemock.storage.PutObjectRequest;
import com.example.runtimemock.storage.StoredObject;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import java.io.ByteArrayInputStream;
import java.io.InputStream;

public final class MinioObjectStorage implements ObjectStorage {

    private final MinioClient client;
    private final String bucket;
    private final boolean createBucket;

    public MinioObjectStorage(String endpoint, String accessKey, String secretKey,
                              String bucket, boolean createBucket) {
        this.client = MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
        this.bucket = bucket;
        this.createBucket = createBucket;
        ensureBucket();
    }

    @Override
    public StoredObject put(PutObjectRequest request) {
        try {
            byte[] content = request.content();
            var response = client.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(request.objectKey())
                    .stream(new ByteArrayInputStream(content), content.length, -1)
                    .contentType(request.contentType())
                    .userMetadata(request.metadata())
                    .build());
            return new StoredObject(
                    "minio",
                    bucket,
                    request.objectKey(),
                    "s3://" + bucket + "/" + request.objectKey(),
                    response.versionId(),
                    response.etag(),
                    request.contentHash(),
                    content.length,
                    request.metadata()
            );
        } catch (Exception e) {
            throw new ObjectStorageException("Cannot store object " + request.objectKey(), e);
        }
    }

    @Override
    public InputStream get(String objectKey) {
        try {
            return client.getObject(GetObjectArgs.builder().bucket(bucket).object(objectKey).build());
        } catch (Exception e) {
            throw new ObjectStorageException("Cannot read object " + objectKey, e);
        }
    }

    @Override
    public void delete(String objectKey) {
        try {
            client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(objectKey).build());
        } catch (Exception e) {
            throw new ObjectStorageException("Cannot delete object " + objectKey, e);
        }
    }

    private void ensureBucket() {
        if (!createBucket) {
            return;
        }
        try {
            boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
            if (!exists) {
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            }
        } catch (Exception e) {
            throw new ObjectStorageException("Cannot initialize bucket " + bucket, e);
        }
    }
}
