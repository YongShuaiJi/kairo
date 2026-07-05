package com.example.kairo.sidecar;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public final class StableTokenizer implements AutoCloseable {

    private final byte[] domainKey;

    public StableTokenizer(byte[] domainKey) {
        this.domainKey = domainKey.clone();
    }

    public String tokenize(String domain, String fieldPath, Object value) {
        if (destroyed()) {
            throw new IllegalStateException("Tokenizer key has been destroyed");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(domainKey, "HmacSHA256"));
            String input = domain + '\n' + fieldPath + '\n' + String.valueOf(value);
            byte[] digest = mac.doFinal(input.getBytes(StandardCharsets.UTF_8));
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
            return "tok_" + token.substring(0, 32);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot tokenize value", e);
        }
    }

    public boolean destroyed() {
        int aggregate = 0;
        for (byte value : domainKey) {
            aggregate |= value;
        }
        return aggregate == 0;
    }

    @Override
    public void close() {
        java.util.Arrays.fill(domainKey, (byte) 0);
    }
}
