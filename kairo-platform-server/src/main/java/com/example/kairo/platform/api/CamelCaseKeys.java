package com.example.kairo.platform.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * V1.6 &sect;2.2 / &sect;3: database rows carry snake_case columns; the public API
 * must never leak them. This normaliser recursively rewrites map keys to camelCase
 * so the first-class resource-family endpoints publish stable camelCase JSON
 * regardless of how the service layer lower-cases its rows.
 */
final class CamelCaseKeys {

    private CamelCaseKeys() {
    }

    /** Recursively rewrite every map key in a value from snake_case to camelCase. */
    static Object normalize(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(toCamelCase(String.valueOf(k)), normalize(v)));
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) {
                out.add(normalize(item));
            }
            return out;
        }
        return value;
    }

    /** {@code application_id} -&gt; {@code applicationId}; leaves already-camelCase keys intact. */
    static String toCamelCase(String key) {
        if (key == null || key.isEmpty()) {
            return key;
        }
        StringBuilder out = new StringBuilder(key.length());
        boolean upperNext = false;
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c == '_') {
                upperNext = true;
            } else if (upperNext) {
                out.append(Character.toUpperCase(c));
                upperNext = false;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
