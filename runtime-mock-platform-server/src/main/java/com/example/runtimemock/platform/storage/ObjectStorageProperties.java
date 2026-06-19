package com.example.runtimemock.platform.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("runtime-mock.platform.object-storage")
public class ObjectStorageProperties {

    private String provider = "minio";
    private String endpoint = "http://127.0.0.1:9000";
    private String accessKey = "runtime_mock";
    private String secretKey = "";
    private String bucket = "runtime-mock";
    private boolean createBucket = true;

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getAccessKey() {
        return accessKey;
    }

    public void setAccessKey(String accessKey) {
        this.accessKey = accessKey;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    public String getBucket() {
        return bucket;
    }

    public void setBucket(String bucket) {
        this.bucket = bucket;
    }

    public boolean isCreateBucket() {
        return createBucket;
    }

    public void setCreateBucket(boolean createBucket) {
        this.createBucket = createBucket;
    }
}
