package com.clara.utils.deserialize;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 把 YAML 对象绑定为「只含标量值」的扩展参数映射。
 *
 * <p>是什么、在哪一层：本类属于 {@code clara-model} 的配置解析层（{@code com.clara.model.configuration.deserialize}），
 * 是实现层组件。它保住原先由配置加载器执行的约束——能力节点的 {@code parameters} 只允许字符串、数字与
 * 布尔值，不接受嵌套对象或列表，避免把结构化内容塞进「平台扩展参数」而绕过类型校验。
 *
 * <p>谁创建谁调用：由 Jackson 在绑定 {@code ProviderCapabilityConfiguration.parameters} 时自动实例化并调用；
 * 业务代码不直接使用本类。
 *
 * <p>使用方法：在 {@code Map<String, Object>} 类型的配置字段或构造器参数上标注
 * {@code @JsonDeserialize(using = ScalarMapDeserializer.class)}。绑定规则：文本 → {@code String}；
 * 整数 → 能放入 int 时为 {@code Integer}，否则 {@code Long}；小数 → {@code Double}；布尔 → {@code Boolean}。
 * 为保证「绑定完成后解析器不会残留未消费的 token」（严格模式会据此报错），本类逐 token 消费整个对象，
 * 不使用 {@code readValueAsTree}。
 *
 * <p>生命周期与线程安全：无状态，Jackson 可复用同一实例；返回的映射为可变 {@link LinkedHashMap}，
 * 由接收方（配置类构造器）自行冻结为只读视图。
 *
 * <p>失败语义：节点不是对象、或任一值不是标量（对象 / 数组 / null）时抛
 * {@link InvalidFormatException}（中文原因，由 {@code StrictYamlBinder} 附加字段路径后向上抛出）；
 * 字段缺失时不进入本方法，由 Jackson 传入 null。属于配置错误，不可重试。
 */
public final class ScalarMapDeserializer extends JsonDeserializer<Map<String, Object>> {

    /**
     * 绑定并校验扩展参数映射。
     *
     * @param parser  当前 JSON/YAML 解析器，非 null
     * @param context 反序列化上下文，非 null（本类不使用）
     * @return 参数映射（保持 YAML 中的书写顺序）；值为 null 时返回 null，表示未配置
     * @throws IOException             解析失败时
     * @throws InvalidFormatException 节点不是对象，或存在非标量值时
     */
    @Override
    public Map<String, Object> deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        if (parser.currentToken() == JsonToken.VALUE_NULL) {
            return null; // NOSONAR null 表示“未配置”，与空对象语义不同，调用方依赖这一区分
        }
        if (parser.currentToken() != JsonToken.START_OBJECT) {
            throw InvalidFormatException.from(parser, "扩展参数必须是键值对象", parser.getText(), Map.class);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        while (true) {
            JsonToken token = parser.nextToken();
            if (token == JsonToken.END_OBJECT) {
                return result;
            }
            if (token != JsonToken.FIELD_NAME) {
                throw InvalidFormatException.from(parser, "扩展参数必须是键值对象", parser.getText(), Map.class);
            }
            readEntry(parser, result);
        }
    }

    private static void readEntry(JsonParser parser, Map<String, Object> result) throws IOException {
        String name = parser.getCurrentName();
        JsonToken valueToken = parser.nextToken();
        if (valueToken == JsonToken.VALUE_STRING) {
            result.put(name, parser.getText());
        } else if (valueToken == JsonToken.VALUE_NUMBER_INT) {
            putIntegral(result, name, parser.getLongValue());
        } else if (valueToken == JsonToken.VALUE_NUMBER_FLOAT) {
            result.put(name, Double.valueOf(parser.getDoubleValue()));
        } else if (valueToken == JsonToken.VALUE_TRUE || valueToken == JsonToken.VALUE_FALSE) {
            result.put(name, Boolean.valueOf(parser.getBooleanValue()));
        } else {
            throw InvalidFormatException.from(parser, "扩展参数只允许字符串、数字或布尔值：" + name,
                    valueToken == null ? null : valueToken.asString(), Object.class);
        }
    }

    private static void putIntegral(Map<String, Object> result, String name, long integral) {
        if (integral >= Integer.MIN_VALUE && integral <= Integer.MAX_VALUE) {
            result.put(name, Integer.valueOf((int) integral));
        } else {
            result.put(name, Long.valueOf(integral));
        }
    }
}
