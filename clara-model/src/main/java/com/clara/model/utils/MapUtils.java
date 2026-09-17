package com.clara.model.utils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 提供 Map 的通用校验与转换工具。
 */
public final class MapUtils {

    /**
     * 复制并校验映射为只读的标量映射。
     *
     * @param source 原始映射，可为 null
     * @return 只读的标量映射，输入为空时返回空映射
     * @throws IllegalArgumentException 键为空白或值非标量时
     */
    public static Map<String, Object> unmodifiableScalarMap(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            if (StringUtils.isBlank(entry.getKey())) {
                throw new IllegalArgumentException("map key must not be blank");
            }
            Object value = entry.getValue();
            if (!(value instanceof String) && !(value instanceof Number)
                    && !(value instanceof Boolean)) {
                throw new IllegalArgumentException("map values must be scalar");
            }
            result.put(entry.getKey(), value);
        }
        return Collections.unmodifiableMap(result);
    }
}
