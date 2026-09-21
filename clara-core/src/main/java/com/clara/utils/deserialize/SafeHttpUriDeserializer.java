package com.clara.utils.deserialize;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;

/**
 * 把 YAML 字符串绑定为「安全的绝对 HTTP(S) URI」。
 *
 * <p>是什么、在哪一层：本类属于 {@code clara-model} 的配置解析层（{@code com.clara.model.configuration.deserialize}），
 * 是实现层组件。它把原先散在配置加载器里的 URI 安全校验搬到反序列化阶段，使配置类只要在字段（或
 * creator 参数）上标注 {@code @JsonDeserialize(using = SafeHttpUriDeserializer.class)} 就能获得同等校验，
 * 不再依赖调用方手工调用校验方法。
 *
 * <p>谁创建谁调用：由 Jackson 在配置绑定过程中自动实例化并调用（每个被标注的字段一次）；业务代码不直接
 * 使用本类。
 *
 * <p>使用方法：在配置类的 URI 字段或构造器参数上标注注解即可，无需手工调用；校验规则为——语法合法、
 * 必须是绝对地址、必须带主机、不得包含用户信息（{@code user:password@host} 形式一律拒绝）、协议只能
 * 是 http 或 https。
 *
 * <p>生命周期与线程安全：无状态，Jackson 可复用同一实例；不存在需要释放的资源。
 *
 * <p>失败语义：值缺失、非文本或不符合上述任一规则时抛 {@link InvalidFormatException}（消息为中文，
 * 由 {@link com.clara.model.configuration.StrictYamlBinder} 附加字段路径与行号后向上抛出），
 * 属于配置错误，不可重试。
 */
public final class SafeHttpUriDeserializer extends JsonDeserializer<URI> {

    /**
     * 绑定并校验一个 URI 配置项。
     *
     * @param parser  当前 JSON/YAML 解析器，非 null
     * @param context 反序列化上下文，非 null（本类不使用）
     * @return 校验通过的绝对 HTTP(S) URI
     * @throws IOException             解析失败时
     * @throws InvalidFormatException 值为空、非法 URI 或不符合安全规则时
     */
    @Override
    public URI deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        String value = parser.getValueAsString();
        if (value == null || value.trim().isEmpty()) {
            throw invalid(parser, value, "不能为空，必须是安全的绝对 HTTP(S) 地址");
        }
        try {
            URI uri = new URI(value);
            if (!uri.isAbsolute() || uri.getHost() == null || uri.getUserInfo() != null
                    || !("https".equalsIgnoreCase(uri.getScheme())
                    || "http".equalsIgnoreCase(uri.getScheme()))) {
                throw invalid(parser, value, "必须是安全的绝对 HTTP(S) 地址：需含主机、不得含用户信息");
            }
            return uri;
        } catch (URISyntaxException exception) {
            throw invalid(parser, value, "不是合法的 URI");
        }
    }

    /**
     * 构造带中文原因的格式异常。
     *
     * @param parser 当前解析器
     * @param value  原始取值，可为 null
     * @param reason 中文原因
     * @return 格式异常实例
     */
    private static InvalidFormatException invalid(JsonParser parser, Object value, String reason) {
        return InvalidFormatException.from(parser, reason, value, URI.class);
    }
}
