package com.example.runtimemock.storage;

import java.io.InputStream;

public interface ObjectStorage {

    StoredObject put(PutObjectRequest request);

    InputStream get(String objectKey);

    void delete(String objectKey);
}
