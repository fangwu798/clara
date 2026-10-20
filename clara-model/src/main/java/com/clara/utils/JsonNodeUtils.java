package com.clara.utils;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 协议解析用的 JSON 节点读取工具。
 *
 * <p>定位：把「读一个平台协议字段」这件事的宽严口径固定下来，供各协议编解码器复用，使调用方只需关注
 * 字段到领域类型的映射。配置解析不在本类职责内——配置的形状由配置类上的 {@code @JsonProperty} 注解
 * 声明，严格校验由配置层的绑定器负责。
 *
 * <p>使用方法：
 * <ul>
 *   <li>无需实例化——构造器私有，全部方法为 {@code static}；</li>
 *   <li>读可选的元数据字段（类型不符可以按缺失处理）用 {@link #textOrNull(JsonNode)}；</li>
 *   <li>读协议字段（类型不符属于数据非法，必须报错）用 {@link #strictTextOrNull(JsonNode)} 或按字段名读取的
 *       {@link #strictTextOrNull(JsonNode, String)}；</li>
 *   <li>读计数类字段：缺失与 0 等价时用 {@link #getIntValue(JsonNode, String)}，
 *       必须区分「平台未提供」与「值为 0」（如 token 用量）时用 {@link #optionalIntValue(JsonNode, String)}。</li>
 * </ul>
 *
 * <p>线程安全：无状态、无可变字段，可跨线程并发调用；传入的 {@link JsonNode} 由调用方保证不被并发修改。
 *
 * <p>失败语义：只有严格模式的 {@link #strictTextOrNull(JsonNode)} 会抛
 * {@link IllegalArgumentException}（节点存在但类型不是文本）；其余方法不抛异常，一律用 null 或 0 表达
 * 「缺失或非法」。
 */
public final class JsonNodeUtils {

    private JsonNodeUtils() {
    }

    /**
     * 读取节点自身的文本值（宽松模式）。
     *
     * <p>不做任何校验：节点为 null、NullNode 或类型不是文本时一律返回 null，适用于读取可选的元数据字段，
     * 由调用方自行决定缺失是否可接受。
     *
     * @param node 待读取的节点，可为 null
     * @return 文本值；节点为 null、NullNode 或非文本时返回 null
     */
    public static String textOrNull(JsonNode node) {
        return node != null && node.isTextual() ? node.textValue() : null;
    }

    /**
     * 读取节点自身的文本值（严格模式）。
     *
     * <p>与 {@link #textOrNull(JsonNode)} 的区别：节点存在但类型不是文本时直接报错，而不是宽松地按缺失
     * 处理，适合协议解析——字段类型不符属于数据非法，不能静默吞掉。节点为 null 或 NullNode 时仍返回
     * null，由调用方决定缺失是否可接受；空串属于合法文本，原样返回。
     *
     * @param node 待读取的节点，可为 null
     * @return 文本值；节点为 null 或 NullNode 时返回 null
     * @throws IllegalArgumentException 节点存在但不是文本时
     */
    public static String strictTextOrNull(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            throw new IllegalArgumentException("value must be a text");
        }
        return node.textValue();
    }

    /**
     * 读取指定字段的文本值（严格模式）。
     *
     * <p>等价于 {@code strictTextOrNull(parent.get(field))}：字段缺失或为 null 时返回 null，
     * 字段存在但不是文本时报错。
     *
     * @param parent 父节点，不可为 null
     * @param field  字段名，不可为 null
     * @return 字段文本值；字段缺失或为 null 时返回 null
     * @throws IllegalArgumentException 字段存在但不是文本时
     */
    public static String strictTextOrNull(JsonNode parent, String field) {
        return strictTextOrNull(parent.get(field));
    }

    /**
     * 读取整数字段（宽松模式），字段缺失或不是 int 范围内的整数时返回 0。
     *
     * <p>不校验、不抛异常，适用于可选的计数类字段，由调用方自行决定 0 与「缺失」是否等价。
     *
     * @param parent 父节点，不可为 null
     * @param field  字段名，不可为 null
     * @return 整数值；字段缺失、非整数或超出 int 范围时返回 0
     */
    public static int getIntValue(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            return 0;
        }
        return value.intValue();
    }

    /**
     * 读取可选的整数字段，区分「缺失」与「值为 0」。
     *
     * <p>与 {@link #getIntValue(JsonNode, String)} 的区别：字段缺失、非整数或超出 int 范围时返回 null，
     * 适用于「平台未提供时应当推导或忽略」的计数类字段（如 token 用量里的总量与缓存命中量），避免把缺失
     * 当成 0 而丢掉上层推导逻辑。
     *
     * @param parent 父节点，不可为 null
     * @param field  字段名，不可为 null
     * @return 整数值；字段缺失、非整数或超出 int 范围时返回 null
     */
    public static Integer optionalIntValue(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            return null;
        }
        return value.intValue();
    }
}
