package com.example.runtimemock.platform.storage;

import com.example.runtimemock.storage.ObjectStorage;
import com.example.runtimemock.storage.PutObjectRequest;
import com.example.runtimemock.storage.StoredObject;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class InMemoryObjectStorage implements ObjectStorage {

    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();

    @Override
    public StoredObject put(PutObjectRequest request) {
        byte[] content = request.content();
        objects.put(request.objectKey(), content);
        return new StoredObject("memory", "test", request.objectKey(),
                "memory://test/" + request.objectKey(), "", "", request.contentHash(),
                content.length, request.metadata());
    }

    @Override
    public InputStream get(String objectKey) {
        byte[] content = objects.get(objectKey);
        if (content == null) {
            throw new IllegalArgumentException("Object not found: " + objectKey);
        }
        return new ByteArrayInputStream(content);
    }

    @Override
    public void delete(String objectKey) {
        objects.remove(objectKey);
    }
}
