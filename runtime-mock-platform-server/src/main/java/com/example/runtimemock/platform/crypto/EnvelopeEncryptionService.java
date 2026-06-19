package com.example.runtimemock.platform.crypto;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

public final class EnvelopeEncryptionService {

    private static final int GCM_TAG_BITS = 128;
    private static final int NONCE_BYTES = 12;

    private final KeyEncryptionService keyEncryptionService;
    private final SecureRandom random = new SecureRandom();

    public EnvelopeEncryptionService(KeyEncryptionService keyEncryptionService) {
        this.keyEncryptionService = keyEncryptionService;
    }

    public EncryptedPayload encrypt(byte[] plaintext, String scope) {
        byte[] dek = randomBytes(32);
        try {
            byte[] nonce = randomBytes(NONCE_BYTES);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(dek, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, nonce));
            cipher.updateAAD(scope.getBytes(StandardCharsets.UTF_8));
            byte[] ciphertext = cipher.doFinal(plaintext);
            KeyEncryptionService.WrappedKey wrappedKey = keyEncryptionService.wrap(dek, scope);
            Map<String, String> metadata = new LinkedHashMap<>();
            metadata.put("encryption", "AES-256-GCM");
            metadata.put("encryption-scope", scope);
            metadata.put("data-nonce", Base64.getEncoder().encodeToString(nonce));
            metadata.put("wrapped-dek", Base64.getEncoder().encodeToString(wrappedKey.ciphertext()));
            metadata.put("wrapped-dek-nonce", Base64.getEncoder().encodeToString(wrappedKey.nonce()));
            metadata.put("kek-version", wrappedKey.keyVersion());
            return new EncryptedPayload(ciphertext, metadata);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot encrypt object payload", e);
        } finally {
            Arrays.fill(dek, (byte) 0);
        }
    }

    public byte[] decrypt(byte[] ciphertext, Map<String, String> metadata, String scope) {
        byte[] dek = null;
        try {
            KeyEncryptionService.WrappedKey wrappedKey = new KeyEncryptionService.WrappedKey(
                    required(metadata, "kek-version"),
                    Base64.getDecoder().decode(required(metadata, "wrapped-dek-nonce")),
                    Base64.getDecoder().decode(required(metadata, "wrapped-dek"))
            );
            dek = keyEncryptionService.unwrap(wrappedKey, scope);
            byte[] nonce = Base64.getDecoder().decode(required(metadata, "data-nonce"));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(dek, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, nonce));
            cipher.updateAAD(scope.getBytes(StandardCharsets.UTF_8));
            return cipher.doFinal(ciphertext);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot decrypt object payload", e);
        } finally {
            if (dek != null) {
                Arrays.fill(dek, (byte) 0);
            }
        }
    }

    private String required(Map<String, String> metadata, String name) {
        String value = metadata.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing encryption metadata: " + name);
        }
        return value;
    }

    private byte[] randomBytes(int size) {
        byte[] bytes = new byte[size];
        random.nextBytes(bytes);
        return bytes;
    }

    public record EncryptedPayload(byte[] content, Map<String, String> metadata) {
        public EncryptedPayload {
            content = content.clone();
            metadata = Map.copyOf(metadata);
        }

        @Override
        public byte[] content() {
            return content.clone();
        }
    }
}
