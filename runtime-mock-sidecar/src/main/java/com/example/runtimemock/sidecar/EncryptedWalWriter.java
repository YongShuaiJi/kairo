package com.example.runtimemock.sidecar;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public final class EncryptedWalWriter {

    private static final int GCM_TAG_BITS = 128;
    private static final int NONCE_BYTES = 12;

    private final Path walFile;
    private final SecretKey key;
    private final Clock clock;
    private final SecureRandom random;
    private final AtomicLong sequence = new AtomicLong();
    private final ObjectMapper mapper = JsonMapper.builder()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    public EncryptedWalWriter(Path directory, SecretKey key) {
        this(directory, key, Clock.systemUTC(), new SecureRandom());
    }

    EncryptedWalWriter(Path directory, SecretKey key, Clock clock, SecureRandom random) {
        this.walFile = directory.resolve("runtime-mock-recording.wal");
        this.key = key;
        this.clock = clock;
        this.random = random;
        try {
            Files.createDirectories(directory);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot create WAL directory: " + directory, e);
        }
    }

    public synchronized WalAppendResult append(Object maskedPayload) {
        try {
            long nextSequence = sequence.incrementAndGet();
            Instant timestamp = clock.instant();
            byte[] plaintext = mapper.writeValueAsBytes(maskedPayload);
            String payloadHash = sha256(plaintext);
            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);
            byte[] ciphertext = encrypt(nonce, plaintext, associatedData(nextSequence, timestamp, payloadHash));

            Map<String, Object> line = new LinkedHashMap<>();
            line.put("sequence", nextSequence);
            line.put("timestamp", timestamp.toString());
            line.put("payloadHash", payloadHash);
            line.put("nonce", Base64.getEncoder().encodeToString(nonce));
            line.put("ciphertext", Base64.getEncoder().encodeToString(ciphertext));
            Files.writeString(walFile, mapper.writeValueAsString(line) + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            return new WalAppendResult(nextSequence, timestamp, payloadHash, plaintext.length, ciphertext.length);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot append encrypted WAL record", e);
        }
    }

    public synchronized List<WalRecord> readAll() {
        try {
            if (!Files.exists(walFile)) {
                return List.of();
            }
            List<WalRecord> records = new ArrayList<>();
            for (String line : Files.readAllLines(walFile, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                Map<String, Object> row = mapper.readValue(line, new TypeReference<>() {
                });
                long rowSequence = ((Number) row.get("sequence")).longValue();
                Instant rowTimestamp = Instant.parse(String.valueOf(row.get("timestamp")));
                String payloadHash = String.valueOf(row.get("payloadHash"));
                byte[] nonce = Base64.getDecoder().decode(String.valueOf(row.get("nonce")));
                byte[] ciphertext = Base64.getDecoder().decode(String.valueOf(row.get("ciphertext")));
                byte[] plaintext = decrypt(nonce, ciphertext, associatedData(rowSequence, rowTimestamp, payloadHash));
                if (!sha256(plaintext).equals(payloadHash)) {
                    throw new IllegalStateException("WAL payload hash mismatch at sequence " + rowSequence);
                }
                Object payload = mapper.readValue(plaintext, Object.class);
                records.add(new WalRecord(rowSequence, rowTimestamp, payloadHash, payload));
            }
            return List.copyOf(records);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read encrypted WAL", e);
        }
    }

    public Path walFile() {
        return walFile;
    }

    private byte[] encrypt(byte[] nonce, byte[] plaintext, byte[] associatedData) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, nonce));
        cipher.updateAAD(associatedData);
        return cipher.doFinal(plaintext);
    }

    private byte[] decrypt(byte[] nonce, byte[] ciphertext, byte[] associatedData) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, nonce));
        cipher.updateAAD(associatedData);
        return cipher.doFinal(ciphertext);
    }

    private byte[] associatedData(long recordSequence, Instant timestamp, String payloadHash) {
        return (recordSequence + "\n" + timestamp + "\n" + payloadHash).getBytes(StandardCharsets.UTF_8);
    }

    private static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
