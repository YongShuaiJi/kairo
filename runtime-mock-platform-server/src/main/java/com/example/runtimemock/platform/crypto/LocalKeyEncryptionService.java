package com.example.runtimemock.platform.crypto;

import jakarta.annotation.PreDestroy;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

public final class LocalKeyEncryptionService implements KeyEncryptionService {

    private static final int GCM_TAG_BITS = 128;
    private static final int NONCE_BYTES = 12;

    private final byte[] masterKey;
    private final String keyVersion;
    private final SecureRandom random = new SecureRandom();

    public LocalKeyEncryptionService(EncryptionProperties properties) {
        this.masterKey = loadMasterKey(properties);
        this.keyVersion = properties.getKeyVersion();
    }

    @Override
    public WrappedKey wrap(byte[] dataEncryptionKey, String scope) {
        byte[] nonce = randomBytes(NONCE_BYTES);
        return new WrappedKey(keyVersion, nonce, crypt(Cipher.ENCRYPT_MODE, dataEncryptionKey, nonce, scope));
    }

    @Override
    public byte[] unwrap(WrappedKey wrappedKey, String scope) {
        if (!keyVersion.equals(wrappedKey.keyVersion())) {
            throw new IllegalArgumentException("Unknown local KEK version: " + wrappedKey.keyVersion());
        }
        return crypt(Cipher.DECRYPT_MODE, wrappedKey.ciphertext(), wrappedKey.nonce(), scope);
    }

    @PreDestroy
    public void destroy() {
        Arrays.fill(masterKey, (byte) 0);
    }

    private byte[] crypt(int mode, byte[] input, byte[] nonce, String scope) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, new SecretKeySpec(masterKey, "AES"), new GCMParameterSpec(GCM_TAG_BITS, nonce));
            cipher.updateAAD(scope.getBytes(StandardCharsets.UTF_8));
            return cipher.doFinal(input);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot process data encryption key", e);
        }
    }

    private byte[] randomBytes(int size) {
        byte[] bytes = new byte[size];
        random.nextBytes(bytes);
        return bytes;
    }

    private static byte[] loadMasterKey(EncryptionProperties properties) {
        try {
            String encoded = properties.getMasterKeyBase64();
            if ((encoded == null || encoded.isBlank())
                    && properties.getMasterKeyFile() != null
                    && !properties.getMasterKeyFile().isBlank()) {
                encoded = Files.readString(Path.of(properties.getMasterKeyFile())).trim();
            }
            if (encoded == null || encoded.isBlank()) {
                throw new IllegalStateException(
                        "A base64 256-bit master key or master-key-file is required when encryption is enabled");
            }
            byte[] key = Base64.getDecoder().decode(encoded);
            if (key.length != 32) {
                throw new IllegalStateException("Master key must decode to exactly 32 bytes");
            }
            return key;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot load local master key", e);
        }
    }
}
