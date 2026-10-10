package com.clara.kernel.http;

import com.clara.context.ConvContext;
import com.clara.core.model.IRequest;
import com.clara.dto.model.RawHttpRequest;
import com.clara.kernel.config.ProviderConfiguration;
import com.clara.kernel.message.BasicParameters;
import com.clara.kernel.message.ImageParameters;
import com.clara.kernel.message.provider.ProviderRequestEncoder;
import lombok.Data;
import lombok.experimental.SuperBuilder;

/**
 * HTTP 请求对象：直接承载平台 HTTP 接口线格式的请求参数，是 {@link ImageParameters} 的具体实现。
 *
 * <p>是什么、在哪一层：实现层（{@code com.clara.kernel.http}）的 {@link IRequest} 实现，继承链为
 * {@code HttpRequest} → {@link ImageParameters} → {@link BasicParameters}，
 * 因此同时具备对话通用参数与图片生成专有字段（{@code prompt} / {@code image} / {@code size} 等）；字段上的
 * {@code @ProviderAdapter} 注解决定编成哪种线格式，本类不含平台分支。与出参侧的 {@link HttpResponse} 对称。
 *
 * <p>谁创建、谁调用：由调用方（宿主应用的 Agent / CLI 或媒体能力调用方）用 {@code builder()} 构造并填充
 * 字段，再交给 {@link com.clara.kernel.api.execute.IHttpExecute} 发送；传输层通过 {@link #toRequest()}
 * 取得线格式请求体。
 *
 * <p>使用方法：
 * <pre>{@code
 * HttpRequest request = HttpRequest.builder()
 *         .model("seedream-4.5")
 *         .prompt("一只猫")
 *         .stream(false)
 *         .build();
 * RawHttpRequest raw = request.toRequest();
 * }</pre>
 *
 * <p>失败语义：{@link #toRequest()} 依赖线程上下文（{@code ConvContext}）中已绑定的平台配置与能力种类，
 * 未绑定时无法定位编解码规则，编码失败会抛出领域异常。
 */
@Data
@SuperBuilder
public class HttpRequest extends ImageParameters {

    /**
     * 返回本请求的实际类型。
     *
     * @param <T> 请求类型
     * @return {@code HttpRequest.class}，非 null
     */
    @Override
    public <T extends IRequest> Class<T> getCls() {
        return (Class<T>) HttpRequest.class;
    }

    /**
     * 取当前线程上下文（{@code ConvContext}）绑定的平台配置，把本对象编码为线格式请求体。
     *
     * <p>平台与能力来自 {@code ConvContext}，字段与平台 JSON 的对应关系由字段上的 {@code @ProviderAdapter}
     * 声明，因此调用前必须已绑定平台配置与能力种类。
     *
     * @return 线格式 HTTP 请求，非 null
     */
    @Override
    public RawHttpRequest toRequest() {
        ProviderConfiguration providerConfiguration = ConvContext.current().getProviderConfiguration();
        return ProviderRequestEncoder.encode(this, providerConfiguration);
    }
}
