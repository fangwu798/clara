package com.clara.utils.deserialize;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

import java.io.IOException;

/**
 * 把 YAML 字符串绑定为内联密钥的 {@code char[]}。
 *
 * <p>是什么、在哪一层：本类属于 {@code clara-model} 的配置解析层（{@code com.clara.model.configuration.deserialize}），
 * 是实现层组件。配置里的 {@code apiKey} 以 {@code char[]} 保存（便于使用后清零），而 Jackson 对
 * {@code char[]} 的默认处理是按字符数组逐个绑定，与「一个字符串就是一份密钥」的语义不符，因此用本类
 * 显式转换。
 *
 * <p>谁创建谁调用：由 Jackson 在绑定 {@code ProviderCredentialConfiguration.apiKey} 时自动实例化并调用；
 * 业务代码不直接使用本类。
 *
 * <p>使用方法：在 {@code char[]} 类型的配置字段或构造器参数上标注
 * {@code @JsonDeserialize(using = SecretCharsDeserializer.class)}。缺失或显式 null 时返回 null，
 * 由配置类构造器校验「环境变量与内联密钥二选一」。
 *
 * <p>生命周期与线程安全：无状态，Jackson 可复用同一实例。返回的新数组由配置类持有，其清零由
 * {@code ProviderCredentialConfiguration#close()} 负责。
 *
 * <p>失败语义：不主动抛异常——非文本节点按 {@link JsonParser#getValueAsString()} 的宽松语义处理，
 * 「二选一」等业务约束由配置类构造器给出中文错误。密钥明文不写入任何日志或异常消息。
 */
public final class SecretCharsDeserializer extends JsonDeserializer<char[]> {

    /**
     * 绑定内联密钥。
     *
     * @param parser  当前 JSON/YAML 解析器，非 null
     * @param context 反序列化上下文，非 null（本类不使用）
     * @return 密钥字符数组；字段缺失或值为 null 时返回 null
     * @throws IOException 读取失败时
     */
    @Override
    public char[] deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        String value = parser.getValueAsString();
        return value == null ? null : value.toCharArray();
    }
}
