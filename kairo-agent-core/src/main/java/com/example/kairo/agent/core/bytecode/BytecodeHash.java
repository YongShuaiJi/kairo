package com.example.kairo.agent.core.bytecode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 hashing helper for bytecode payloads. Used for input/output/applied
 * snapshot hashes and diff identity. Hex output is lowercase and stable.
 */
public final class BytecodeHash {

    private BytecodeHash() {
    }

    public static String sha256Hex(byte[] bytes) {
        return sha256Hex(bytes, 0, bytes.length);
    }

    public static String sha256Hex(byte[] bytes, int offset, int length) {
        if (bytes == null) {
            throw new IllegalArgumentException("bytes must not be null");
        }
        if (offset < 0 || length < 0 || offset + length > bytes.length) {
            throw new IllegalArgumentException("invalid offset/length for bytecode hash");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(bytes, offset, length);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static String sha256Hex(String value) {
        return sha256Hex(value.getBytes(StandardCharsets.UTF_8));
    }
}
