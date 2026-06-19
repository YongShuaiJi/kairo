package com.example.runtimemock.platform.crypto;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class EnvelopeEncryptionServiceTest {

    @Test
    void encryptsWithRandomDekAndDecryptsWithBoundScope() {
        EncryptionProperties properties = new EncryptionProperties();
        properties.setMasterKeyBase64("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        properties.setKeyVersion("test-v1");
        EnvelopeEncryptionService service =
                new EnvelopeEncryptionService(new LocalKeyEncryptionService(properties));
        byte[] plaintext = "{\"secret\":\"value\"}".getBytes(StandardCharsets.UTF_8);

        var first = service.encrypt(plaintext, "dataset:one");
        var second = service.encrypt(plaintext, "dataset:one");

        assertThat(first.content()).isNotEqualTo(plaintext);
        assertThat(first.content()).isNotEqualTo(second.content());
        assertThat(first.metadata()).containsEntry("kek-version", "test-v1");
        assertThat(service.decrypt(first.content(), first.metadata(), "dataset:one")).isEqualTo(plaintext);
        assertThat(Arrays.equals(first.content(), plaintext)).isFalse();
    }
}
