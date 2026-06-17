package com.example.runtimemock.sidecar;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public final class StableTokenizer {

    private final byte[] domainKey;

    public StableTokenizer(byte[] domainKey) {
        this.domainKey = domainKey.clone();
    }

    public String tokenize(String domain, String fieldPath, Object value) {
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
}
