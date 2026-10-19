package com.clara.mcp.registry.capability;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 计算模式的稳定哈希值。
 */
public final class SchemaHasher {
    public String hash(Object value) {
        StringBuilder canonical = new StringBuilder();
        append(canonical, value, 0);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) result.append(String.format("%02x", item & 0xff));
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void append(StringBuilder target, Object value, int depth) {
        if (depth > 100) { throw new IllegalArgumentException("structured value is too deeply nested"); }
        if (value == null) { target.append('n'); return; }
        if (value instanceof String) {
            appendString(target, (String) value);
        } else if (value instanceof Boolean) {
            target.append('b').append(value);
        } else if (value instanceof Number) {
            appendNumber(target, (Number) value);
        } else if (value instanceof List) {
            appendList(target, (List<?>) value, depth);
        } else if (value instanceof Map) {
            appendMap(target, (Map<?, ?>) value, depth);
        } else {
            throw new IllegalArgumentException("unsupported structured value: " + value.getClass().getName());
        }
    }

    private static void appendString(StringBuilder target, String text) {
        target.append('s').append(text.length()).append(':').append(text);
    }

    private static void appendNumber(StringBuilder target, Number value) {
        target.append('d').append(value.getClass().getName()).append(':').append(value);
    }

    private static void appendList(StringBuilder target, List<?> values, int depth) {
        target.append('[');
        for (Object item : values) {
            append(target, item, depth + 1);
        }
        target.append(']');
    }

    private static void appendMap(StringBuilder target, Map<?, ?> map, int depth) {
        List<String> keys = sortedKeys(map);
        target.append('{');
        for (String key : keys) {
            append(target, key, depth + 1);
            append(target, map.get(key), depth + 1);
        }
        target.append('}');
    }

    private static List<String> sortedKeys(Map<?, ?> map) {
        List<String> keys = new ArrayList<>();
        for (Object key : map.keySet()) {
            if (!(key instanceof String)) {
                throw new IllegalArgumentException("map keys must be strings");
            }
            keys.add((String) key);
        }
        Collections.sort(keys);
        return keys;
    }
}
