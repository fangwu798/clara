package com.clara.utils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Map 的通用校验与拼装工具。
 *
 * <p>定位：无状态的纯函数集合，服务于「请求体 / 响应元数据」的组装与元数据固化两处场景。
 *
 * <p>使用方法：
 * <ul>
 *   <li>无需实例化——构造器私有，全部方法为 {@code static}；</li>
 *   <li>把外部传入的元数据固化为只读快照（并校验键非空白、值为标量）用
 *       {@link #unmodifiableScalarMap(Map)}；</li>
 *   <li>拼装请求体 / 元数据时用 {@link #putIfNotNull(Map, String, Object)} 代替手写 if 判空。</li>
 * </ul>
 *
 * <p>线程安全：无状态；返回的映射除 {@link #unmodifiableScalarMap(Map)} 明确只读外，
 * 其余方法操作的 {@code target} 由调用方自行保证不并发写。
 *
 * <p>失败语义：{@link #unmodifiableScalarMap(Map)} 在键为空白或值非标量时抛
 * {@link IllegalArgumentException}；{@link #putIfNotNull(Map, String, Object)} 不抛异常。
 */
public final class MapUtils {

    private MapUtils() {
    }

    /**
     * 复制并校验映射为只读的标量映射。
     *
     * <p>复制而非包装：调用方后续修改原始映射不会影响返回值，保证快照语义。
     *
     * @param source 原始映射，可为 null
     * @return 只读的标量映射；输入为 null 或空时返回 {@link Collections#emptyMap()}
     * @throws IllegalArgumentException 键为 null 或空白、或值不是 String / Number / Boolean 时
     */
    public static Map<String, Object> unmodifiableScalarMap(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, Object> result = new LinkedHashMap<>();
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
