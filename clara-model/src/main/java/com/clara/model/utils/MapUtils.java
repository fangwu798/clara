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

    /**
     * 值非 null 时写入映射。
     *
     * <p>用于拼装请求体或响应元数据：调用方不必重复写 if 判空，值为 null 时静默跳过，
     * 避免把 null 写进映射后再被序列化或透传给上层。
     *
     * @param target 目标映射，不可为 null
     * @param key    字段名，不可为 null
     * @param value  字段值，为 null 时不写入
     * @param <V>    值类型
     */
    public static <V> void putIfNotNull(Map<String, V> target, String key, V value) {
        if (value != null) {
            target.put(key, value);
        }
    }
}
