package com.clara.mcp.registry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ImmutableValues {
    private ImmutableValues() {
    }
    public static Map<String, Object> copyMap(Map<String, Object> source) {
        if (source == null) return Collections.emptyMap();
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            if (entry.getKey() == null) throw new IllegalArgumentException("map key must not be null");
            result.put(entry.getKey(), copy(entry.getValue()));
        }
        return Collections.unmodifiableMap(result);
    }
    public static List<Map<String, Object>> copyMapList(List<Map<String, Object>> source) {
        if (source == null) return Collections.emptyList();
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>(source.size());
        for (Map<String, Object> item : source) {
            if (item == null) throw new IllegalArgumentException("list must not contain null");
            result.add(copyMap(item));
        }
        return Collections.unmodifiableList(result);
    }
    private static Object copy(Object value) {
        if (value == null || value instanceof String || value instanceof Number
                || value instanceof Boolean) return value;
        if (value instanceof Map) {
            Map<?, ?> source = (Map<?, ?>) value;
            Map<String, Object> converted = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> entry : source.entrySet()) {
                if (!(entry.getKey() instanceof String)) {
                    throw new IllegalArgumentException("map keys must be strings");
                }
                converted.put((String) entry.getKey(), copy(entry.getValue()));
            }
            return Collections.unmodifiableMap(converted);
        }
        if (value instanceof List) {
            List<Object> result = new ArrayList<Object>();
            for (Object item : (List<?>) value) result.add(copy(item));
            return Collections.unmodifiableList(result);
        }
        throw new IllegalArgumentException("unsupported structured value: " + value.getClass().getName());
    }
}
