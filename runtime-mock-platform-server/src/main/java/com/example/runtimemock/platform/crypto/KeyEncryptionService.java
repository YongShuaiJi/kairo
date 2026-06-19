package com.example.runtimemock.platform.crypto;

public interface KeyEncryptionService {

    WrappedKey wrap(byte[] dataEncryptionKey, String scope);

    byte[] unwrap(WrappedKey wrappedKey, String scope);

    record WrappedKey(String keyVersion, byte[] nonce, byte[] ciphertext) {
        public WrappedKey {
            nonce = nonce.clone();
            ciphertext = ciphertext.clone();
        }

        @Override
        public byte[] nonce() {
            return nonce.clone();
        }

        @Override
        public byte[] ciphertext() {
            return ciphertext.clone();
        }
    }
}
