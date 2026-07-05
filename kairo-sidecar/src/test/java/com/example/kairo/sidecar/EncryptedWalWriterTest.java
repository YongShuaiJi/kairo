package com.example.kairo.sidecar;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EncryptedWalWriterTest {

    @TempDir
    Path tempDir;

    @Test
    void encryptsWalRecordsAndReadsThemBack() throws Exception {
        byte[] keyBytes = "0123456789abcdef".getBytes(StandardCharsets.UTF_8);
        EncryptedWalWriter writer = new EncryptedWalWriter(
                tempDir,
                new SecretKeySpec(keyBytes, "AES"),
                Clock.fixed(Instant.parse("2026-06-17T00:00:00Z"), ZoneOffset.UTC),
                new java.security.SecureRandom(new byte[]{1, 2, 3, 4})
        );

        WalAppendResult result = writer.append(Map.of(
                "orderId", "order-123",
                "authorization", "***"
        ));

        assertThat(result.sequence()).isEqualTo(1);
        String walText = Files.readString(writer.walFile(), StandardCharsets.UTF_8);
        assertThat(walText).doesNotContain("order-123");
        assertThat(walText).doesNotContain("authorization");

        List<WalRecord> records = writer.readAll();
        assertThat(records).hasSize(1);
        assertThat(records.get(0).sequence()).isEqualTo(1);
        assertThat(records.get(0).payload()).isEqualTo(Map.of(
                "orderId", "order-123",
                "authorization", "***"
        ));
    }
}
