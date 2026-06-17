package com.example.runtimemock.sidecar;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

public final class PayloadMasker {

    private static final Pattern JWT_PATTERN = Pattern.compile("^[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+$");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern CARD_PATTERN = Pattern.compile("^\\d{12,19}$");

    private final MaskingPolicy policy;
    private final StableTokenizer tokenizer;
    private final String tokenDomain;

    public PayloadMasker(MaskingPolicy policy, StableTokenizer tokenizer, String tokenDomain) {
        this.policy = policy;
        this.tokenizer = tokenizer;
        this.tokenDomain = tokenDomain;
    }

    public Object mask(Object payload) {
        return maskValue("$", "", payload, 0);
    }

    @SuppressWarnings("unchecked")
    private Object maskValue(String path, String fieldName, Object value, int depth) {
        if (depth > policy.maxDepth()) {
            return "[MAX_DEPTH]";
        }
        if (!policy.isAllowed(path)) {
            return null;
        }
        boolean sensitive = isSensitive(fieldName, value);
        MaskingAction action = policy.actionFor(path, sensitive);
        if (action != null) {
            return apply(path, value, action);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> masked = new LinkedHashMap<>();
            map.forEach((key, item) -> {
                String childName = String.valueOf(key);
                String childPath = "$".equals(path) ? childName : path + "." + childName;
                Object childValue = maskValue(childPath, childName, item, depth + 1);
                if (childValue != null) {
                    masked.put(childName, childValue);
                }
            });
            return masked;
        }
        if (value instanceof List<?> list) {
            List<Object> masked = new ArrayList<>();
            int limit = Math.min(list.size(), policy.maxCollectionSize());
            for (int i = 0; i < limit; i++) {
                masked.add(maskValue(path + "[" + i + "]", fieldName, list.get(i), depth + 1));
            }
            if (list.size() > limit) {
                masked.add("[TRUNCATED:" + (list.size() - limit) + "]");
            }
            return masked;
        }
        if (value instanceof String text && text.length() > policy.maxStringLength()) {
            return text.substring(0, policy.maxStringLength()) + "[TRUNCATED]";
        }
        return value;
    }

    private Object apply(String path, Object value, MaskingAction action) {
        return switch (action) {
            case DROP -> null;
            case MASK -> policy.fixedValue();
            case HASH -> sha256(String.valueOf(value));
            case TOKENIZE -> tokenizer.tokenize(tokenDomain, path, value);
            case GENERALIZE -> generalize(value);
            case FIXED -> policy.fixedValue();
            case PRESERVE_FORMAT -> preserveFormat(value);
        };
    }

    private boolean isSensitive(String fieldName, Object value) {
        String normalized = fieldName == null ? "" : fieldName.toLowerCase(Locale.ROOT);
        if (normalized.contains("password")
                || normalized.contains("passwd")
                || normalized.contains("secret")
                || normalized.contains("token")
                || normalized.contains("cookie")
                || normalized.contains("authorization")
                || normalized.contains("jwt")
                || normalized.contains("session")
                || normalized.contains("credential")
                || normalized.contains("phone")
                || normalized.contains("email")
                || normalized.contains("idcard")
                || normalized.contains("card")) {
            return true;
        }
        if (value instanceof String text) {
            String trimmed = text.trim();
            return JWT_PATTERN.matcher(trimmed).matches()
                    || EMAIL_PATTERN.matcher(trimmed).matches()
                    || CARD_PATTERN.matcher(trimmed).matches();
        }
        return false;
    }

    private Object generalize(Object value) {
        if (value instanceof Number) {
            return 0;
        }
        if (value instanceof Boolean) {
            return false;
        }
        return "[GENERALIZED]";
    }

    private Object preserveFormat(Object value) {
        String text = String.valueOf(value);
        StringBuilder builder = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (Character.isDigit(ch)) {
                builder.append('0');
            } else if (Character.isLetter(ch)) {
                builder.append('X');
            } else {
                builder.append(ch);
            }
        }
        return builder.toString();
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
